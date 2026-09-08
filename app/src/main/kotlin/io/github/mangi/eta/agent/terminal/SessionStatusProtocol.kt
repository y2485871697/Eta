package io.github.mangi.eta.agent.terminal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/**
 * 持久 shell 会话的状态行协议：每条命令结束后向同一 stdin 追加一个随机 marker 的 printf，
 * 输出退出码与执行后的 PWD。宿主侧据此切分输出、跟踪 cwd；marker 含随机 UUID，
 * 正常命令输出不会与之混淆。
 */
internal object SessionStatusProtocol {

    fun newMarker(): String = "__ETA_STATUS_${UUID.randomUUID().toString().replace("-", "")}"

    fun statusCommand(marker: String): String =
        "printf '\\n$marker:%s:%s\\n' \"\$?\" \"\$PWD\""

    /**
     * 单逻辑行协议：命令经 eval 执行，状态 printf 与命令在同一行，由 shell 在命令退出后自己输出。
     * 状态行不作为独立行进入 stdin——交互式命令（read、REPL 等）读 stdin 时不会吃掉标记，
     * 会话运行期间写入的用户输入也完整留给前台进程。
     */
    fun commandLine(marker: String, command: String): String =
        "eval ${shellQuote(command)}; eta_ec=\$?; printf '\\n$marker:%s:%s\\n' \"\$eta_ec\" \"\$PWD\""

    fun isStatusLine(line: String, marker: String): Boolean = line.startsWith("$marker:")

    /** 解析状态行；cwd 为空（空行段）时返回 null，由调用方回退到会话当前 cwd。 */
    fun parseStatusLine(line: String, marker: String): Status? {
        if (!isStatusLine(line, marker)) return null
        val status = line.removePrefix("$marker:")
        val separator = status.indexOf(':')
        if (separator <= 0) return Status(exitCode = -1, cwd = null)
        return Status(
            exitCode = status.take(separator).toIntOrNull() ?: -1,
            cwd = status.drop(separator + 1).ifBlank { null },
        )
    }

    data class Status(val exitCode: Int, val cwd: String?)
}

/** 有界输出收集器：读取线程持续排空管道，超过上限后丢弃后续内容并标记截断。 */
internal class ByteArrayOutputCollector {
    private val output = ByteArrayOutputStream()
    private var totalBytesRead = 0L
    private var truncated = false

    fun readFrom(input: java.io.InputStream, maxBytes: Int = Int.MAX_VALUE) {
        runCatching {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                synchronized(this) {
                    totalBytesRead += read.toLong()
                    val allowed = (maxBytes - output.size()).coerceAtLeast(0)
                    if (allowed > 0) {
                        output.write(buffer, 0, read.coerceAtMost(allowed))
                    }
                    if (read > allowed) {
                        truncated = true
                    }
                }
            }
        }.onFailure { throwable ->
            if (throwable !is IOException) throw throwable
        }
    }

    fun bytes(): ByteArray = synchronized(this) { output.toByteArray() }

    fun text(): String = bytes().decodeToString()

    fun totalBytesRead(): Long = synchronized(this) { totalBytesRead }

    fun isTruncated(): Boolean = synchronized(this) { truncated }

    fun clear() {
        synchronized(this) {
            output.reset()
            totalBytesRead = 0
            truncated = false
        }
    }
}
