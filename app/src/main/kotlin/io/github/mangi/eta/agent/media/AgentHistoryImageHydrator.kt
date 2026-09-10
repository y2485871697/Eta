package io.github.mangi.eta.agent.media

import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 把持久化的 image_file 在发往模型前还原成视觉输入，或变成非视觉模型可 read_image 的路径。 */
internal object AgentHistoryImageHydrator {
    const val TYPE_IMAGE_FILE = "image_file"

    fun hydrateAll(
        history: List<AgentModelClient.ConversationMessage>,
        supportsVision: Boolean,
    ): List<AgentModelClient.ConversationMessage> =
        history.map { message -> hydrate(message, supportsVision) }

    fun hydrate(
        message: AgentModelClient.ConversationMessage,
        supportsVision: Boolean,
    ): AgentModelClient.ConversationMessage {
        if (message.contentJson.isBlank()) return message
        val content = runCatching { JSONTokener(message.contentJson).nextValue() }.getOrNull()
            as? JSONArray ?: return message
        var changed = false
        val next = JSONArray()
        val restoredPaths = mutableListOf<String>()
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            if (item.optString("type") != TYPE_IMAGE_FILE) {
                next.put(item)
                continue
            }
            changed = true
            val path = item.optString("path")
            val mime = item.optString("mime").ifBlank { "image/jpeg" }
            restoredPaths += path
            if (!supportsVision) continue
            val file = File(path)
            if (!file.isFile || file.length() !in 1..MAX_AGENT_IMAGE_BYTES.toLong()) continue
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) continue
            val image = runCatching {
                AgentImageCodec.fromBytes(bytes, source = "history_attach", mimeHint = mime)
            }.getOrNull() ?: continue
            next.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", image.reference)),
            )
        }
        if (!changed) return message
        if (!supportsVision && restoredPaths.isNotEmpty()) {
            val listing = restoredPaths.joinToString("\n") { path -> "[用户图片] $path" }
            if (next.length() == 0) {
                next.put(JSONObject().put("type", "text").put("text", listing))
            } else {
                val first = next.optJSONObject(0)
                if (first != null && first.optString("type") == "text") {
                    val text = first.optString("text")
                    first.put("text", if (text.isBlank()) listing else "$text\n\n$listing")
                    next.put(0, first)
                } else {
                    val prefixed = JSONArray().put(JSONObject().put("type", "text").put("text", listing))
                    for (index in 0 until next.length()) prefixed.put(next.get(index))
                    return message.copy(
                        content = listing,
                        contentJson = prefixed.toString(),
                    )
                }
            }
        }
        val text = (0 until next.length())
            .mapNotNull { index ->
                next.optJSONObject(index)
                    ?.takeIf { it.optString("type") == "text" }
                    ?.optString("text")
            }
            .joinToString("\n")
        return message.copy(
            content = text.ifBlank { message.content },
            contentJson = next.toString(),
        )
    }
}
