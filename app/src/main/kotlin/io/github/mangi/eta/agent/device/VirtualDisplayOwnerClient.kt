package io.github.mangi.eta.agent.device

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process as AndroidProcess
import io.github.mangi.eta.core.AgentLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * App 侧连接 root 虚拟屏 owner 的客户端。
 *
 * owner 是 `vd.runtime.VirtualDisplayOwnerMain`，由本类通过 [RootSu] 在 root 的
 * app_process 里拉起（CLASSPATH=apk 的 sourceDir 与 splits）。owner 在启动时向
 * stdout 打印一行 `VD_OWNER_READY`，随后只在 ABSTRACT 本地套接字上收发换行分隔的
 * JSON 请求/响应。
 *
 * 本类只做四件事：
 * 1. 启动 owner，并在超时内读取一行 READY（读取在后台线程，主调用线程只等待有界时间）。
 * 2. 连接 ABSTRACT 套接字，并核对 peer 凭据（uid==0 且 pid==启动握手里的 pid）。
 * 3. 串行发送 `{v,op,token,...}` 请求，读取有界响应。
 * 4. 暴露客户端状态与原始响应，绝不把 token 交给模型、日志或文件。
 *
 * 安全与失败语义：
 * - token 只存在于本对象私有字段和请求报文里，不进入日志、不进入 toString、不进入异常。
 * - 请求失败（超时、IO、协议）只返回错误响应，绝不杀死 owner，也绝不回退到物理屏幕。
 * - 启动阶段一旦不确定（超时、READY 解析失败、peer 校验失败），只终结本次刚拉起的进程并
 *   返回失败；绝不自动重放创建，避免留下无法对账的孤儿 owner。
 *
 * 已知限制（实现时 owner 源码尚未并入本 worktree）：
 * - 各 op 的 payload 字段由 owner 定义；本类对 launch 只要求 `component` 非空且原样透传，
 *   对 input/handoff 透传调用方给的扁平字段，不做 schema 校验。
 * - 响应里的成功标记按 `ok` 或存在 `error`/`errorCode` 判定；owner 若用其它字段表达失败，
 *   本类无法识别（见 [parseResponse]）。
 * - owner 目前没有 handoff 实现，[handoff] 只是把 op 发出去；缺失操作由 owner 返回错误，
 *   本类原样上报，不会伪装成功。
 */
