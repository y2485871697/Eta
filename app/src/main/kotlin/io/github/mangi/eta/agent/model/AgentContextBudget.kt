package io.github.mangi.eta.agent.model

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

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
        tokens += countTokens(
            if (message.contentJson.isNotBlank()) message.contentJson else message.content,
        )
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
