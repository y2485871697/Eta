package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentModelClient

/** 把压缩结果变成时间线上的分界标记，摘要原文原样保留。 */
internal object AgentContextCompactionUi {
    /** Pruning may commit before a manual summary fails. Until a new bill or a successful
     * summary baseline arrives, keep the latest measured occupancy instead of falling back
     * to a historical compaction marker. This is the last bill, not a pruning estimate. */
    internal fun pendingPruningUsage(livePromptTokens: Int?, messages: List<AgentChatMessageUi>,
        livePromptIsProjected: Boolean = false): Int? =
        livePromptTokens?.takeIf { !livePromptIsProjected && it > 0 } ?: latestBilledContextTokens(messages)

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
        val pruningOnly = isPruningOnly(originalHistory, compressedHistory, compressorLabel)
        val keptHistory = compressedHistory.filterNot(AgentContextCompactor::isCompressionSummary)
        val compactedCount = completedMessageCount(originalHistory, compressedHistory, compressorLabel)
        val summary = if (pruningOnly) "" else compressedHistory
            .filter(AgentContextCompactor::isCompressionSummary)
            .joinToString("\n\n") { AgentContextCompactor.displaySummary(it.content) }
        val keptUserCount = keptHistory.count { message ->
            message.role.equals("user", ignoreCase = true) &&
                !AgentContextCompactor.isCompressionSummary(message)
        } + extraKeptUserMessages.coerceAtLeast(0)
        // 圆环不能再用压缩前的窗口账单，但累计用量要留在标记上，供会话/设置统计。
        val preservedUsage = conversationTokenUsage(messages)
        val messagesWithoutOldUsage = messages.map { message ->
            if (message is ContextCompactedMessageUi) {
                message.copy(preservedUsage = ConversationTokenUsageUi())
            } else {
                message
            }
        }
        return insertMarker(
            messages = messagesWithoutOldUsage,
            marker = ContextCompactedMessageUi(
                id = markerId,
                compactedCount = compactedCount,
                summary = summary,
                compressorLabel = if (pruningOnly) "工具输出修剪（非摘要）" else compressorLabel,
                baselineTokens = baselineTokens,
                resumeRound = resumeRound,
                preservedUsage = preservedUsage,
            ),
            keptUserCount = keptUserCount,
        ).let(::clearBilledTokenUsage)
    }

    /** Shared by the timeline marker and completion Toast; maintenance is never success. */
    internal fun completedMessageCount(
        originalHistory: List<AgentModelClient.ConversationMessage>,
        compressedHistory: List<AgentModelClient.ConversationMessage>,
        compressorLabel: String = "",
    ): Int {
        if (compressedHistory.isEmpty() || compressedHistory == originalHistory ||
            isPruningOnly(originalHistory, compressedHistory, compressorLabel) ||
            compressedHistory.none(AgentContextCompactor::isCompressionSummary)) return 0
        val keptCount = compressedHistory.count { !AgentContextCompactor.isCompressionSummary(it) }
        val keepStart = (originalHistory.size - keptCount).coerceIn(0, originalHistory.size)
        val visible = originalHistory.take(keepStart)
            .count(AgentContextCompactor::isVisibleConversationMessage)
        return visible.takeIf { it > 0 } ?: keepStart.coerceAtLeast(1)
    }

    internal fun isPruningOnly(
        originalHistory: List<AgentModelClient.ConversationMessage>,
        compressedHistory: List<AgentModelClient.ConversationMessage>,
        compressorLabel: String = "",
    ): Boolean {
        // Runtime events may include tool records not yet reflected in UI history.
        if (compressorLabel == "工具输出预算修剪（原文可回读）") return true
        if (originalHistory == compressedHistory) return false
        return originalHistory.size == compressedHistory.size &&
            originalHistory.zip(compressedHistory).all { (before, after) ->
                before == after || (before.role == "tool" && after.role == "tool" &&
                    before.copy(content = after.content) == after &&
                    after.content.contains("[Eta tool output pruned;"))
            }
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
