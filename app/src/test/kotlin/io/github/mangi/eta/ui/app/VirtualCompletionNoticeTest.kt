package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.ui.model.*
import org.junit.Assert.*
import org.junit.Test

class VirtualCompletionNoticeTest {
    private fun result(ok: Boolean = true, verified: Boolean = true, content: String = "answer") =
        AgentRuntimeWire.RunResult("run-vd", ok, content,
            error = if (ok) null else "已停止", virtualDeliveryCompleted = verified)

    @Test fun confirmedResultAppendsOnceAndKeepsAnswer() {
        val answer = AgentMessageUi("assistant-run-vd-1", "answer")
        val first = VirtualCompletionNotice.append(listOf(answer), "run-vd", result())
        assertSame(answer, first.first())
        assertEquals(SystemNoticeCode.Completed, (first.last() as SystemNoticeMessageUi).code)
        assertEquals(2, first.size)
        assertSame(first, VirtualCompletionNotice.append(first, "run-vd", result()))
    }

    @Test fun failedOrUnverifiedResultsDoNotProduceCompletion() {
        val messages = listOf<AgentChatMessageUi>(AgentMessageUi("assistant-run-vd-1", "partial"))
        for (value in listOf(result(ok = false), result(verified = false))) {
            assertSame(messages, VirtualCompletionNotice.append(messages, "run-vd", value))
        }
        assertFalse(VirtualCompletionNotice.confirmed(result().copy(error = "uncertain")))
    }

    @Test fun replayKeepsSingleNoticeAndDoesNotPolluteHistory() {
        val state = AgentChatUiState(
            messages = listOf(UserMessageUi("user-run-vd", "task")),
            input = "", isStreaming = true, thinkingEnabled = false,
        )
        val first = AgentPendingResultRecovery.apply(state, "run-vd", result(), supplements = emptyList())
        val second = AgentPendingResultRecovery.apply(first.state, "run-vd", result(), supplements = emptyList())
        assertTrue(second.alreadyApplied)
        assertEquals(first.state, second.state)
        assertEquals(1, first.state.messages.filterIsInstance<SystemNoticeMessageUi>()
            .count { it.code == SystemNoticeCode.Completed })
        assertEquals("answer", first.state.messages.filterIsInstance<AgentMessageUi>().single().content)
        assertFalse(first.state.history.any { it.content == "已完成" })
    }

    @Test fun confirmedEmptyResultReplacesOnlyEmptyPlaceholder() {
        val placeholder = SystemNoticeMessageUi("assistant-run-vd-1", SystemNoticeCode.EmptyResult)
        val other = AgentMessageUi("assistant-other-1", "keep")
        val updated = VirtualCompletionNotice.append(listOf(other, placeholder), "run-vd", result(content = ""))
        assertEquals(other, updated.first())
        assertEquals(2, updated.size)
        assertEquals(SystemNoticeCode.Completed, (updated.last() as SystemNoticeMessageUi).code)
    }

    @Test
    fun confirmedEmptyRecoveryPreservesExistingAnswerAndReplayIsStable() {
        for (streaming in listOf(false, true)) {
            val answer = AgentMessageUi("assistant-run-vd-1", "already received answer", isStreaming = streaming)
            val state = AgentChatUiState(
                messages = listOf(answer), input = "", isStreaming = streaming, thinkingEnabled = false,
            )
            val first = AgentPendingResultRecovery.apply(state, "run-vd", result(content = ""), supplements = emptyList())
            assertEquals(answer.copy(isStreaming = false), first.state.messages.filterIsInstance<AgentMessageUi>().single())
            assertEquals(listOf(SystemNoticeCode.Completed),
                first.state.messages.filterIsInstance<SystemNoticeMessageUi>().map { it.code })
            val replay = AgentPendingResultRecovery.apply(first.state, "run-vd", result(content = ""), supplements = emptyList())
            assertTrue(replay.alreadyApplied)
            assertEquals(first.state, replay.state)
        }
    }

    @Test
    fun confirmedCompletionRemovesOnlyThisRunsStaleEmptyNotice() {
        val answer = AgentMessageUi("assistant-run-vd-1", "keep answer")
        val stale = SystemNoticeMessageUi("interrupted-run-vd", SystemNoticeCode.EmptyResult)
        val other = SystemNoticeMessageUi("interrupted-other", SystemNoticeCode.EmptyResult)
        val updated = VirtualCompletionNotice.append(listOf(answer, stale, other), "run-vd", result(content = ""))
        assertTrue(answer in updated)
        assertTrue(other in updated)
        assertFalse(stale in updated)
        assertEquals(1, updated.filterIsInstance<SystemNoticeMessageUi>().count { it.code == SystemNoticeCode.Completed })
    }
}
