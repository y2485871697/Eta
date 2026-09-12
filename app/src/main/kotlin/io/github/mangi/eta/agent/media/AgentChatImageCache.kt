package io.github.mangi.eta.agent.media

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import java.io.File
import java.util.UUID

/**
 * 非视觉模型看不到聊天附图。把原图落到应用缓存，会话里只传可读路径给 read_image。
 * 不写入用户工作区；删除会话或变成孤儿后清理，避免缓存无限涨。
 */
internal class AgentChatImageCache(context: Context) {
    private val root = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)

    fun stage(
        conversationId: String,
        bytes: ByteArray,
        displayName: String,
    ): AgentFileReference? {
        if (bytes.isEmpty() || bytes.size > MAX_AGENT_IMAGE_BYTES) return null
        val conversationDir = File(root, sanitize(conversationId)).apply { mkdirs() }
        if (!conversationDir.isDirectory) return null
        val safeName = AgentFileReferenceGateway.safeImportName(displayName)
        val destination = File(conversationDir, "${UUID.randomUUID()}-$safeName")
        destination.writeBytes(bytes)
        if (!destination.isFile) return null
        return AgentFileReference(
            displayName = safeName,
            absolutePath = destination.absolutePath,
            kind = AgentFileReferenceKind.File,
        )
    }

    fun deleteConversation(conversationId: String) {
        val directory = File(root, sanitize(conversationId))
        if (directory.exists()) directory.deleteRecursively()
        pruneEmptyRoot()
    }

    fun deleteOrphans(activeConversationIds: Set<String>) {
        if (!root.isDirectory) return
        val active = activeConversationIds.mapTo(mutableSetOf(), ::sanitize)
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name !in active) {
                child.deleteRecursively()
            }
        }
        pruneEmptyRoot()
    }

    private fun pruneEmptyRoot() {
        if (root.isDirectory && root.list().isNullOrEmpty()) root.delete()
    }

    companion object {
        const val CACHE_DIRECTORY = "eta-chat-images"

        fun decodeImageBytes(value: String): ByteArray? {
            val trimmed = value.trim()
            if (!trimmed.startsWith("data:image/", ignoreCase = true)) return null
            val marker = trimmed.indexOf("base64,", ignoreCase = true)
            if (marker < 0) return null
            return runCatching {
                Base64.decode(trimmed.substring(marker + "base64,".length), Base64.DEFAULT)
            }.getOrNull()
        }

        fun readBytes(value: String): ByteArray? {
            decodeImageBytes(value)?.let { return it }
            val path = value.trim().removePrefix("file://")
            if (!path.startsWith("/")) return null
            val file = File(path)
            if (!file.isFile || file.length() !in 1L..MAX_AGENT_IMAGE_BYTES.toLong()) return null
            return runCatching { file.readBytes() }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_AGENT_IMAGE_BYTES }
        }

        private fun sanitize(conversationId: String): String =
            AgentFileReferenceGateway.safeImportName(conversationId)
    }
}
