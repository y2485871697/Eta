package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi

/** UI-only status; it never becomes assistant content or model history. */
internal object VirtualCompletionNotice {
    fun confirmed(result: AgentRuntimeWire.RunResult): Boolean =
        result.ok && result.error.isNullOrBlank() && result.virtualDeliveryCompleted

    fun messageId(runId: String): String = "virtual-completed-$runId"

    fun append(
        messages: List<AgentChatMessageUi>,
        runId: String,
        result: AgentRuntimeWire.RunResult,
    ): List<AgentChatMessageUi> {
        if (runId.isBlank() || !confirmed(result)) return messages
        val id = messageId(runId)
        if (messages.any { it.id == id }) return messages
        val cleaned = messages.filterNot { message ->
            val sameRunAssistant = message.id == "assistant-$runId" ||
                message.id.startsWith("assistant-$runId-")
            (sameRunAssistant && message is AgentMessageUi &&
                message.content.isBlank() && message.usage == null) ||
                ((sameRunAssistant || message.id == "interrupted-$runId") &&
                    message is SystemNoticeMessageUi && message.code == SystemNoticeCode.EmptyResult)
        }
        return cleaned + SystemNoticeMessageUi(id, SystemNoticeCode.Completed)
    }
}
