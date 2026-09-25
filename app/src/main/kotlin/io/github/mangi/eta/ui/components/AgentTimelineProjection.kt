package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress

internal sealed interface AgentTimelineEntry {
    val key: String

    data class Message(
        val message: AgentChatMessageUi,
    ) : AgentTimelineEntry {
        override val key: String = message.id
    }

    data class WorkProcess(
        override val key: String,
        val messages: List<AgentChatMessageUi>,
    ) : AgentTimelineEntry
}

internal fun List<AgentChatMessageUi>.toTimelineEntries(): List<AgentTimelineEntry> = buildList {
    val workMessages = mutableListOf<AgentChatMessageUi>()
    var workRunKey: String? = null
    var currentIndex = -1

    fun flushWorkProcess() {
        if (workMessages.isEmpty()) return
        add(
            AgentTimelineEntry.WorkProcess(
                key = "work-${workMessages.first().id}",
                messages = workMessages.toList(),
            )
        )
        workMessages.clear()
        workRunKey = null
    }

    fun appendWork(message: AgentChatMessageUi, runKey: String?) {
        if (workMessages.isNotEmpty() && runKey != null && workRunKey != null && runKey != workRunKey) {
            flushWorkProcess()
        }
        if (workMessages.isEmpty()) workRunKey = runKey
        workMessages += message
    }

    fun hasLaterWorkFor(runKey: String): Boolean =
        (currentIndex + 1 until this@toTimelineEntries.size).any {
            this@toTimelineEntries[it].workRunKey() == runKey
        }

    this@toTimelineEntries.forEachIndexed { index, message ->
        currentIndex = index
        if (message is UserMessageUi && message.isResumeAfterCompress()) {
            return@forEachIndexed
        }
        val runKey = message.workRunKey()
        if (runKey != null || message.isWorkProcessMessage()) {
            appendWork(message, runKey)
            return@forEachIndexed
        }

        // A model text block between two tool calls of the same run is progress
        // narration, not a new chat turn. Keep it in the same collapsible work card.
        // The final answer has no later work block and therefore stays outside.
        if (message is AgentMessageUi && workRunKey != null && hasLaterWorkFor(workRunKey!!)) {
            workMessages += message
            return@forEachIndexed
        }

        flushWorkProcess()
        add(AgentTimelineEntry.Message(message))
    }
    flushWorkProcess()
}

/** Use projected list indices, not raw message indices (work steps are grouped). */
internal fun List<AgentTimelineEntry>.userMessageIndices(): List<Int> = mapIndexedNotNull { index, entry ->
    val user = (entry as? AgentTimelineEntry.Message)?.message as? UserMessageUi
    index.takeIf { user != null && !user.isResumeAfterCompress() }
}

private fun AgentChatMessageUi.workRunKey(): String? {
    val marker = when (this) {
        is ThinkingMessageUi -> "-thinking-"
        is ToolActivityMessageUi -> "-tool-"
        else -> return null
    }
    val index = id.indexOf(marker)
    return id.takeIf { index > 0 }?.substring(0, index)
}

private fun AgentChatMessageUi.isWorkProcessMessage(): Boolean =
    this is ThinkingMessageUi || this is ToolActivityMessageUi || this is ToolSummaryMessageUi

