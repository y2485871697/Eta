package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject

internal fun UserMessageUi.fullImageSourceAt(index: Int): String {
    val source = imageSources.getOrNull(index)?.trim().orEmpty()
    if (source.isNotEmpty()) return source
    return images.getOrNull(index).orEmpty()
}

internal fun encodeUserMessageImages(
    previews: List<String>,
    sources: List<String> = emptyList(),
): String {
    val array = JSONArray()
    previews.forEachIndexed { index, preview ->
        if (preview.isBlank()) return@forEachIndexed
        val source = sources.getOrNull(index)?.trim().orEmpty()
        if (source.isEmpty() || source == preview) {
            array.put(preview)
        } else {
            array.put(
                JSONObject()
                    .put("preview", preview)
                    .put("source", source),
            )
        }
    }
    return array.toString()
}

internal fun decodeUserMessageImages(raw: String): Pair<List<String>, List<String>> {
    if (raw.isBlank()) return emptyList<String>() to emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList<String>() to emptyList()
    val previews = ArrayList<String>(array.length())
    val sources = ArrayList<String>(array.length())
    var hasDistinctSource = false
    for (index in 0 until array.length()) {
        val obj = array.optJSONObject(index)
        if (obj != null) {
            val preview = obj.optString("preview").ifBlank { obj.optString("dataUrl") }
            if (preview.isBlank()) continue
            val source = obj.optString("source").ifBlank { preview }
            previews += preview
            sources += source
            if (source != preview) hasDistinctSource = true
        } else {
            val preview = array.optString(index).trim()
            if (preview.isBlank()) continue
            previews += preview
            sources += preview
        }
    }
    return previews to if (hasDistinctSource) sources else emptyList()
}

internal fun attachUserImageSources(
    messages: List<AgentChatMessageUi>,
    history: List<AgentModelClient.ConversationMessage>,
): List<AgentChatMessageUi> {
    val historySources = history.map(AgentConversationCodec::persistedImageSources)
        .filter { it.isNotEmpty() }
    if (historySources.isEmpty()) return messages
    var next = 0
    return messages.map { message ->
        if (message !is UserMessageUi || message.images.isEmpty()) {
            message
        } else {
            val sources = historySources.getOrNull(next)
            next += 1
            when {
                message.imageSources.isNotEmpty() -> message
                sources != null && sources.size == message.images.size ->
                    message.copy(imageSources = sources)
                else -> message
            }
        }
    }
}
