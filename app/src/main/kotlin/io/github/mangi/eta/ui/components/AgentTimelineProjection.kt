package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress

// Bound each eagerly rendered work-process Column without dropping message data.
private const val WORK_PROCESS_UI_BATCH_LIMIT = 32

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

    fun flushWorkProcess() {
        if (workMessages.isEmpty()) return
        add(
            AgentTimelineEntry.WorkProcess(
                key = "work-${workMessages.first().id}",
                messages = workMessages.toList(),
            )
        )
        workMessages.clear()
    }

    this@toTimelineEntries.forEach { message ->
        if (message is UserMessageUi && message.isResumeAfterCompress()) {
            return@forEach
        }
        if (message.isWorkProcessMessage()) {
            workMessages += message
            if (workMessages.size >= WORK_PROCESS_UI_BATCH_LIMIT) flushWorkProcess()
        } else {
            flushWorkProcess()
            add(AgentTimelineEntry.Message(message))
        }
    }
    flushWorkProcess()
}

/** Use projected list indices, not raw message indices (work steps are grouped). */
internal fun List<AgentTimelineEntry>.userMessageIndices(): List<Int> = mapIndexedNotNull { index, entry ->
    val user = (entry as? AgentTimelineEntry.Message)?.message as? UserMessageUi
    index.takeIf { user != null && !user.isResumeAfterCompress() }
}

private fun AgentChatMessageUi.isWorkProcessMessage(): Boolean =
    this is ThinkingMessageUi || this is ToolActivityMessageUi || this is ToolSummaryMessageUi

