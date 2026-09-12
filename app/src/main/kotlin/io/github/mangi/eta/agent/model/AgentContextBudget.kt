package io.github.mangi.eta.agent.model

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object AgentContextBudget {
    private const val RESERVED_TOKENS = 8_000
    private const val TOKENS_PER_MESSAGE = 3
    private const val IMAGE_GRID = 32
    private const val IMAGE_MAX_EDGE = 2048
    private const val IMAGE_MIN_TOKENS = 85
    // 对齐 Operit：中文约 1.5 token/字，拉丁约 4 字符/token。
    // 原先统一 codepoint/3，中文会少算 4～6 倍，界面用量就会远低于接口账单。
    private const val CJK_TOKENS_PER_CHAR = 1.5
    private const val LATIN_TOKENS_PER_CHAR = 0.25
    private val DATA_URL_REGEX =
        Regex("""data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=\s]+""", RegexOption.IGNORE_CASE)

    fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        var index = 0
        while (index < text.length) {
            val extra = if (text[index].isHighSurrogate() && index + 1 < text.length) 2 else 1
            val codePoint = if (extra == 2) {
                Character.toCodePoint(text[index], text[index + 1])
            } else {
                text[index].code
            }
            if (isCjkCodePoint(codePoint)) cjk++ else other++
            index += extra
        }
        return max(1, (cjk * CJK_TOKENS_PER_CHAR + other * LATIN_TOKENS_PER_CHAR).roundToInt())
    }

    private fun isCjkCodePoint(codePoint: Int): Boolean = when (codePoint) {
        in 0x3400..0x4DBF -> true
        in 0x4E00..0x9FFF -> true
        in 0xF900..0xFAFF -> true
        in 0x3040..0x30FF -> true
        in 0xAC00..0xD7AF -> true
        in 0x3000..0x303F -> true
        in 0xFF00..0xFFEF -> true
        in 0x20000..0x2A6DF -> true
        else -> false
    }

    fun countStoredImages(count: Int): Int = count.coerceAtLeast(0) * IMAGE_MIN_TOKENS

    fun countImageTokens(image: AgentModelClient.ModelImage): Int {
        val w = image.width
        val h = image.height
        return if (w != null && h != null && w > 0 && h > 0) {
            countImageTokens(w, h)
        } else {
            max(IMAGE_MIN_TOKENS, image.bytes / 4_096)
        }
    }

    fun countImageTokens(width: Int, height: Int): Int {
        val longEdge = max(width, height)
        val scale = if (longEdge > IMAGE_MAX_EDGE) IMAGE_MAX_EDGE.toFloat() / longEdge else 1f
        val scaledW = width * scale
        val scaledH = height * scale
        val tokens = (ceil(scaledW / IMAGE_GRID) * ceil(scaledH / IMAGE_GRID)).toInt()
        return max(IMAGE_MIN_TOKENS, tokens)
    }

    fun estimate(messages: org.json.JSONArray): Int {
        var tokens = 0
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            tokens += countMessage(AgentConversationCodec.fromJsonObject(message))
        }
        return tokens
    }

    fun countMessage(message: AgentModelClient.ConversationMessage): Int {
        var tokens = TOKENS_PER_MESSAGE
        // contentJson 才是发出去的 content；有它时不要再加一份纯文本。
        tokens += countPayload(message)
        if (message.reasoningContent.isNotBlank()) {
            tokens += countTokens(message.reasoningContent)
        }
        if (message.toolCallId.isNotBlank()) {
            tokens += countTokens(message.toolCallId)
        }
        if (message.toolCallsJson.isNotBlank()) {
            tokens += countTokens(message.toolCallsJson)
        }
        return tokens
    }

    private fun countPayload(message: AgentModelClient.ConversationMessage): Int {
        if (message.contentJson.isNotBlank()) {
            return countStructuredContent(message.contentJson)
        }
        return countTokens(stripInlineDataUrls(message.content))
    }

    /**
     * 多模态 content 里的图片按视觉 token 计，不能把 data URL / 路径当正文去估。
     * 工具回图进下一轮 prompt 时，base64 按拉丁 4 字符/token 会一下子多出几万。
     */
    private fun countStructuredContent(raw: String): Int {
        val parsed = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        when (parsed) {
            is JSONArray -> {
                var tokens = 0
                for (index in 0 until parsed.length()) {
                    val item = parsed.optJSONObject(index)
                    tokens += if (item != null) {
                        countContentPart(item)
                    } else {
                        countTokens(stripInlineDataUrls(parsed.optString(index)))
                    }
                }
                return tokens
            }
            is JSONObject -> return countContentPart(parsed)
            is String -> return countTokens(stripInlineDataUrls(parsed))
            else -> return countTokens(stripInlineDataUrls(raw))
        }
    }

    private fun countContentPart(part: JSONObject): Int {
        val type = part.optString("type")
        return when (type) {
            "text" -> countTokens(part.optString("text"))
            "image_url", "image_file", "image", "input_image" -> countContentImage(part)
            else -> {
                val text = part.optString("text")
                if (text.isNotBlank()) countTokens(text) else 0
            }
        }
    }

    private fun countContentImage(part: JSONObject): Int {
        val url = part.optJSONObject("image_url")?.optString("url").orEmpty()
            .ifBlank { part.optString("url") }
            .ifBlank { part.optString("path") }
        val width = part.optInt("width").takeIf { it > 0 }
            ?: part.optJSONObject("image_url")?.optInt("width")?.takeIf { it > 0 }
        val height = part.optInt("height").takeIf { it > 0 }
            ?: part.optJSONObject("image_url")?.optInt("height")?.takeIf { it > 0 }
        if (width != null && height != null) {
            return countImageTokens(width, height)
        }
        val bytes = dataUrlDecodedBytes(url)
        return if (bytes != null) {
            countImageTokens(
                AgentModelClient.ModelImage(
                    reference = url,
                    mimeType = "image/*",
                    bytes = bytes,
                    source = "content_image",
                ),
            )
        } else {
            IMAGE_MIN_TOKENS
        }
    }

    private fun dataUrlDecodedBytes(url: String): Int? {
        val marker = "base64,"
        val index = url.indexOf(marker, ignoreCase = true)
        if (index < 0) return null
        val encoded = url.substring(index + marker.length).filterNot { it.isWhitespace() }
        if (encoded.isEmpty()) return null
        return (encoded.length * 3) / 4
    }

    private fun stripInlineDataUrls(text: String): String {
        if (!text.contains("data:image", ignoreCase = true)) return text
        return DATA_URL_REGEX.replace(text, "[image]")
    }

    fun countCurrentTurn(prompt: String, images: List<AgentModelClient.ModelImage>): Int {
        var tokens = TOKENS_PER_MESSAGE
        tokens += countTokens(prompt)
        images.forEach { tokens += countImageTokens(it) }
        return tokens
    }

    fun historyBudget(contextWindow: Int, prompt: String, images: List<AgentModelClient.ModelImage>): Int {
        val currentTurn = countCurrentTurn(prompt, images)
        return (contextWindow - RESERVED_TOKENS - currentTurn).coerceAtLeast(0)
    }

    fun trimHistory(
        history: List<AgentModelClient.ConversationMessage>,
        budgetTokens: Int,
    ): List<AgentModelClient.ConversationMessage> {
        if (history.isEmpty()) return history
        if (budgetTokens <= 0) return emptyList()
        var remaining = budgetTokens
        val result = ArrayDeque<AgentModelClient.ConversationMessage>()
        for (index in history.size - 1 downTo 0) {
            val message = history[index]
            val tokens = countMessage(message)
            if (tokens > remaining && result.isNotEmpty()) {
                break
            }
            remaining -= tokens
            result.addFirst(message)
        }
        return result.toList()
    }
}
