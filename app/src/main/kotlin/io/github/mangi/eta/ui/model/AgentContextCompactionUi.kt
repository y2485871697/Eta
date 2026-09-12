package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentModelClient

/** 把压缩结果变成时间线上的分界标记，摘要原文原样保留。 */
internal object AgentContextCompactionUi {
    fun applyMarker(
        messages: List<AgentChatMessageUi>,
        originalHistory: List<AgentModelClient.ConversationMessage>,
        compressedHistory: List<AgentModelClient.ConversationMessage>,
        extraKeptUserMessages: Int = 0,
        compressorLabel: String = "",
        baselineTokens: Int = 0,
        resumeRound: Int = 0,
        markerId: String = "compacted-${System.currentTimeMillis()}",
    ): List<AgentChatMessageUi> {
        if (compressedHistory == originalHistory) return messages
        val keptHistory = compressedHistory.filterNot(AgentContextCompactor::isCompressionSummary)
        val keepStart = (originalHistory.size - keptHistory.size).coerceIn(0, originalHistory.size)
        val compactedVisible = if (keepStart > 0) {
            originalHistory.subList(0, keepStart)
                .count(AgentContextCompactor::isVisibleConversationMessage)
        } else {
            0
        }
        val compactedCount = compactedVisible.takeIf { it > 0 } ?: keepStart.coerceAtLeast(1)
        val summary = compressedHistory
            .filter(AgentContextCompactor::isCompressionSummary)
            .joinToString("\n\n") { AgentContextCompactor.displaySummary(it.content) }
        val keptUserCount = keptHistory.count { message ->
            message.role.equals("user", ignoreCase = true) &&
                !AgentContextCompactor.isCompressionSummary(message)
        } + extraKeptUserMessages.coerceAtLeast(0)
        return insertMarker(
            messages = messages,
            marker = ContextCompactedMessageUi(
                id = markerId,
                compactedCount = compactedCount,
                summary = summary,
                compressorLabel = compressorLabel,
                baselineTokens = baselineTokens,
                resumeRound = resumeRound,
            ),
            keptUserCount = keptUserCount,
        )
    }

    internal fun insertMarker(
        messages: List<AgentChatMessageUi>,
        marker: ContextCompactedMessageUi,
        keptUserCount: Int,
    ): List<AgentChatMessageUi> {
        var insertIndex = messages.size
        var remaining = keptUserCount
        if (remaining > 0) {
            for (index in messages.indices.reversed()) {
                if (messages[index] is UserMessageUi) {
                    remaining--
                    if (remaining == 0) {
                        insertIndex = index
                        break
                    }
                }
            }
            if (remaining > 0) insertIndex = 0
        }
        val replaceIndex = when {
            insertIndex > 0 && messages[insertIndex - 1] is ContextCompactedMessageUi ->
                insertIndex - 1
            insertIndex < messages.size && messages[insertIndex] is ContextCompactedMessageUi ->
                insertIndex
            else -> null
        }
        if (replaceIndex != null) {
            return messages.toMutableList().also { it[replaceIndex] = marker }
        }
        return messages.take(insertIndex) + marker + messages.drop(insertIndex)
    }
}