internal class VirtualDisplayOwnerClient private constructor(
    private val logger: AgentLogger,
    private val process: Process?,
    private val socket: LocalSocket,
    private val output: OutputStream,
    private val reader: LineReader,
    /** 本次连接使用的 ABSTRACT 套接字名（非机密）。 */
    val socketName: String,
    /** owner 进程 pid，来自 READY 并已与套接字 peer pid 核对。 */
    val ownerPid: Long,
    /** owner 创建的虚拟屏 displayId，来自 READY。 */
    val displayId: Int,
    /** owner 本次创建的稳定标识，来自 READY。 */
    val uniqueId: String,
    /** Used only by the owning app to persist a reconnect capability. */
    val recoveryToken: String get() = token
    /** 与本次创建绑定的 runId；所有需要 runId 的操作默认使用它，且必须非空。 */
    val runId: String,
    private val token: String,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** owner 进程是否仍在运行且本客户端未关闭。 */
    val isAlive: Boolean get() = !closed.get() && (process?.isAlive ?: true)

    /**
     * 发送一次串行请求。payload 的键会被扁平并入请求顶层，禁止覆盖 `v`/`op`/`token`。
     * 失败只返回 [OwnerResponse.ok] 为 false 的响应，不杀死 owner、不回退物理屏。
     */
    fun request(
        op: String,
        payload: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    ): OwnerResponse {
        if (closed.get()) return errorResponse(op, VirtualDisplayOwnerError.CLIENT_CLOSED)
        val invalid = validateRequest(op, payload)
        if (invalid != null) return errorResponse(op, invalid)
        val effectiveTimeout = timeoutMillis.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val effectiveLimit = maxResponseBytes.coerceIn(1, SCREENSHOT_MAX_RESPONSE_BYTES)

        val started = android.os.SystemClock.elapsedRealtime()
        if (!lock.tryLock(effectiveTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            return errorResponse(op, VirtualDisplayOwnerError.REQUEST_TIMEOUT)
        }
        val remaining = effectiveTimeout - (android.os.SystemClock.elapsedRealtime() - started)
        if (remaining <= 0) { lock.unlock(); return errorResponse(op, VirtualDisplayOwnerError.REQUEST_TIMEOUT) }
        val done = AtomicBoolean(false)
        val watchdog = thread(isDaemon = true, name = "vd-request-deadline") {
            try { Thread.sleep(remaining) } catch (_: InterruptedException) { return@thread }
            if (!done.get()) close()
        }
        try {
            if (closed.get()) return errorResponse(op, VirtualDisplayOwnerError.CLIENT_CLOSED)
            socket.setSoTimeout(remaining.toInt())
            output.write(buildRequestLine(op, payload + ("timeoutMs" to remaining)).toByteArray(StandardCharsets.UTF_8))
            output.flush()
            val line = reader.readLine(effectiveLimit)
            val result = if (line == null) errorResponse(op, VirtualDisplayOwnerError.RESPONSE_PROTOCOL) else parseResponse(op, line)
            if (result.errorCode == VirtualDisplayOwnerError.RESPONSE_PROTOCOL) close()
            return result
        } catch (_: IOException) {
            close()
            return errorResponse(op, VirtualDisplayOwnerError.REQUEST_IO)
        } finally {
            done.set(true); watchdog.interrupt(); lock.unlock()
        }
    }

    /** `status`：读取 owner 侧状态。 */
    fun status(timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS): OwnerResponse =
        request(VirtualDisplayOwnerProtocol.OP_STATUS, emptyMap(), timeoutMillis)

    /**
     * `launch`：启动一个组件。
     *
     * `component` 必须是从 owner inspect 结果原样拷贝的字符串，本类不改写、不规范它。
     * runId 必须非空，默认使用与本次创建绑定的 [runId]。
     */
    fun launch(
        component: String,
        runId: String = this.runId,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): OwnerResponse {
        if (component.isEmpty() || !isSafeFieldValue(component)) {
            return errorResponse(VirtualDisplayOwnerProtocol.OP_LAUNCH, VirtualDisplayOwnerError.REQUEST_INVALID)
        }
        val run = runId.ifBlank {
            return errorResponse(VirtualDisplayOwnerProtocol.OP_LAUNCH, VirtualDisplayOwnerError.REQUEST_INVALID)
        }
        return request(
            VirtualDisplayOwnerProtocol.OP_LAUNCH,
            linkedMapOf(
                VirtualDisplayOwnerProtocol.FIELD_COMPONENT to component,
                VirtualDisplayOwnerProtocol.FIELD_RUN_ID to run,
            ),
            timeoutMillis,
        )
    }

    /** `input`：透传调用方给的扁平输入字段（键值由 owner 定义），runId 必须非空。 */
    fun input(
        fields: Map<String, Any?>,
        runId: String = this.runId,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): OwnerResponse {
        val run = runId.ifBlank {
            return errorResponse(VirtualDisplayOwnerProtocol.OP_INPUT, VirtualDisplayOwnerError.REQUEST_INVALID)
        }
        return request(
            VirtualDisplayOwnerProtocol.OP_INPUT,
            fields + (VirtualDisplayOwnerProtocol.FIELD_RUN_ID to run),
            timeoutMillis,
        )
    }

    /** `snapshot`：请求截图，响应上限放宽到 16MB。 */
    fun snapshot(
        runId: String = this.runId,
        timeoutMillis: Long = SCREENSHOT_REQUEST_TIMEOUT_MS,
    ): OwnerResponse {
        val run = runId.ifBlank {
            return errorResponse(VirtualDisplayOwnerProtocol.OP_SNAPSHOT, VirtualDisplayOwnerError.REQUEST_INVALID)
        }
        return request(
            VirtualDisplayOwnerProtocol.OP_SNAPSHOT,
            linkedMapOf(VirtualDisplayOwnerProtocol.FIELD_RUN_ID to run),
            timeoutMillis,
            SCREENSHOT_MAX_RESPONSE_BYTES,
        )
    }

    /** `handoff`：透传调用方给的扁平字段；owner 未实现时会返回错误，本类原样上报。 */
    fun handoff(
        fields: Map<String, Any?> = emptyMap(),
        runId: String = this.runId,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): OwnerResponse {
        val run = runId.ifBlank {
            return errorResponse(VirtualDisplayOwnerProtocol.OP_HANDOFF, VirtualDisplayOwnerError.REQUEST_INVALID)
        }
        return request(
            VirtualDisplayOwnerProtocol.OP_HANDOFF,
            fields + (VirtualDisplayOwnerProtocol.FIELD_RUN_ID to run),
            timeoutMillis,
        )
    }

    /** `release`：请求 owner 释放资源。失败不会杀死 owner。 */
    fun release(
        runId: String = this.runId,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): OwnerResponse {
        val run = runId.ifBlank {
            return errorResponse(VirtualDisplayOwnerProtocol.OP_RELEASE, VirtualDisplayOwnerError.REQUEST_INVALID)
        }
        return request(
            VirtualDisplayOwnerProtocol.OP_RELEASE,
            linkedMapOf(VirtualDisplayOwnerProtocol.FIELD_RUN_ID to run),
            timeoutMillis,
        )
    }

    /**
     * 仅关闭传输，不终结 owner。副屏只能通过已核验的 release 请求关闭。
     *
     * 只应在本次运行结束时调用；请求失败不得触发此方法。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        // Never terminate the owner: it may still hold tasks.
    }

    override fun toString(): String =
        "VirtualDisplayOwnerClient(displayId=$displayId, ownerPid=$ownerPid, runId=$runId, alive=$isAlive)"

    private fun buildRequestLine(op: String, payload: Map<String, Any?>): String {
        val json = JSONObject()
        json.put(VirtualDisplayOwnerProtocol.FIELD_VERSION, VirtualDisplayOwnerProtocol.VERSION)
        json.put(VirtualDisplayOwnerProtocol.FIELD_OP, op)
        json.put(VirtualDisplayOwnerProtocol.FIELD_TOKEN, token)
        for ((key, value) in payload) {
            if (value == null) continue
            json.put(key, value)
        }
        // JSONObject.toString 不含裸换行；换行只作为报文分隔符。
        return json.toString() + "\n"
    }

    private fun validateRequest(op: String, payload: Map<String, Any?>): String? {
        if (!isSafeFieldValue(op)) return VirtualDisplayOwnerError.REQUEST_INVALID
        for (key in payload.keys) {
            if (!isSafeFieldValue(key)) return VirtualDisplayOwnerError.REQUEST_INVALID
            if (key in VirtualDisplayOwnerProtocol.RESERVED_REQUEST_FIELDS) {
                return VirtualDisplayOwnerError.REQUEST_INVALID
            }
        }
        return null
    }

    private fun parseResponse(op: String, line: String): OwnerResponse {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return errorResponse(op, VirtualDisplayOwnerError.RESPONSE_PROTOCOL)
        val json = try {
            JSONObject(trimmed)
        } catch (_: JSONException) {
            return errorResponse(op, VirtualDisplayOwnerError.RESPONSE_PROTOCOL)
        }
        if (json.opt("v") != 1 || json.opt("ok") !is Boolean ||
            (json.optBoolean("ok") && json.optString("op") != op) ||
            (json.has("op") && json.optString("op") != op)) {
            return errorResponse(op, VirtualDisplayOwnerError.RESPONSE_PROTOCOL)
        }
        val errorField = firstNonBlank(json, "error", "errorCode", "error_code")
        return OwnerResponse(op, json.optBoolean("ok") && errorField == null, errorField ?: "", json)
    }

    private fun errorResponse(op: String, code: String): OwnerResponse =
        OwnerResponse(op = op, ok = false, errorCode = code, json = null)

    companion object {
        private const val DEFAULT_STARTUP_TIMEOUT_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 15_000L
        private const val SCREENSHOT_REQUEST_TIMEOUT_MS = 30_000L
        private const val MIN_TIMEOUT_MS = 1_000L
        private const val MAX_TIMEOUT_MS = 120_000L
        private const val DEFAULT_MAX_RESPONSE_BYTES = 1 * 1024 * 1024
        private const val SCREENSHOT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        private const val SOCKET_PREFIX = "eta.vd.owner."
        private const val NAME_PREFIX = "eta-vd-"

        /**
         * 拉起 owner 并完成握手。必须从非主线程调用：读取 READY 在后台线程进行，但本方法会
         * 在调用线程上有界等待。
         */
        fun start(
            context: Context,
            logger: AgentLogger,
            startupTimeoutMillis: Long = DEFAULT_STARTUP_TIMEOUT_MS,
        ): OwnerStartResult {
            val resolution = resolveClasspath(context)
            val classpath = resolution.path
            if (classpath == null) {
                return OwnerStartResult.Failed(
                    resolution.errorCode ?: VirtualDisplayOwnerError.CLASSPATH_UNAVAILABLE,
                )
            }

            val socketName = SOCKET_PREFIX + randomHex(16)
            val ownerName = NAME_PREFIX + randomHex(10)
            val allowUid = AndroidProcess.myUid()
            val script = buildStartupScript(classpath, socketName, allowUid, ownerName)

            val process = try {
                RootSu.process(script).redirectErrorStream(false).start()
            } catch (_: IOException) {
                return OwnerStartResult.Failed(VirtualDisplayOwnerError.PROCESS_START_FAILED)
            } catch (_: RuntimeException) {
                return OwnerStartResult.Failed(VirtualDisplayOwnerError.PROCESS_START_FAILED)
            }

            drainStderr(process)

            val handshake = StartupHandshake(LineReader(process.inputStream))
            handshake.begin()
            return when (val outcome = handshake.await(startupTimeoutMillis)) {
                is HandshakeOutcome.Failed -> {
                    // Never terminate the owner: it may still hold tasks.
                    logger.warn("Agent virtual display owner startup outcome=handshake_failed code=${outcome.errorCode}")
                    OwnerStartResult.Failed(outcome.errorCode)
                }

                is HandshakeOutcome.Ready ->
                    finishStart(logger, process, socketName, allowUid, outcome.line)
            }
        }

        /** Reconnects only to a persisted owner after validating kernel peer identity and status. */
        fun reconnect(
            logger: AgentLogger,
            socketName: String,
            ownerPid: Long,
            displayId: Int,
            uniqueId: String,
            token: String,
            runId: String,
        ): VirtualDisplayOwnerClient? {
            if (socketName.isBlank() || ownerPid <= 0L || displayId < 0 ||
                uniqueId.isBlank() || token.isBlank() || runId.isBlank()) return null
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                val peer = socket.peerCredentials
                if (peer.uid != 0 || peer.pid.toLong() != ownerPid) {
                    socket.close(); return null
                }
                val client = VirtualDisplayOwnerClient(
                    logger = logger,
                    process = null,
                    socket = socket,
                    output = socket.outputStream,
                    reader = LineReader(socket.inputStream),
                    socketName = socketName,
                    ownerPid = ownerPid,
                    displayId = displayId,
                    uniqueId = uniqueId,
                    runId = runId,
                    token = token,
                )
                val status = client.status()
                val body = status.json
                if (!status.ok || body == null || body.optInt("displayId", -1) != displayId ||
                    body.optString("uniqueId") != uniqueId) {
                    client.close(); return null
                }
                return client
            } catch (_: Exception) {
                runCatching { socket.close() }
                return null
            }
        }

        private fun finishStart(
            logger: AgentLogger,
            process: Process,
            socketName: String,
            allowUid: Int,
            readyLine: String,
        ): OwnerStartResult {
            val parsed = parseReady(readyLine, socketName, allowUid)
            if (parsed is ReadyParseResult.Failed) {
                // Never terminate the owner: it may still hold tasks.
                logger.warn("Agent virtual display owner startup outcome=ready_rejected code=${parsed.errorCode}")
                return OwnerStartResult.Failed(parsed.errorCode)
            }
            val ready = (parsed as ReadyParseResult.Parsed).info

            val socket = LocalSocket()
            try {
                val connected = AtomicBoolean(false)
                val deadline = thread(isDaemon = true, name = "vd-connect-deadline") {
                    try { Thread.sleep(CONNECT_TIMEOUT_MS.toLong()) } catch (_: InterruptedException) { return@thread }
                    if (!connected.get()) runCatching { socket.close() }
                }
                try { socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)) }
                finally { connected.set(true); deadline.interrupt() }
            } catch (_: IOException) {
                runCatching { socket.close() }
                // Never terminate the owner: it may still hold tasks.
                logger.warn("Agent virtual display owner startup outcome=connect_failed")
                return OwnerStartResult.Failed(VirtualDisplayOwnerError.CONNECT_FAILED)
            }

            val credentials = runCatching { socket.peerCredentials }.getOrNull()
            if (credentials == null) {
                runCatching { socket.close() }
                // Never terminate the owner: it may still hold tasks.
                return OwnerStartResult.Failed(VirtualDisplayOwnerError.PEER_UNAVAILABLE)
            }
            if (credentials.uid != 0) {
                runCatching { socket.close() }
                // Never terminate the owner: it may still hold tasks.
                logger.warn("Agent virtual display owner startup outcome=peer_uid uid=${credentials.uid}")
                return OwnerStartResult.Failed(VirtualDisplayOwnerError.PEER_UID)
            }
            if (credentials.pid.toLong() != ready.pid) {
                runCatching { socket.close() }
                // Never terminate the owner: it may still hold tasks.
                return OwnerStartResult.Failed(VirtualDisplayOwnerError.PEER_PID)
            }

            val outputStream = try {
                socket.outputStream
            } catch (_: IOException) {
                null
            }
            val inputStream = try {
                socket.inputStream
            } catch (_: IOException) {
                null
            }
            if (outputStream == null || inputStream == null) {
                runCatching { socket.close() }
                // Never terminate the owner: it may still hold tasks.
                return OwnerStartResult.Failed(VirtualDisplayOwnerError.CONNECT_FAILED)
            }

            val client = VirtualDisplayOwnerClient(
                logger = logger,
                process = process,
                socket = socket,
                output = outputStream,
                reader = LineReader(inputStream),
                socketName = socketName,
                ownerPid = ready.pid,
                displayId = ready.displayId,
                uniqueId = ready.uniqueId,
                runId = newRunId(),
                token = ready.token,
            )
            logger.info("Agent virtual display owner ready display=${client.displayId} pid=${client.ownerPid}")
            return OwnerStartResult.Ready(client)
        }
    }
}

