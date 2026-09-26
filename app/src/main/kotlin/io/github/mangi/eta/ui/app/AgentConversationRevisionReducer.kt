package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isSteerSupplement

/** 以用户轮次为边界同步裁剪展示消息与模型上下文。 */
internal object AgentConversationRevisionReducer {
    data class Boundary(
        val userMessage: UserMessageUi,
        val userMessageIndex: Int,
        val historyPrefix: List<AgentModelClient.ConversationMessage>,
        val laterTurnCount: Int,
        val contextWasCompacted: Boolean,
    )

    data class BranchPrefix(
        val messages: List<AgentChatMessageUi>,
        val history: List<AgentModelClient.ConversationMessage>,
    )

    fun boundary(state: AgentChatUiState, targetMessageId: String): Boundary? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index] is UserMessageUi
        } ?: return null
        val userMessage = state.messages[userMessageIndex] as UserMessageUi
        val historyIndex = historyUserIndex(state, userMessageIndex)
        // Missing supplements must not be mistaken for the preceding original question.
        if (historyIndex == null && userMessage.isSteerSupplement()) return null
        val laterTurnCount = state.messages.drop(userMessageIndex + 1).count {
            it is UserMessageUi && !it.isSteerSupplement()
        }
        val compacted = historyIndex == null && wasRemovedByCompaction(state, userMessageIndex)
        // A missing match is not proof of compaction. Fail closed instead of erasing history.
        if (historyIndex == null && !compacted) return null

        return Boundary(
            userMessage = userMessage,
            userMessageIndex = userMessageIndex,
            historyPrefix = historyIndex?.let(state.history::take).orEmpty(),
            laterTurnCount = laterTurnCount,
            contextWasCompacted = compacted,
        )
    }

    fun deleteFromTurn(state: AgentChatUiState, targetMessageId: String): AgentChatUiState? {
        val boundary = boundary(state, targetMessageId) ?: return null
        return state.copy(
            messages = state.messages.take(boundary.userMessageIndex),
            history = boundary.historyPrefix,
            messageEdit = null,
            livePromptTokens = null,
        )
    }

    /**
     * 从目标消息分出一条独立会话：保留该消息及之前的展示内容，
     * 模型上下文截到同一轮结束（助手消息含本轮回复；用户消息只含该条提问）。
     */
    fun branchPrefix(state: AgentChatUiState, targetMessageId: String): BranchPrefix? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index] is UserMessageUi
        } ?: return null
        val historyUserIndex = historyUserIndex(state, userMessageIndex)
        if (historyUserIndex == null &&
            ((state.messages[userMessageIndex] as UserMessageUi).isSteerSupplement() ||
                !wasRemovedByCompaction(state, userMessageIndex))) return null
        val messages = state.messages.take(targetIndex + 1)
        val history = if (historyUserIndex == null) {
            reconstructHistory(messages)
        } else if (state.messages[targetIndex] is UserMessageUi) {
            state.history.take(historyUserIndex + 1)
        } else {
            // A branch must not include later supplements that are not visible in its prefix.
            val nextUser = (historyUserIndex + 1 until state.history.size).firstOrNull {
                state.history[it].role == "user"
            }
            if (nextUser != null) state.history.take(nextUser) else state.history
        }
        return BranchPrefix(messages = messages, history = history)
    }

    /** Stable owner + exact user payload; list length is never evidence of message identity. */
    private fun historyUserIndex(state: AgentChatUiState, uiIndex: Int): Int? {
        val user = state.messages[uiIndex] as UserMessageUi
        val runId = user.id.removePrefix("user-").substringBefore("-supplement-")
        val expected = user.content.trim()
        val steering = io.github.mangi.eta.agent.model.AgentContextCompactor.steeringUserContent(user.content).trim()
        fun matches(message: AgentModelClient.ConversationMessage): Boolean {
            if (message.role != "user" || AgentContextCompactor.isCompressionSummary(message)) return false
            val text = historyText(message)
            if (text == expected || text == steering) return true
            // Attachment envelopes differ between UI/persisted/vision requests. Only normalize
            // inside the same proven owner turn, never across repeated questions or supplements.
            if (message.turnId != runId || user.isSteerSupplement()) return false
            val parsed = AgentFileReferencePromptCodec.parse(text)
            val ui = AgentFileReferencePromptCodec.parse(expected)
            return ui.request.isNotBlank() && parsed.request.trim() == ui.request.trim() &&
                parsed.conversations == ui.conversations
        }
        val candidates = state.history.indices.filter { matches(state.history[it]) }
        val inTurn = candidates.filter { state.history[it].turnId == runId }
        val scoped = inTurn.ifEmpty { candidates }
        // Disambiguate only identical payloads, not every user bubble including supplements.
        val laterDuplicates = state.messages.drop(uiIndex + 1).filterIsInstance<UserMessageUi>().count {
            it.content.trim() == expected &&
                (inTurn.isEmpty() || it.id.removePrefix("user-").substringBefore("-supplement-") == runId)
        }
        return scoped.getOrNull(scoped.size - 1 - laterDuplicates)
    }

    private fun historyText(message: AgentModelClient.ConversationMessage): String =
        message.content.ifBlank {
            runCatching {
                val parts = org.json.JSONArray(message.contentJson)
                (0 until parts.length()).mapNotNull { index ->
                    parts.optJSONObject(index)?.takeIf { it.optString("type") == "text" }?.optString("text")
                }.filter { it.isNotBlank() }.joinToString("\n")
            }.getOrDefault("")
        }.trim()

    /** Evidence must place this specific missing message before a real summary boundary.
     * A tool-pruning marker or a summary elsewhere in the conversation is insufficient. */
    private fun wasRemovedByCompaction(state: AgentChatUiState, uiIndex: Int): Boolean {
        val user = state.messages[uiIndex] as UserMessageUi
        val owner = user.id.removePrefix("user-").substringBefore("-supplement-")
        if (state.history.any { it.role == "user" && it.turnId == owner }) return false
        val markers = state.messages.withIndex().filter { (_, message) ->
            message is ContextCompactedMessageUi && message.compactedCount > 0 && message.summary.isNotBlank()
        }
        if (markers.isNotEmpty()) return markers.any { it.index > uiIndex }
        // Legacy conversations may lack UI markers. Require a real summary plus a retained,
        // exactly matched later user turn; do not infer from a smaller history list alone.
        val summaryIndex = state.history.indexOfLast(AgentContextCompactor::isCompressionSummary)
        if (summaryIndex < 0) return false
        return (uiIndex + 1 until state.messages.size).any { later ->
            state.messages[later] is UserMessageUi &&
                historyUserIndex(state, later)?.let { it > summaryIndex } == true
        }
    }

    fun outboundHistory(state: AgentChatUiState): List<AgentModelClient.ConversationMessage> {
        val targetId = state.messageEdit?.targetMessageId ?: return state.history
        return boundary(state, targetId)?.historyPrefix ?: state.history
    }

    fun visibleMessagesForEdit(
        messages: List<AgentChatMessageUi>,
        targetMessageId: String?,
    ): List<AgentChatMessageUi> {
        if (targetMessageId == null) return messages
        val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
        return if (targetIndex < 0) messages else messages.take(targetIndex + 1)
    }

    private fun reconstructHistory(
        messages: List<AgentChatMessageUi>,
    ): List<AgentModelClient.ConversationMessage> = messages.mapNotNull { message ->
        when (message) {
            is UserMessageUi -> AgentModelClient.ConversationMessage(
                role = "user",
                content = message.content,
            )
            is AgentMessageUi -> message.content.takeIf { it.isNotBlank() }?.let { content ->
                AgentModelClient.ConversationMessage(role = "assistant", content = content)
            }
            else -> null
        }
    }

    /**
     * 暂停/结束任务后，屏幕上已写出的助手正文必须进模型历史。
     * 否则下一轮请求看不到刚才的完整回答。
     */
    fun commitVisibleAssistantIntoHistory(
        history: List<AgentModelClient.ConversationMessage>,
        messages: List<AgentChatMessageUi>,
    ): List<AgentModelClient.ConversationMessage> {
        val lastUserIndex = messages.indexOfLast { message ->
            message is UserMessageUi
        }
        val partial = messages
            .drop((lastUserIndex + 1).coerceAtLeast(0))
            .filterIsInstance<AgentMessageUi>()
            .lastOrNull { it.content.isNotBlank() }
            ?: return history
        return historyWithTrailingPartial(history, partial)
    }

    fun historyWithTrailingPartial(
        history: List<AgentModelClient.ConversationMessage>,
        partial: AgentMessageUi,
    ): List<AgentModelClient.ConversationMessage> {
        val migrated = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(history)
        val partialMessage = AgentModelClient.ConversationMessage(
            role = "assistant",
            content = partial.content,
            turnId = migrated.lastOrNull { it.turnId.isNotBlank() }?.turnId.orEmpty(),
        )
        val last = migrated.lastOrNull()
        if (last?.role == "assistant" && last.content == partial.content) return migrated
        val extendsTrailingText = last?.role == "assistant" &&
            last.toolCallsJson.isBlank() && last.content.isNotBlank() && partial.content.startsWith(last.content)
        return if (extendsTrailingText) migrated.dropLast(1) + partialMessage else migrated + partialMessage
    }

}

