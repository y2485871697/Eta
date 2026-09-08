package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 history 正文从 Messenger Bundle 中移出。
 *
 * 发送端把 history 序列化后写入临时文件，并通过只读文件描述符交给 Runtime；
 * 接收端读取文件后恢复成消息列表。文件本身由发送端持有并负责清理。
 */
internal object AgentRuntimeHistoryTransfer {
    private const val HISTORY_TRANSFER_DIRECTORY = "agent-runtime-history"
    private const val MAX_HISTORY_FILE_BYTES = 16 * 1024 * 1024L
    private const val STALE_FILE_AGE_MILLIS = 6 * 60 * 60 * 1_000L

    class PreparedHistory internal constructor(
        val descriptor: ParcelFileDescriptor,
        private val file: File,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { descriptor.close() }
            runCatching { file.delete() }
        }
    }


    fun prepare(
        context: Context,
        history: List<AgentModelClient.ConversationMessage>,
    ): PreparedHistory {
        val cacheDirectory = File(context.cacheDir, HISTORY_TRANSFER_DIRECTORY).apply {
            if (!isDirectory && !mkdirs()) {
                throw IllegalStateException("无法创建 history 传输缓存")
            }
        }
        cleanupStaleFiles(cacheDirectory)

        val file = File(cacheDirectory, "history-${UUID.randomUUID()}.json")
        val encoded = AgentConversationCodec.encodeTranscriptForStorage(history)
        if (encoded.length > MAX_HISTORY_FILE_BYTES) {
            throw AgentRuntimeWire.PayloadTooLargeException(encoded.length)
        }
        file.writeText(encoded, Charsets.UTF_8)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            ?: throw IllegalStateException("无法打开 history 文件描述符")
        return PreparedHistory(descriptor, file)
    }

    fun readFromBundle(bundle: Bundle): List<AgentModelClient.ConversationMessage> {
        bundle.getParcelable(AgentRuntimeWire.KEY_HISTORY_FD, ParcelFileDescriptor::class.java)
            ?.use { descriptor ->
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                if (bytes.size > MAX_HISTORY_FILE_BYTES) {
                    throw AgentRuntimeWire.PayloadTooLargeException(bytes.size)
                }
                val raw = String(bytes, Charsets.UTF_8)
                return AgentConversationCodec.decodeTranscript(raw)
            }
        // 兼容旧客户端：history 仍内联在 Bundle 中。
        return bundle.getParcelableArrayList(AgentRuntimeWire.KEY_HISTORY, Bundle::class.java)
            .orEmpty()
            .map { message ->
                AgentModelClient.ConversationMessage(
                    role = message.getString(AgentRuntimeWire.KEY_ROLE).orEmpty(),
                    content = message.getString(AgentRuntimeWire.KEY_CONTENT).orEmpty(),
                    contentJson = message.getString(AgentRuntimeWire.KEY_CONTENT_JSON).orEmpty(),
                    toolCallId = message.getString(AgentRuntimeWire.KEY_TOOL_CALL_ID).orEmpty(),
                    reasoningContent = message.getString(AgentRuntimeWire.KEY_REASONING_CONTENT).orEmpty(),
                    toolCallsJson = message.getString(AgentRuntimeWire.KEY_TOOL_CALLS_JSON).orEmpty(),
                )
            }
    }

    private fun cleanupStaleFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }
}