/** [VirtualDisplayOwnerClient.start] 的结果。 */
internal sealed interface OwnerStartResult {
    class Ready(val client: VirtualDisplayOwnerClient) : OwnerStartResult
    class Failed(val errorCode: String) : OwnerStartResult
}

/**
 * 一次 owner 请求的响应。
 *
 * [json] 是 owner 返回的原始对象（可能为 null，表示未收到合法 JSON）。
 * [ok] 为 false 时 [errorCode] 是本类识别的错误码或 owner 给出的错误字段。
 */
internal class OwnerResponse(
    val op: String,
    val ok: Boolean,
    val errorCode: String,
    val json: JSONObject?,
) {
    val isError: Boolean get() = !ok

    fun optString(name: String): String? =
        json?.takeIf { it.has(name) && !it.isNull(name) }?.optString(name)

    fun optBoolean(name: String, default: Boolean = false): Boolean =
        json?.optBoolean(name, default) ?: default

    fun optInt(name: String, default: Int = 0): Int =
        json?.optInt(name, default) ?: default

    fun optLong(name: String, default: Long = 0L): Long =
        json?.optLong(name, default) ?: default

    fun optJSONObject(name: String): JSONObject? = json?.optJSONObject(name)

    fun optJSONArray(name: String): JSONArray? = json?.optJSONArray(name)
}

