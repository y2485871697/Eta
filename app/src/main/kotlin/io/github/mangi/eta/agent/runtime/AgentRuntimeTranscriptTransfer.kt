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
 * 把最终结果 transcript 从 Messenger Bundle 中移出。
 *
 * 发送端把 transcript 序列化后写入临时文件，并通过只读文件描述符交给入口进程；
 * 接收端读取文件后恢复成消息列表。文件本身由发送端持有，直到入口 ACK 后再清理。
 * 旧客户端仍可从 Bundle 内联 JSON 回退读取。
 */
internal object AgentRuntimeTranscriptTransfer {
    private const val TRANSCRIPT_TRANSFER_DIRECTORY = "agent-runtime-transcript"
    private const val MAX_TRANSCRIPT_FILE_BYTES = 16 * 1024 * 1024L
    private const val STALE_FILE_AGE_MILLIS = 6 * 60 * 60 * 1_000L

    class PreparedTranscript internal constructor(
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
        transcript: List<AgentModelClient.ConversationMessage>,
    ): PreparedTranscript {
        val cacheDirectory = File(context.cacheDir, TRANSCRIPT_TRANSFER_DIRECTORY).apply {
            if (!isDirectory && !mkdirs()) {
                throw IllegalStateException("无法创建 transcript 传输缓存")
            }
        }
        cleanupStaleFiles(cacheDirectory)

        val file = File(cacheDirectory, "transcript-${UUID.randomUUID()}.json")
        val encoded = AgentConversationCodec.encodeTranscriptForIpc(transcript)
        if (encoded.length > MAX_TRANSCRIPT_FILE_BYTES) {
            throw AgentRuntimeWire.PayloadTooLargeException(encoded.length)
        }
        file.writeText(encoded, Charsets.UTF_8)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            ?: throw IllegalStateException("无法打开 transcript 文件描述符")
        return PreparedTranscript(descriptor, file)
    }

    fun readFromBundle(bundle: Bundle): List<AgentModelClient.ConversationMessage> {
        bundle.getParcelable(AgentRuntimeWire.KEY_TRANSCRIPT_FD, ParcelFileDescriptor::class.java)
            ?.use { descriptor ->
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                if (bytes.size > MAX_TRANSCRIPT_FILE_BYTES) {
                    throw AgentRuntimeWire.PayloadTooLargeException(bytes.size)
                }
                return AgentConversationCodec.decodeTranscript(String(bytes, Charsets.UTF_8))
            }
        // 兼容旧 Runtime：transcript 仍内联在 Bundle 中。
        return AgentConversationCodec.decodeTranscript(
            bundle.getString(AgentRuntimeWire.KEY_TRANSCRIPT_JSON),
        )
    }

    private fun cleanupStaleFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }
}
