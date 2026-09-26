package io.github.mangi.eta.ui.model

import org.junit.Assert.*
import org.junit.Test

class CloudContextUsageTest {
    private val scope = ContextUsageScope("conversation", "provider", "model", 0)
    @Test fun partialUsageStaysWithinRequestAndAllowsDownwardCorrection() {
        var state = CloudContextUsageState(scope).receive(scope, TokenUsageUi(inputTokens = 80000))
        state = state.receive(scope, TokenUsageUi(outputTokens = 20))
        assertEquals(80000, state.inputTokens)
        state = state.receive(scope, TokenUsageUi(inputTokens = 40000))
        assertEquals(40000, state.inputTokens)
        state = state.receive(scope, TokenUsageUi(inputTokens = 90000), projected = true)
        assertEquals(40000, state.inputTokens)
        val fresh = state.invalidate()
        assertNull(fresh.inputTokens)
        assertNull(fresh.receive(fresh.scope, TokenUsageUi(outputTokens = 30)).inputTokens)
        assertNull(fresh.receive(scope, TokenUsageUi(inputTokens = 80000)).inputTokens)
        assertEquals(1000, fresh.receive(fresh.scope, TokenUsageUi(inputTokens = 1000)).inputTokens)
    }
    @Test fun modelAndHistoryChangesRejectOldReceiptsAndUsage() {
        val original = CloudContextUsageState(scope).receive(scope, TokenUsageUi(inputTokens = 90000))
        val changed = original.invalidate(modelId = "new-model")
        assertNull(changed.restore(original.snapshot()).inputTokens)
        assertNull(changed.receive(scope, TokenUsageUi(inputTokens = 90000)).inputTokens)
        assertNull(original.invalidate().restore(original.snapshot()).inputTokens)
        assertEquals(90000, original.restore(original.snapshot()).inputTokens)
    }
    @Test fun oldRunCompactionRoundDoesNotSuppressNewRunBill() {
        val messages = listOf<AgentChatMessageUi>(
            ContextCompactedMessageUi("old-marker", 8, "summary", resumeRound = 8),
            AgentMessageUi("assistant-run-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-1-0", "new run", usage = TokenUsageUi(inputTokens = 4000)),
        )
        assertEquals(4000, latestBilledContextTokens(messages))
    }
}