/** owner 协议常量：字段名、op 名与版本。 */
internal object VirtualDisplayOwnerProtocol {
    const val VERSION = 1
    const val READY_PREFIX = "VD_OWNER_READY"
    const val MAIN_CLASS = "vd.runtime.VirtualDisplayOwnerMain"

    const val OP_STATUS = "status"
    const val OP_LAUNCH = "launch"
    const val OP_INPUT = "input"
    const val OP_SNAPSHOT = "snapshot"
    const val OP_HANDOFF = "handoff"
    const val OP_RELEASE = "release"

    const val FIELD_VERSION = "v"
    const val FIELD_OP = "op"
    const val FIELD_TOKEN = "token"
    const val FIELD_COMPONENT = "component"
    const val FIELD_RUN_ID = "runId"

    /** payload 不允许覆盖的协议字段。 */
    val RESERVED_REQUEST_FIELDS = setOf(FIELD_VERSION, FIELD_OP, FIELD_TOKEN)
}

/** 本类自有的错误码。 */
internal object VirtualDisplayOwnerError {
    const val CLASSPATH_UNAVAILABLE = "OWNER_CLASSPATH_UNAVAILABLE"
    const val CLASSPATH_INVALID = "OWNER_CLASSPATH_INVALID"
    const val PROCESS_START_FAILED = "OWNER_PROCESS_START_FAILED"
    const val STARTUP_TIMEOUT = "OWNER_STARTUP_TIMEOUT"
    const val STARTUP_EOF = "OWNER_STARTUP_EOF"
    const val STARTUP_OUTPUT_LIMIT = "OWNER_STARTUP_OUTPUT_LIMIT"
    const val STARTUP_IO = "OWNER_STARTUP_IO"
    const val READY_PROTOCOL = "OWNER_READY_PROTOCOL"
    const val READY_VERSION = "OWNER_READY_VERSION"
    const val READY_UID = "OWNER_READY_UID"
    const val READY_ALLOW_UID = "OWNER_READY_ALLOW_UID"
    const val READY_SOCKET_MISMATCH = "OWNER_READY_SOCKET_MISMATCH"
    const val CONNECT_FAILED = "OWNER_CONNECT_FAILED"
    const val PEER_UNAVAILABLE = "OWNER_PEER_UNAVAILABLE"
    const val PEER_UID = "OWNER_PEER_UID"
    const val PEER_PID = "OWNER_PEER_PID"
    const val CLIENT_CLOSED = "OWNER_CLIENT_CLOSED"
    const val REQUEST_INVALID = "OWNER_REQUEST_INVALID"
    const val REQUEST_IO = "OWNER_REQUEST_IO"
    const val REQUEST_TIMEOUT = "OWNER_REQUEST_TIMEOUT"
    const val RESPONSE_LIMIT = "OWNER_RESPONSE_LIMIT"
    const val RESPONSE_PROTOCOL = "OWNER_RESPONSE_PROTOCOL"
}

