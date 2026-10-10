package io.github.mangi.eta.ui.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTerminalMessageOrderScaleTest {
    @Test
    fun orderedHistoryOf3200MixedMessagesAnd100RunsUsesBoundedFullListPasses() {
        val messages = CountingList(terminalOrderLargeHistory())
        assertEquals(3_200, messages.size)
        assertSame("An unchanged transcript retains its identity", messages, messages.withTerminalBodiesInOrder())
        assertTrue("Unchanged history must skip output/equality passes: ${messages.reads}", messages.reads <= 4 * messages.size)

        messages.reads = 0
        assertSame(messages, normalizeTerminalRunMessages(terminalOrderHistoryRunId(0), messages))
        assertTrue("Explicit-run normalization also skips output/equality passes", messages.reads <= 4 * messages.size)
    }

    @Test
    fun activeRunWithHistoricNoticesSkipsBodyOwnershipPassAndRetainsIdentity() {
        val runId = "active-with-history"
        val messages = CountingList(terminalOrderLargeHistory() + listOf(
            UserMessageUi("user-$runId", "active"),
            AgentMessageUi("assistant-$runId-1-0", "streaming", isStreaming = true),
            SystemNoticeMessageUi("assistant-$runId-1-usage", SystemNoticeCode.ModelRetry),
        ))
        assertSame(messages, normalizeTerminalRunMessages(runId, messages))
        // Initial notice detection reads at most one pass, then anchors and notices.
        // A body-owner map before the empty-run return would require a fourth pass.
        assertTrue("No full body-owner pass for a live run: ${messages.reads}", messages.reads <= 3 * messages.size)
    }

    @Test
    fun lateTerminalNoticeStillReordersActiveBodyAfterTheEarlyReturn() {
        val runId = "active-with-history"
        val history = terminalOrderLargeHistory()
        val user = UserMessageUi("user-$runId", "active")
        val body = AgentMessageUi("assistant-$runId-1-0", "streaming", isStreaming = true)
        val open = history + listOf(user, body)
        assertSame(open, normalizeTerminalRunMessages(runId, open))
        val notice = SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.Stopped)
        val late = body.copy(id = "assistant-$runId-1-1", content = "late", isStreaming = false)
        val closed = open + listOf(notice, late)
        assertEquals(LegacyTerminalMessageOrderReference.normalize(runId, closed), normalizeTerminalRunMessages(runId, closed))
        assertEquals(history + listOf(user, body, late, notice), normalizeTerminalRunMessages(runId, closed))
    }

    @Test
    fun largeHistoryRetainsLatestPayloadFirstSlotAndMovesOnlyLateBody() {
        val original = terminalOrderLargeHistory()
        val runId = terminalOrderHistoryRunId(0)
        val firstText = original[1] as AgentMessageUi
        val firstNotice = original[31] as SystemNoticeMessageUi
        val latest = firstText.copy(content = "latest full payload\n", usage = TokenUsageUi(inputTokens = 123))
        val late = ThinkingMessageUi("$runId-thinking-11-fallback", "late reasoning", false)
        val lastNotice = SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.RuntimeFailed, "latest detail")
        val input = original + listOf(latest, late, lastNotice)
        val expected = original.toMutableList().apply {
            this[1] = latest
            this[31] = lastNotice.copy(id = firstNotice.id)
            add(31, late)
        }
        assertEquals(expected, input.withTerminalBodiesInOrder())
        assertEquals(expected, normalizeTerminalRunMessages(runId, input))
        assertSame(expected, expected.withTerminalBodiesInOrder())
        assertEquals("Input payload is immutable", firstText, original[1])
    }

    @Test
    fun deterministicDifferentialMatchesFrozenLegacyForInterleavedAndNestedOwners() {
        val owners = listOf("r", "r-1", "r-1-2", "r-tool-1-call", "r-thinking-1", "anon_9e7", "r\nline")
        val random = Random(0x282D1F6)
        repeat(96) { sample ->
            val pool = buildList<AgentChatMessageUi> {
                owners.forEach { owner ->
                    add(UserMessageUi("user-$owner", "anchor $owner"))
                    add(UserMessageUi("user-$owner-supplement-1", "supplement"))
                    listOf("", "-1", "-1-2", "-2-result", "-2-usage", "-1-2-3", "-١").forEach { suffix ->
                        add(AgentMessageUi("assistant-$owner$suffix", "body:$sample:$suffix", isStreaming = true))
                    }
                    add(ThinkingMessageUi("$owner-thinking-2-fallback", "reasoning", false))
                    add(ThinkingMessageUi("$owner-thinking-2-0", "reasoning block", true))
                    add(tool("$owner-tool-1-call", "tool result"))
                    add(tool("$owner-tool-1-call-tool-2-tail", "nested tool ID"))
                    add(tool("$owner-tool-1-\n", "invalid suffix"))
                    add(SystemNoticeMessageUi("assistant-$owner-1", SystemNoticeCode.Stopped, "old"))
                    add(SystemNoticeMessageUi("assistant-$owner-2-usage", SystemNoticeCode.ModelRetry, "retry"))
                    add(SystemNoticeMessageUi("interrupted-$owner", SystemNoticeCode.Interrupted, "latest"))
                    add(SystemNoticeMessageUi("virtual-completed-$owner", SystemNoticeCode.Completed, "complete"))
                }
                add(AgentMessageUi("unowned-answer", "unowned"))
                add(SystemNoticeMessageUi("unowned-notice", SystemNoticeCode.RuntimeFailed))
            }
            val input = buildList {
                // Some samples deliberately have no user anchors. Thinking/tools
                // and explicit notices must infer ownership without adjacency.
                if (sample % 3 == 0) owners.forEach { add(UserMessageUi("user-$it", "anchor")) }
                repeat(45) {
                    val message = pool[random.nextInt(pool.size)]
                    add(message)
                    if (random.nextInt(5) == 0) add(when (message) {
                        is AgentMessageUi -> message.copy(content = "updated $sample", isStreaming = false)
                        is ThinkingMessageUi -> message.copy(content = "updated thinking", elapsedSeconds = 91)
                        is ToolActivityMessageUi -> message.copy(resultSummary = "updated tool")
                        is SystemNoticeMessageUi -> message.copy(detail = "updated notice")
                        else -> message
                    })
                }
            }.shuffled(random)
            assertEquivalent("all runs sample $sample", input, LegacyTerminalMessageOrderReference.orderAll(input), input.withTerminalBodiesInOrder())
            (owners + listOf("", " ", "unanchored")).forEach { owner ->
                assertEquivalent("$owner sample $sample", input,
                    LegacyTerminalMessageOrderReference.normalize(owner, input), normalizeTerminalRunMessages(owner, input))
            }
        }
    }

    private fun assertEquivalent(label: String, input: List<AgentChatMessageUi>, expected: List<AgentChatMessageUi>, actual: List<AgentChatMessageUi>) {
        assertEquals(label, expected, actual)
        if (expected === input) assertSame(label, input, actual)
    }

    private class CountingList(private val values: List<AgentChatMessageUi>) : AbstractList<AgentChatMessageUi>() {
        var reads = 0
        override val size: Int get() = values.size
        override fun get(index: Int): AgentChatMessageUi { reads++; return values[index] }
    }

    private fun tool(id: String, result: String) = ToolActivityMessageUi(
        id, "read", ToolActivityStatusUi.Success, "{}", resultSummary = result,
    )
}

internal fun terminalOrderHistoryRunId(index: Int): String = "history-$index-${"a".repeat(50)}"

/** 100 owners, 3,200 messages: near-device mix, with IDs approaching 100 chars. */
internal fun terminalOrderLargeHistory(): List<AgentChatMessageUi> = buildList {
    repeat(100) { index ->
        val runId = terminalOrderHistoryRunId(index)
        add(UserMessageUi("user-$runId", "saved question $index"))
        repeat(10) { round ->
            add(AgentMessageUi("assistant-$runId-$round-0", "saved text $index/$round", generatedAtMillis = 123L))
            add(ThinkingMessageUi("$runId-thinking-$round-0", "saved reasoning $index/$round", false))
            add(ToolActivityMessageUi("$runId-tool-$round-call", "read", ToolActivityStatusUi.Success,
                "args $index/$round", resultSummary = "result $index/$round"))
        }
        add(SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.Completed, "saved notice $index"))
    }
}
