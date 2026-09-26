package io.github.mangi.eta.agent.device

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Revocable preview capabilities, independent of the owner IPC credential. */
internal class VirtualDisplayPreviewHttpServer(
    private val discover: () -> Displays,
    private val capture: (Identity?) -> Frame,
    private val prepareClose: ((Identity) -> VirtualDisplayManualClose.Result)? = null,
    private val commitClose: ((Identity, String) -> VirtualDisplayManualClose.Result)? = null,
) {
    data class Identity(val displayId: Int, val uniqueId: String)
    data class Display(val displayId: Int, val uniqueId: String, val phase: String) {
        val identity: Identity get() = Identity(displayId, uniqueId)
    }
    data class Displays(val displays: List<Display> = emptyList(), val error: String = "")
    data class Frame(val png: ByteArray? = null, val displayId: Int = -1,
        val phase: String = "unknown", val error: String = "", val uniqueId: String = "")
    data class Ticket(val port: Int, val token: String, val controlToken: String? = null) {
        val viewerUri: String get() = "http://127.0.0.1:3070/#eta-preview=$port.$token" +
            (controlToken?.let { "&eta_control=$it" } ?: "")
        override fun toString() = "PreviewTicket(port=$port, credential=redacted)"
    }
    data class Request(val method: String, val path: String, val headers: Map<String, String>) {
        override fun toString() = "PreviewRequest(method=$method, headers=redacted)"
    }
    private val token = capability()
    private val controlToken = if (prepareClose != null && commitClose != null) capability() else null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val captureLock = ReentrantLock()
    private val closeGrant = VirtualDisplayCloseGrant()
    private var lastCaptureNs = 0L
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(2), { task ->
            Thread(task, "eta-vd-preview-reader").apply { isDaemon = true }
        })
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var running = false

    @Synchronized fun start(): Ticket {
        check(listener == null)
        // No wildcard-address fallback, even when binding fails.
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        listener = server
        running = true
        Thread({
            while (running) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                clients.add(socket)
                try { workers.execute { serve(socket, server.localPort) } }
                catch (_: Exception) { clients.remove(socket); runCatching { socket.close() } }
            }
        }, "eta-vd-preview-listener").apply { isDaemon = true }.start()
        return Ticket(server.localPort, token, controlToken)
    }

    @Synchronized fun stop() {
        running = false
        closeGrant.revoke()
        runCatching { listener?.close() }
        clients.forEach { runCatching { it.close() } }
        // Do not interrupt an operation sharing the authenticated owner transport.
        workers.shutdown()
    }

    private fun serve(socket: Socket, port: Int) {
        try {
            socket.soTimeout = 2_000
            val request = try { readRequest(socket.getInputStream()) } catch (_: Exception) {
                write(socket, 400, "application/json", errorJson("PREVIEW_REQUEST_INVALID"), null)
                return
            }
            val decision = authorize(request, port, token, controlToken)
            val origin = request.headers["origin"]?.takeIf { it in ALLOWED_ORIGINS }
            if (decision != 200) {
                write(socket, decision, "application/json", errorJson(protocolError(decision)), origin)
                return
            }
            // Authentication precedes identity parsing and all owner access.
            val route = request.path
            val selected = try { selectedIdentity(request) } catch (_: IllegalArgumentException) {
                writeError(socket, "PREVIEW_IDENTITY_INVALID", origin); return
            }
            if (!captureLock.tryLock()) {
                writeError(socket, "PREVIEW_BUSY", origin); return
            }
            try {
                if (isClosePath(route)) {
                    val result = try {
                        if (!running) return
                        val identity = requireNotNull(selected)
                        if (route == CLOSE_PREPARE_PATH) {
                            closeGrant.revoke()
                            prepareClose!!.invoke(identity).also { prepared ->
                                if (prepared.outcome == "prepared") closeGrant.issue(requireNotNull(prepared.nonce), identity)
                            }
                        } else {
                            val nonce = request.headers.getValue("x-eta-close-nonce")
                            if (!closeGrant.consume(nonce, identity)) VirtualDisplayManualClose.Result("blocked", "CLOSE_NONCE_INVALID")
                            else commitClose!!.invoke(identity, nonce)
                        }
                    } catch (ex: Exception) {
                        if (ex is InterruptedException) Thread.currentThread().interrupt()
                        // An unexpected commit failure cannot certify absence of mutation.
                        if (route == CLOSE_COMMIT_PATH) VirtualDisplayManualClose.Result("closed_unconfirmed", "RELEASE_UNCONFIRMED")
                        else VirtualDisplayManualClose.Result("blocked", "CLOSE_UNAVAILABLE")
                    }
                    if (!running) return
                    val status = when {
                        result.confirmed || result.outcome == "prepared" -> 200
                        result.outcome == "blocked" -> 409
                        else -> 503
                    }
                    write(socket, status, "application/json", resultJson(result), origin)
                    return
                }
                if (route == DISPLAYS_PATH) {
                    val result = try { discover() } catch (_: Exception) { Displays(error = "PREVIEW_UNAVAILABLE") }
                    if (!running) return
                    if (result.error.isNotEmpty()) {
                        writeError(socket, result.error, origin)
                    } else if (result.displays.size > 1 || result.displays.any { !validIdentity(it.identity) }) {
                        writeError(socket, "OWNER_STATE_UNKNOWN", origin)
                    } else {
                        val entries = result.displays.joinToString(",") {
                            "{\"displayId\":${it.displayId},\"uniqueId\":\"${it.uniqueId}\",\"phase\":\"${safePhase(it.phase)}\"}"
                        }
                        write(socket, 200, "application/json",
                            "{\"ok\":true,\"displays\":[$entries]}".toByteArray(Charsets.UTF_8), origin)
                    }
                    return
                }
                val now = System.nanoTime()
                if (lastCaptureNs != 0L && now - lastCaptureNs < 500_000_000L) {
                    writeError(socket, "PREVIEW_BUSY", origin); return
                }
                lastCaptureNs = now
                val frame = try { capture(selected) } catch (_: Exception) { Frame(error = "PREVIEW_UNAVAILABLE") }
                if (!running) return
                val png = frame.png
                val actual = Identity(frame.displayId, frame.uniqueId)
                when {
                    frame.error.isNotEmpty() -> writeError(socket, frame.error, origin)
                    !validIdentity(actual) -> writeError(socket, "FRAME_INVALID", origin)
                    selected != null && selected != actual -> writeError(socket, "PREVIEW_DISPLAY_GONE", origin)
                    png == null || !validPng(png) -> writeError(socket, "PREVIEW_UNAVAILABLE", origin)
                    else -> write(socket, 200, "image/png", png, origin,
                        "X-Eta-Display-Id: ${frame.displayId}\r\n" +
                        "X-Eta-Display-Unique-Id: ${frame.uniqueId}\r\nX-Eta-Phase: ${safePhase(frame.phase)}\r\n")
                }
            } finally { captureLock.unlock() }
        } catch (_: Exception) {
            // No URLs, headers, credentials, screenshots or exception payloads are logged.
        } finally { clients.remove(socket); runCatching { socket.close() } }
    }

    private fun writeError(socket: Socket, error: String, origin: String?) {
        val code = error.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "PREVIEW_UNAVAILABLE"
        val status = when (code) {
            "PREVIEW_IDENTITY_INVALID" -> 400
            "NO_VIRTUAL_SESSION" -> 404
            "PREVIEW_DISPLAY_GONE" -> 410
            "PREVIEW_BUSY" -> 429
            else -> 503
        }
        write(socket, status, "application/json", errorJson(code), origin)
    }

    private fun write(socket: Socket, status: Int, type: String, body: ByteArray, origin: String?, extra: String = "") {
        val payload = if (status == 204) byteArrayOf() else body
        val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 410 -> "Gone"
            429 -> "Too Many Requests"; else -> "Service Unavailable" }
        val cors = if (origin == null) "" else
            "Access-Control-Allow-Origin: $origin\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Authorization, X-Eta-Control-Token, X-Eta-Display-Id, X-Eta-Display-Unique-Id, X-Eta-Close-Nonce\r\nAccess-Control-Max-Age: 60\r\n" +
            "Access-Control-Expose-Headers: X-Eta-Display-Id, X-Eta-Display-Unique-Id, X-Eta-Phase\r\n"
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store, max-age=0\r\nPragma: no-cache\r\n" +
            "X-Content-Type-Options: nosniff\r\nVary: Origin\r\n" + cors + extra + "\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(payload); flush() }
    }

    companion object {
        const val MAX_FRAME_BYTES = 6 * 1024 * 1024
        const val DISPLAYS_PATH = "/eta-preview/displays"
        const val FRAME_PATH = "/eta-preview/frame"
        const val CLOSE_PREPARE_PATH = "/eta-preview/close/prepare"
        const val CLOSE_COMMIT_PATH = "/eta-preview/close/commit"
        val ALLOWED_ORIGINS = setOf("http://127.0.0.1:3070", "http://localhost:3070")
        private val READ_HEADERS = setOf("authorization", "x-eta-display-id", "x-eta-display-unique-id")
        private fun capability(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        private fun isClosePath(path: String) = path == CLOSE_PREPARE_PATH || path == CLOSE_COMMIT_PATH
        private fun equalToken(expected: String, actual: String?): Boolean = actual != null &&
            MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
        fun validPng(bytes: ByteArray): Boolean = bytes.size in 8..MAX_FRAME_BYTES &&
            bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }
        // The Android unique id is opaque, never normalized. This alphabet is JSON/header safe.
        fun validIdentity(identity: Identity): Boolean = identity.displayId > 0 &&
            identity.uniqueId.matches(Regex("[A-Za-z0-9:_,.\\-]{1,512}"))
        private fun safePhase(phase: String): String =
            phase.takeIf { it.matches(Regex("[a-z_]{1,32}")) } ?: "unknown"
        private fun errorJson(code: String): ByteArray =
            ("{\"ok\":false,\"error\":\"$code\",\"outcome\":\"blocked\",\"reason\":\"$code\",\"nonce\":null,\"expiresInMs\":null}")
                .toByteArray(Charsets.UTF_8)
        private fun quoted(value: String): String = "\"" + value.flatMap { ch ->
            when (ch) {
                '\\' -> "\\\\".toList()
                '"' -> "\\\"".toList()
                else -> if (ch.code < 32) "\\u${ch.code.toString(16).padStart(4, '0')}".toList() else listOf(ch)
            }
        }.joinToString("") + "\""
        internal fun resultJson(result: VirtualDisplayManualClose.Result): ByteArray =
            ("{\"outcome\":${quoted(result.outcome)},\"reason\":${quoted(result.reason)}," +
                "\"nonce\":${result.nonce?.let(::quoted) ?: "null"},\"expiresInMs\":${result.expiresInMs ?: "null"}}")
                .toByteArray(Charsets.UTF_8)

        /** Absent pair remains valid only for legacy GET; mutations require an exact selection. */
        fun selectedIdentity(request: Request): Identity? {
            require(request.path in setOf(FRAME_PATH, DISPLAYS_PATH, CLOSE_PREPARE_PATH, CLOSE_COMMIT_PATH))
            val rawId = request.headers["x-eta-display-id"]
            val unique = request.headers["x-eta-display-unique-id"]
            if (rawId == null && unique == null && !isClosePath(request.path)) return null
            require(request.path != DISPLAYS_PATH)
            require(rawId != null && unique != null)
            require(rawId.matches(Regex("[1-9][0-9]{0,9}")))
            val identity = Identity(requireNotNull(rawId.toIntOrNull()), unique)
            require(validIdentity(identity))
            return identity
        }

        private fun protocolError(status: Int): String = when (status) {
            400 -> "PREVIEW_REQUEST_INVALID"
            401 -> "PREVIEW_AUTH_INVALID"
            404 -> "NO_VIRTUAL_SESSION"
            405 -> "PREVIEW_METHOD_NOT_ALLOWED"
            429 -> "PREVIEW_BUSY"
            204 -> ""
            else -> "PREVIEW_ACCESS_DENIED"
        }

        fun authorize(request: Request, port: Int, token: String, controlToken: String? = null): Int {
            if (request.headers["host"] !in setOf("127.0.0.1:$port", "localhost:$port") ||
                request.headers["origin"] !in ALLOWED_ORIGINS) return 403
            val path = request.path.substringBefore('?')
            if (path !in setOf(FRAME_PATH, DISPLAYS_PATH, CLOSE_PREPARE_PATH, CLOSE_COMMIT_PATH)) return 404
            if (request.headers.containsKey("transfer-encoding") || request.headers.containsKey("expect") ||
                request.headers["content-length"]?.let { it != "0" } == true) return 403
            val close = isClosePath(path)
            if (request.method == "OPTIONS") {
                if ('?' in request.path) return 400
                val names = request.headers["access-control-request-headers"].orEmpty()
                    .split(',').map { it.trim().lowercase(Locale.ROOT) }
                val allowed = if (close) READ_HEADERS + "x-eta-control-token" +
                    (if (path == CLOSE_COMMIT_PATH) setOf("x-eta-close-nonce") else emptySet()) else READ_HEADERS
                val valid = if (close) controlToken != null && names.toSet() == allowed
                    else "authorization" in names && names.all { it in allowed } &&
                        (("x-eta-display-id" in names) == ("x-eta-display-unique-id" in names))
                return if (request.headers["access-control-request-method"] == (if (close) "POST" else "GET") &&
                    valid && names.size == names.toSet().size) 204 else 403
            }
            if (request.method != (if (close) "POST" else "GET")) return 405
            if (!equalToken("Bearer $token", request.headers["authorization"])) return 401
            if (close && (controlToken == null || !equalToken(controlToken, request.headers["x-eta-control-token"]))) return 403
            if ('?' in request.path) return 400
            if (path == CLOSE_COMMIT_PATH && request.headers["x-eta-close-nonce"]
                    ?.matches(Regex("[A-Za-z0-9_-]{1,128}")) != true) return 400
            if (path == CLOSE_PREPARE_PATH && request.headers.containsKey("x-eta-close-nonce")) return 400
            return 200
        }

        fun readRequest(input: InputStream): Request {
            var remaining = 8192
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    check(remaining-- > 0 && bytes.size() < 2048)
                    val value = input.read()
                    check(value >= 0)
                    if (value == 10) {
                        val raw = bytes.toByteArray()
                        check(raw.isNotEmpty() && raw.last() == 13.toByte())
                        return String(raw, 0, raw.size - 1, Charsets.US_ASCII)
                    }
                    check(value == 9 || value == 13 || value in 32..126)
                    bytes.write(value)
                }
            }
            val start = line().split(' ')
            check(start.size == 3 && start[2] in setOf("HTTP/1.0", "HTTP/1.1"))
            val headers = linkedMapOf<String, String>()
            while (true) {
                val raw = line()
                if (raw.isEmpty()) break
                check(headers.size < 32 && !raw.startsWith(' ') && !raw.startsWith('\t'))
                val split = raw.indexOf(':')
                check(split > 0)
                val key = raw.substring(0, split).lowercase(Locale.ROOT)
                val value = raw.substring(split + 1).trim()
                check(key.matches(Regex("[a-z0-9-]+")) && !headers.containsKey(key))
                check(value.none { it == '\r' || it == '\n' })
                headers[key] = value
            }
            // Never consume a body or a pipelined second request on this connection.
            check(input.available() == 0)
            return Request(start[0], start[1], headers)
        }
    }
}
