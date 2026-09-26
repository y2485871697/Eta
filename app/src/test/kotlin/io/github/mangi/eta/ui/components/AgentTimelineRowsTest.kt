package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test

class AgentTimelineRowsTest {
    private fun tool(i: Int, running: Boolean = false) = ToolActivityMessageUi(
        id = "tool-$i", toolName = "terminal",
        status = if (running) ToolActivityStatusUi.Running else ToolActivityStatusUi.Success,
        argumentsSummary = "command-$i", command = "complete command $i",
        resultSummary = "complete result $i",
    )

    @Test fun expandedGroupsProduceIndependentLazyStepsWithoutLosingContent() {
        val tools = List(1_000) { tool(it) }
        val groups = tools.toTimelineEntries()
        val rows = groups.toLazyTimelineRows(groups.associate { it.key to true }, false)
        val steps = rows.filterIsInstance<AgentTimelineRow.WorkStep>()
        assertEquals(1_000, steps.size)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        tools.zip(steps).forEach { (original, row) -> assertSame(original, row.message) }
        assertEquals(groups.size, rows.filterIsInstance<AgentTimelineRow.WorkHeader>().size)
    }

    @Test fun collapsedGroupsRetainDataButDoNotEmitDetails() {
        val tools = List(65) { tool(it) }
        val groups = tools.toTimelineEntries()
        val rows = groups.toLazyTimelineRows(emptyMap(), false)
        assertTrue(rows.all { it is AgentTimelineRow.WorkHeader })
        assertEquals(tools, rows.filterIsInstance<AgentTimelineRow.WorkHeader>().flatMap { it.group.messages })
    }

    @Test fun manualCollapsedOverridesRunningAndManualExpandedSurvivesCompletion() {
        val live = listOf(tool(1, running = true)).toTimelineEntries()
        val group = live.single().key
        assertEquals(2, live.toLazyTimelineRows(emptyMap(), true).size)
        assertEquals(1, live.toLazyTimelineRows(mapOf(group to false), true).size)
        val done = listOf(tool(1)).toTimelineEntries()
        assertEquals(2, done.toLazyTimelineRows(mapOf(group to true), false).size)
        assertEquals(1, done.toLazyTimelineRows(emptyMap(), false).size)
    }

    @Test fun keysAndNavigationFollowFlattenedRowsNotGroupIndices() {
        val first = listOf<AgentChatMessageUi>(UserMessageUi("u1", "one"), tool(0), tool(1), UserMessageUi("u2", "two"))
        val groups = first.toTimelineEntries()
        val opened = groups.toLazyTimelineRows(mapOf("work-tool-0" to true), false)
        assertEquals(listOf(0, 4), opened.lazyUserMessageIndices())
        assertEquals(3, opened.indexOfFirst { it.containsMessageId("tool-1") })
        val closed = groups.toLazyTimelineRows(emptyMap(), false)
        assertEquals(listOf(0, 2), closed.lazyUserMessageIndices())
        assertEquals(1, closed.indexOfFirst { it.containsMessageId("tool-1") })
        val appended = (first.dropLast(1) + tool(2) + first.last()).toTimelineEntries()
            .toLazyTimelineRows(mapOf("work-tool-0" to true), false)
        assertEquals(opened.take(4).map { it.key }, appended.take(4).map { it.key })
        assertEquals("complete command 1", (opened[3] as AgentTimelineRow.WorkStep).message.let { (it as ToolActivityMessageUi).command })
    }
}