/** READY 行里解析出的字段（token 只在内部流转，不对外暴露）。 */
private class ReadyInfo(
    val token: String,
    val pid: Long,
    val displayId: Int,
    val uniqueId: String,
)

private sealed interface ReadyParseResult {
    class Parsed(val info: ReadyInfo) : ReadyParseResult
    class Failed(val errorCode: String) : ReadyParseResult
}

private sealed interface HandshakeOutcome {
    class Ready(val line: String) : HandshakeOutcome
    class Failed(val errorCode: String) : HandshakeOutcome
}

private const val STARTUP_LINE_MAX_BYTES = 64 * 1024
private const val STARTUP_TOTAL_MAX_CHARS = 256 * 1024

/**
 * 后台读取 owner 启动 stdout，直到找到 READY 行或有界失败。
 *
 * 找到 READY 后本线程继续排空 stdout（丢弃日志），避免 owner 因管道写满而阻塞。
 * 调用方通过 [await] 在有界时间内等待结果；超时后由调用方终结进程，读取线程随 EOF 退出。
 */
private class StartupHandshake(private val reader: LineReader) {
    private val latch = CountDownLatch(1)

    @Volatile
    private var readyLine: String? = null

    @Volatile
    private var failure: String? = null

    fun begin(): Thread = thread(isDaemon = true, name = "vd-owner-startup") {
        try {
            var scanned = 0
            while (true) {
                val line = reader.readLine(STARTUP_LINE_MAX_BYTES) ?: break
                scanned += line.length
                if (scanned > STARTUP_TOTAL_MAX_CHARS) {
                    failure = VirtualDisplayOwnerError.STARTUP_OUTPUT_LIMIT
                    break
                }
                if (line.startsWith(VirtualDisplayOwnerProtocol.READY_PREFIX)) {
                    readyLine = line
                    break
                }
            }
            if (readyLine == null && failure == null) {
                failure = VirtualDisplayOwnerError.STARTUP_EOF
            }
        } catch (_: LineLimitException) {
            failure = VirtualDisplayOwnerError.STARTUP_OUTPUT_LIMIT
        } catch (_: IOException) {
            failure = VirtualDisplayOwnerError.STARTUP_IO
        } catch (_: RuntimeException) {
            failure = VirtualDisplayOwnerError.STARTUP_IO
        } finally {
            latch.countDown()
        }
        if (readyLine != null) {
            reader.drainForever()
        }
    }

