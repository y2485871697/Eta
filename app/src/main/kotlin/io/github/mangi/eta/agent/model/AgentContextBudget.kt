package io.github.mangi.eta.agent.model

import kotlin.math.ceil
import kotlin.math.max

internal object AgentContextBudget {
    private const val RESERVED_TOKENS = 8_000
    private const val TOKENS_PER_MESSAGE = 3
    private const val IMAGE_GRID = 32
    private const val IMAGE_MAX_EDGE = 2048
    private const val IMAGE_MIN_TOKENS = 85

    fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cp = 0
        var i = 0
        while (i < text.length) {
            cp++
            i += if (text[i].isHighSurrogate() && i + 1 < text.length) 2 else 1
        }
        return max(1, cp / 3)
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

    fun countMessage(message: AgentModelClient.ConversationMessage): Int {
        var tokens = TOKENS_PER_MESSAGE
        tokens += countTokens(message.content)
        if (message.contentJson.isNotBlank()) {
            tokens += countTokens(message.contentJson)
        }
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