internal fun AgentChatMessageUi.withId(id: String): AgentChatMessageUi = when (this) {
    is UserMessageUi -> copy(id = id)
    is AgentMessageUi -> copy(id = id)
    is SystemNoticeMessageUi -> copy(id = id)
    is ThinkingMessageUi -> copy(id = id)
    is RunTraceMessageUi -> copy(id = id)
    is ToolSummaryMessageUi -> copy(id = id)
    is ContextCompactedMessageUi -> copy(id = id)
    is ToolActivityMessageUi -> copy(id = id)
    is SuggestionChipsMessageUi -> copy(id = id)
}

internal fun AgentChatMessageUi.rewritePaths(rewrite: (String) -> String): AgentChatMessageUi = when (this) {
    is UserMessageUi -> copy(
        content = rewrite(content),
        imageSources = imageSources.map(rewrite),
    )
    is AgentMessageUi -> copy(content = rewrite(content))
    is ThinkingMessageUi -> copy(content = rewrite(content))
    is ToolActivityMessageUi -> copy(
        argumentsSummary = rewrite(argumentsSummary),
        command = command?.let(rewrite),
        resultSummary = resultSummary?.let(rewrite),
    )
    else -> this
}

internal fun AgentModelClient.ConversationMessage.rewritePaths(
    rewrite: (String) -> String,
): AgentModelClient.ConversationMessage = copy(
    content = rewrite(content),
    contentJson = rewrite(contentJson),
)