    fun await(timeoutMillis: Long): HandshakeOutcome {
        val completed = runCatching {
            latch.await(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        readyLine?.let { return HandshakeOutcome.Ready(it) }
        failure?.let { return HandshakeOutcome.Failed(it) }
        return if (completed) {
            HandshakeOutcome.Failed(VirtualDisplayOwnerError.STARTUP_EOF)
        } else {
            HandshakeOutcome.Failed(VirtualDisplayOwnerError.STARTUP_TIMEOUT)
        }
    }
}

/** 按换行切分的读取器，保留跨 read 的残余字节，避免丢包或错位。 */
private class LineReader(private val input: InputStream) {
    private val chunk = ByteArray(16 * 1024)
    private var pending = ByteArray(0)

    /** 读取下一行（不含换行），到达 EOF 时返回 null。超过 [maxBytes] 抛 [LineLimitException]。 */
    fun readLine(maxBytes: Int): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val newlineIndex = indexOfNewline(pending)
            if (newlineIndex >= 0) {
                if (out.size() + newlineIndex > maxBytes) throw LineLimitException()
                out.write(pending, 0, newlineIndex)
                pending = pending.copyOfRange(newlineIndex + 1, pending.size)
                return out.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
            }
            if (pending.isNotEmpty()) {
                if (out.size() + pending.size > maxBytes) throw LineLimitException()
                out.write(pending)
                pending = ByteArray(0)
            }
            val read = input.read(chunk)
            if (read < 0) {
                return if (out.size() == 0) null else out.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
            }
            pending = chunk.copyOfRange(0, read)
        }
    }

