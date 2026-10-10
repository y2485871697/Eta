package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.*
import io.github.mangi.eta.ui.components.*
import org.junit.Assert.*
import org.junit.Test

class AgentTerminalSnapshotRegressionTest {
    @Test fun realFinalizersAndTimestampMatchLegacyPayloadsAndKeepOldSnapshots() {
        val projector = AgentRunMessageProjector { 7_000L }
        val usage = TokenUsageUi(inputTokens = 5000, outputTokens = 44)
        val historical = List<AgentChatMessageUi>(4096) { UserMessageUi("user-$it", "old-$it") }
        val input = (historical + listOf(
            ThinkingMessageUi("live-thinking-1-0", "reasoning", true, elapsedSeconds = 3, collapsed = false),
            AgentMessageUi("assistant-other-1-0", "other ", true, usage = usage),
            AgentMessageUi("assistant-live-1-0", "answer\n\n", true, renderMarkdown = false, usage = usage),
        )).incrementalSnapshot()
        val originalHash = input.hashCode()
        val oldEntries = input.toTimelineEntries()
        val oldRows = oldEntries.toLazyTimelineRows(emptyMap(), true)
        val thinking = projector.finalizeThinking("live", input)
        val thinkingOracle = input.map { if (it is ThinkingMessageUi && it.id.startsWith("live-thinking-"))
            it.copy(isStreaming = false, collapsed = true) else it }
        assertEquals(thinkingOracle, thinking)
        assertNull((thinking as AgentIncrementalList<*>).singleReplacementFrom(input))
        val text = projector.finalizeText("live", thinking)
        val textOracle = thinkingOracle.map { if (it is AgentMessageUi && it.id.startsWith("assistant-live-"))
            it.copy(content = it.content.trimEnd(), isStreaming = false, renderMarkdown = true) else it }
        assertEquals(textOracle, text)
        assertNull((text as AgentIncrementalList<*>).singleReplacementFrom(thinking))
        val stamped = stampCompletedReply(text, "live", 12345L)
        val stampedOracle = textOracle.mapIndexed { i, item -> if (i == textOracle.lastIndex)
            (item as AgentMessageUi).copy(generatedAtMillis = 12345L) else item }
        assertEquals(stampedOracle, stamped)
        assertNull((stamped as AgentIncrementalList<*>).singleReplacementFrom(text))
        assertSame(stamped, stampCompletedReply(stamped, "live", 99999L))
        assertSame(stamped, projector.finalizeText("live", stamped))
        val filter = AgentVisibleMessagesCache()
        var fullBuilds = 0
        val timeline = AgentTimelineProjectionCache { fullBuilds++; it.toTimelineEntries() }
        val rows = AgentTimelineRowsCache()
        val visibleBefore = filter.project(input, null)
        timeline.project(visibleBefore)
        val visible = filter.project(stamped, null)
        val entries = timeline.project(visible)
        val lazyRows = rows.project(entries, emptyMap(), false)
        // The stamped assistant payload and the thinking step both patch in place: neither
        // feeds terminal ordering or grouping, so only the first projection rebuilds.
        assertEquals(1, fullBuilds)
        assertEquals(visible.toTimelineEntries(), entries)
        assertEquals(entries.toLazyTimelineRows(emptyMap(), false), lazyRows)
        assertEquals("answer", (stamped.last() as AgentMessageUi).content)
        assertEquals(usage, (stamped.last() as AgentMessageUi).usage)
        assertSame(input[4097], stamped[4097])
        assertEquals("answer\n\n", (input.last() as AgentMessageUi).content)
        assertEquals(originalHash, input.hashCode())
        assertEquals(input.toTimelineEntries(), oldEntries)
        assertEquals(oldEntries.toLazyTimelineRows(emptyMap(), true), oldRows)
    }

    @Test fun multipleFinalizedSlotsDoNotClaimSingleSlotAndOtherRunIsUntouched() {
        val projector = AgentRunMessageProjector { 1_000L }
        val other = AgentMessageUi("assistant-other-1", "other ", true)
        val input = listOf<AgentChatMessageUi>(other, AgentMessageUi("assistant-live-1", "a ", true),
            AgentMessageUi("assistant-live-2", "b\n", true)).incrementalSnapshot()
        val finalized = projector.finalizeText("live", input)
        assertEquals(input.map { if (it is AgentMessageUi && it.id.startsWith("assistant-live-"))
            it.copy(content = it.content.trimEnd(), isStreaming = false, renderMarkdown = true) else it }, finalized)
        assertNull((finalized as AgentIncrementalList<*>).singleReplacementFrom(input))
        assertSame(other, finalized[0])
    }
}