    /** 持续读取并丢弃，直到 EOF 或异常；用于排空长寿命进程的 stdout/stderr。 */
    fun drainForever() {
        pending = ByteArray(0)
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                if (input.read(buffer) < 0) return
            }
        } catch (_: IOException) {
            // 进程退出或流关闭，正常结束。
        }
    }

    private fun indexOfNewline(bytes: ByteArray): Int {
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) return i
        }
        return -1
    }
}

private class LineLimitException : IOException("line limit exceeded")

private class ClasspathResolution(val path: String?, val errorCode: String?)

private fun resolveClasspath(context: Context): ClasspathResolution {
    val info = context.applicationInfo
        ?: return ClasspathResolution(null, VirtualDisplayOwnerError.CLASSPATH_UNAVAILABLE)
    val base = info.sourceDir
    if (base.isNullOrEmpty()) return ClasspathResolution(null, VirtualDisplayOwnerError.CLASSPATH_UNAVAILABLE)
    if (!isSafeClasspathEntry(base)) return ClasspathResolution(null, VirtualDisplayOwnerError.CLASSPATH_INVALID)
    val parts = ArrayList<String>()
    parts.add(base)
    info.splitSourceDirs?.forEach { split ->
        if (split.isNullOrEmpty() || !isSafeClasspathEntry(split)) {
            return ClasspathResolution(null, VirtualDisplayOwnerError.CLASSPATH_INVALID)
        }
        parts.add(split)
    }
    return ClasspathResolution(parts.joinToString(separator = ":"), null)
}

/** 冒号是 CLASSPATH 分隔符；控制字符会打断 shell 引用。 */
private fun isSafeClasspathEntry(path: String): Boolean {
    if (path.indexOf(':') >= 0 || path.indexOf('\u0000') >= 0) return false
    for (element in path) {
        if (element.code <= 0x1F || element.code == 0x7F) return false
    }
    return true
}

private fun buildStartupScript(classpath: String, socketName: String, allowUid: Int, ownerName: String): String =
    "export CLASSPATH=" + shellQuote(classpath) +
        "; exec /system/bin/app_process /system/bin " + VirtualDisplayOwnerProtocol.MAIN_CLASS +
        " --socket " + shellQuote(socketName) +
        " --allow-uid " + allowUid +
        " --name " + shellQuote(ownerName)

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * 解析 READY 行：
 * `VD_OWNER_READY v=1 socket=... token=... pid=... uid=0 allowUid=... displayId=... uniqueId=...`
 *
 * 要求全部字段存在、无重复、版本与 uid 正确、allowUid 与本进程一致、socket 与请求名一致。
 */
private fun parseReady(line: String, expectedSocketName: String, expectedAllowUid: Int): ReadyParseResult {
    val tokens = line.trim().split(' ')
    if (tokens.isEmpty() || tokens[0] != VirtualDisplayOwnerProtocol.READY_PREFIX) {
        return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_PROTOCOL)
    }
    val fields = HashMap<String, String>()
    for (index in 1 until tokens.size) {
        val token = tokens[index]
        if (token.isEmpty()) continue
        val eq = token.indexOf('=')
        if (eq <= 0 || eq == token.length - 1) return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_PROTOCOL)
        val key = token.substring(0, eq)
        val value = token.substring(eq + 1)
        if (fields.put(key, value) != null) return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_PROTOCOL)
    }
    val required = listOf("v", "socket", "token", "pid", "uid", "allowUid", "displayId", "uniqueId")
    for (key in required) {
        if (!fields.containsKey(key)) return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_PROTOCOL)
    }
    if (fields.getValue("v") != VirtualDisplayOwnerProtocol.VERSION.toString()) {
        return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_VERSION)
    }
    if (fields.getValue("uid") != "0") {
        return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_UID)
    }
    if (fields.getValue("allowUid").toIntOrNull() != expectedAllowUid) {
        return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_ALLOW_UID)
    }
    val pid = fields.getValue("pid").toLongOrNull()
    if (pid == null || pid <= 0L) return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_PROTOCOL)
    val displayId = fields.getValue("displayId").toIntOrNull()
    if (displayId == null || displayId < 0) return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_PROTOCOL)
    val token = fields.getValue("token")
    if (token.isEmpty()) return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_PROTOCOL)
    val uniqueId = fields.getValue("uniqueId")
    if (uniqueId.isEmpty()) return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_PROTOCOL)
    val socketName = fields.getValue("socket").removePrefix("@")
    if (socketName != expectedSocketName.removePrefix("@")) {
        return ReadyParseResult.Failed(VirtualDisplayOwnerError.READY_SOCKET_MISMATCH)
    }
    return ReadyParseResult.Parsed(
        ReadyInfo(token = token, pid = pid, displayId = displayId, uniqueId = uniqueId),
    )
}

private fun firstNonBlank(json: JSONObject, vararg names: String): String? {
    for (name in names) {
        if (!json.has(name) || json.isNull(name)) continue
        val value = json.optString(name, "")
        if (value.isNotBlank()) return value
    }
    return null
}

/** op 与 payload 键：非空且不含空格/控制字符。 */
private fun isSafeFieldValue(value: String): Boolean {
    if (value.isEmpty()) return false
    for (c in value) {
        val code = c.code
        if (code <= 0x20 || code == 0x7F) return false
    }
    return true
}

private fun terminateOwnerProcess(process: Process) {
    // Deliberately retained. Uncertain startup/IPC is not authority to release the display.
}

private fun drainStderr(process: Process): Thread = thread(isDaemon = true, name = "vd-owner-stderr") {
    val buffer = ByteArray(8 * 1024)
    try {
        while (true) {
            if (process.errorStream.read(buffer) < 0) return@thread
        }
    } catch (_: IOException) {
        // 进程退出或流关闭，正常结束。
    }
}

private fun newRunId(): String = "run-" + randomHex(12)

private val OWNER_RANDOM = SecureRandom()
private const val HEX = "0123456789abcdef"

private fun randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    OWNER_RANDOM.nextBytes(bytes)
    val builder = StringBuilder(byteCount * 2)
    for (b in bytes) {
        val value = b.toInt() and 0xFF
        builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
    return builder.toString()
}
