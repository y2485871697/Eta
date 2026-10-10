package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
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

    /** Distinct id space so separate groups never share a row key. */
    private fun namedTool(id: String, running: Boolean = false) = ToolActivityMessageUi(
        id = id, toolName = "terminal",
        status = if (running) ToolActivityStatusUi.Running else ToolActivityStatusUi.Success,
        argumentsSummary = "command-$id", command = "complete command $id",
        resultSummary = "complete result $id",
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

    @Test fun everyExpandedBatchHasExactlyOneFirstAndLastStep() {
        for (count in listOf(1, 31, 32, 33, 64, 65, 1_000)) {
            val groups = List(count) { tool(it) }.toTimelineEntries()
            val rows = groups.toLazyTimelineRows(groups.associate { it.key to true }, false)
            val batches = rows.filterIsInstance<AgentTimelineRow.WorkStep>().groupBy { it.groupKey }.values
            assertEquals(List(count) { it }.chunked(32).map { it.size }, batches.map { it.size })
            batches.forEach { steps ->
                assertEquals(1, steps.count { it.isFirst })
                assertEquals(1, steps.count { it.isLast })
                assertTrue(steps.first().isFirst)
                assertTrue(steps.last().isLast)
                assertTrue(steps.drop(1).none { it.isFirst })
                assertTrue(steps.dropLast(1).none { it.isLast })
            }
        }
    }

    @Test fun appendingUpdatesLastCornerWithoutChangingExistingKeys() {
        val initial = listOf(tool(0)).toTimelineEntries()
        val expanded = mapOf(initial.single().key to true)
        val first = initial.toLazyTimelineRows(expanded, false).filterIsInstance<AgentTimelineRow.WorkStep>()
        val next = listOf(tool(0), tool(1)).toTimelineEntries().toLazyTimelineRows(expanded, false)
            .filterIsInstance<AgentTimelineRow.WorkStep>()
        assertTrue(first.single().isFirst && first.single().isLast)
        assertEquals(first.single().key, next.first().key)
        assertTrue(next.first().isFirst)
        assertFalse(next.first().isLast)
        assertFalse(next.last().isFirst)
        assertTrue(next.last().isLast)
    }

    @Test fun appendAcrossBatchBoundaryKeepsClosedEdgesAndStableKeys() {
        var previous = emptyList<AgentTimelineRow>()
        for (count in listOf(31, 32, 33, 64, 65)) {
            val groups = List(count) { tool(it) }.toTimelineEntries()
            val rows = groups.toLazyTimelineRows(groups.associate { it.key to true }, false)
            assertEquals(previous.map { it.key }, rows.take(previous.size).map { it.key })
            val batches = rows.filterIsInstance<AgentTimelineRow.WorkStep>().groupBy { it.groupKey }.values
            assertEquals(List(count) { it }.chunked(32).map { it.size }, batches.map { it.size })
            batches.forEach { batch ->
                assertTrue(batch.first().isFirst)
                assertTrue(batch.last().isLast)
                assertTrue(batch.dropLast(1).none { it.isLast })
            }
            // Completed new batches stay collapsed unless explicitly opened.
            val defaultRows = groups.toLazyTimelineRows(emptyMap(), false)
            assertTrue(defaultRows.all { it is AgentTimelineRow.WorkHeader })
            previous = rows
        }
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

    @Test fun streamingDefaultKeyPreservesRowsForEveryTrailingPolicy() {
        val completed = listOf(tool(0), tool(1)).toTimelineEntries()
        val running = listOf(tool(0, running = true), tool(1)).toTimelineEntries()
        val variants = listOf(
            emptyList(),
            listOf(AgentTimelineEntry.Message(AgentMessageUi("a", "answer"))),
            completed,
            running,
            completed + AgentTimelineEntry.Message(AgentMessageUi("a", "answer")),
            running + AgentTimelineEntry.Message(AgentMessageUi("a", "answer")),
            List(33) { tool(it) }.toTimelineEntries(),
        )
        for (entries in variants) {
            val workKeys = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>().map { it.key }
            for (overrides in listOf(emptyMap(), workKeys.associateWith { false }, workKeys.associateWith { true })) {
                for (streaming in listOf(false, true)) {
                    val key = entries.streamingWorkDefault(overrides, streaming)
                    for (retained in listOf(emptyMap(), workKeys.associateWith { setOf("work-step:tool-1") })) {
                        assertEquals(entries.toLazyTimelineRows(overrides, streaming, retained), entries.toLazyTimelineRows(overrides, key, retained))
                    }
                    val trailing = entries.lastOrNull() as? AgentTimelineEntry.WorkProcess
                    assertEquals(streaming && trailing != null && trailing.key !in overrides, key)
                }
            }
        }
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

    @Test
    fun completionNoticeDoesNotHidePendingAssistantReveal() {
        val answer = AgentMessageUi("a", "answer", isStreaming = false)
        val messages = listOf(answer, SystemNoticeMessageUi("done", SystemNoticeCode.Completed))
        assertTrue(hasPendingAssistantReveal(messages) { it.id == answer.id })
        assertFalse(hasPendingAssistantReveal(messages) { false })
        assertTrue(hasPendingAssistantReveal(messages + AgentMessageUi("empty", "")) { it.id == answer.id })
    }

    @Test
    fun olderAssistantRevealDoesNotKeepLatestAnswerSettling() {
        val messages = listOf(AgentMessageUi("old", "old answer"), AgentMessageUi("new", "new answer"))
        assertFalse(hasPendingAssistantReveal(messages) { it.id == "old" })
        assertTrue(hasPendingAssistantReveal(messages) { it.id == "new" })
        assertFalse(hasPendingAssistantReveal(emptyList()) { true })
    }

    @Test
    fun MissingRevealStateDiffersFromPresentUninitializedState() {
        val messages = listOf(AgentMessageUi("a", "answer"), SystemNoticeMessageUi("done", SystemNoticeCode.Completed))
        val retained = mapOf<String, String?>("a" to null)
        assertTrue(hasPendingAssistantReveal(messages) { retained.containsKey(it.id) && retained[it.id] != it.content })
        val absent = emptyMap<String, String?>()
        assertFalse(hasPendingAssistantReveal(messages) { absent.containsKey(it.id) && absent[it.id] != it.content })
    }

    @Test
    fun recollapsingUpperGroupKeepsExplicitLowerStepGroupExpandedWithStableKeys() {
        // Upper group is deliberately large (32 steps, at the batch limit); lower group has 4 steps.
        val upper = List(32) { namedTool("upper-$it") }
        val lower = List(4) { namedTool("lower-$it") }
        val entries = (upper + AgentMessageUi("sep", "between groups") + lower).toTimelineEntries()
        assertEquals(3, entries.size)
        val upperKey = "work-upper-0"
        val lowerKey = "work-lower-0"
        assertEquals(upperKey, entries[0].key)
        assertEquals("sep", entries[1].key)
        assertEquals(lowerKey, entries[2].key)

        // Lower group opened by hand, upper group collapsed by hand.
        val userState = mapOf(upperKey to false, lowerKey to true)

        val before = entries.toLazyTimelineRows(userState, false)
        assertEquals(
            listOf(upperKey, "sep", lowerKey) + List(4) { "work-step:lower-$it" },
            before.map { it.key },
        )
        val beforeHeaders = before.filterIsInstance<AgentTimelineRow.WorkHeader>()
        assertEquals(listOf(upperKey, lowerKey), beforeHeaders.map { it.key })
        assertFalse(beforeHeaders[0].expanded)
        assertTrue(beforeHeaders[1].expanded)
        val beforeLowerSteps = before.filterIsInstance<AgentTimelineRow.WorkStep>()
        assertEquals(4, beforeLowerSteps.size)
        assertTrue(beforeLowerSteps.all { it.groupKey == lowerKey })
        assertEquals(lower.map { it.id }, beforeLowerSteps.map { (it.message as ToolActivityMessageUi).id })
        assertEquals(listOf("work-step:lower-0", "work-step:lower-1", "work-step:lower-2", "work-step:lower-3"), beforeLowerSteps.map { it.key })
        assertEquals(listOf(true, false, false, true), beforeLowerSteps.map { it.isFirst || it.isLast })
        assertTrue(beforeLowerSteps.first().isFirst)
        assertTrue(beforeLowerSteps.last().isLast)

        // Expand the upper group; the lower group must not change.
        val expanded = entries.toLazyTimelineRows(mapOf(upperKey to true, lowerKey to true), false)
        val expandedUpperSteps = expanded.filterIsInstance<AgentTimelineRow.WorkStep>().filter { it.groupKey == upperKey }
        assertEquals(32, expandedUpperSteps.size)
        assertEquals(List(32) { "work-step:upper-$it" }, expandedUpperSteps.map { it.key })
        assertEquals(
            beforeLowerSteps,
            expanded.filterIsInstance<AgentTimelineRow.WorkStep>().filter { it.groupKey == lowerKey },
        )

        // Collapse the upper group again; the explicitly-expanded lower group survives untouched.
        val after = entries.toLazyTimelineRows(userState, false)
        assertEquals(before.map { it.key }, after.map { it.key })
        val afterHeaders = after.filterIsInstance<AgentTimelineRow.WorkHeader>()
        assertEquals(listOf(upperKey, lowerKey), afterHeaders.map { it.key })
        assertFalse(afterHeaders[0].expanded)
        assertTrue(afterHeaders[1].expanded)
        assertEquals(beforeHeaders[1], afterHeaders[1])
        val afterLowerSteps = after.filterIsInstance<AgentTimelineRow.WorkStep>()
        assertEquals(4, afterLowerSteps.size)
        assertEquals(beforeLowerSteps, afterLowerSteps)
        assertTrue(afterLowerSteps.all { it.groupKey == lowerKey })
        assertTrue(after.filterIsInstance<AgentTimelineRow.WorkStep>().none { it.groupKey == upperKey })
    }

    @Test
    fun streamingAutoExpansionAndBatchBoundaryStayDistinctFromUserOverrides() {
        // 33 consecutive work messages cross the 32-message batch boundary into two groups.
        val entries = List(33) { tool(it) }.toTimelineEntries()
        assertEquals(2, entries.size)
        val leadingKey = "work-tool-0"
        val trailingKey = "work-tool-32"
        assertEquals(listOf(leadingKey, trailingKey), entries.map { it.key })
        assertEquals(32, (entries[0] as AgentTimelineEntry.WorkProcess).messages.size)
        assertEquals(1, (entries[1] as AgentTimelineEntry.WorkProcess).messages.size)

        // While the turn is streaming, only the trailing completed batch auto-expands.
        val streaming = entries.toLazyTimelineRows(emptyMap(), true)
        assertEquals(
            listOf(leadingKey, trailingKey, "work-step:tool-32"),
            streaming.map { it.key },
        )
        assertEquals(
            listOf(false, true),
            streaming.filterIsInstance<AgentTimelineRow.WorkHeader>().map { it.expanded },
        )
        val trailingStep = streaming.filterIsInstance<AgentTimelineRow.WorkStep>().single()
        assertEquals(trailingKey, trailingStep.groupKey)
        assertTrue(trailingStep.isFirst)
        assertTrue(trailingStep.isLast)

        // A user override collapses the streaming trailing batch; the leading batch key stays put.
        val collapsedTrailing = entries.toLazyTimelineRows(mapOf(trailingKey to false), true)
        assertEquals(listOf(leadingKey, trailingKey), collapsedTrailing.map { it.key })
        assertTrue(collapsedTrailing.all { it is AgentTimelineRow.WorkHeader })
        assertTrue(collapsedTrailing.filterIsInstance<AgentTimelineRow.WorkHeader>().none { it.expanded })

        // A user override expands the leading batch when idle; the trailing batch keeps its default collapse.
        val expandedLeading = entries.toLazyTimelineRows(mapOf(leadingKey to true), false)
        val leadingSteps = expandedLeading.filterIsInstance<AgentTimelineRow.WorkStep>()
        assertEquals(32, leadingSteps.size)
        assertTrue(leadingSteps.all { it.groupKey == leadingKey })
        assertEquals("work-step:tool-0", leadingSteps.first().key)
        assertTrue(leadingSteps.first().isFirst)
        assertTrue(leadingSteps.last().isLast)
        assertTrue(leadingSteps.drop(1).none { it.isFirst })
        assertTrue(leadingSteps.dropLast(1).none { it.isLast })
        assertEquals(
            listOf(true, false),
            expandedLeading.filterIsInstance<AgentTimelineRow.WorkHeader>().map { it.expanded },
        )

        // No override and no streaming leaves both batch groups collapsed.
        val idle = entries.toLazyTimelineRows(emptyMap(), false)
        assertTrue(idle.all { it is AgentTimelineRow.WorkHeader })
        assertEquals(listOf(leadingKey, trailingKey), idle.map { it.key })
        assertEquals(
            listOf(false, false),
            idle.filterIsInstance<AgentTimelineRow.WorkHeader>().map { it.expanded },
        )
    }

    @Test
    fun nonTrailingWorkKeepsRunningDefaultRegardlessOfTurnStreaming() {
        for (running in listOf(false, true)) {
            for (isStreaming in listOf(false, true)) {
                val entries = listOf<AgentChatMessageUi>(
                    tool(0, running = running),
                    AgentMessageUi("answer", "answer", isStreaming = isStreaming),
                ).toTimelineEntries()
                assertEquals(listOf("work-tool-0", "answer"), entries.map { it.key })

                val rows = entries.toLazyTimelineRows(emptyMap(), isStreaming)
                val header = rows.filterIsInstance<AgentTimelineRow.WorkHeader>().single()
                assertEquals(running, header.expanded)
                assertEquals(
                    if (running) {
                        listOf("work-tool-0", "work-step:tool-0", "answer")
                    } else {
                        listOf("work-tool-0", "answer")
                    },
                    rows.map { it.key },
                )

                val manuallyCollapsed = entries.toLazyTimelineRows(
                    mapOf("work-tool-0" to false),
                    isStreaming,
                )
                assertEquals(
                    listOf("work-tool-0", "answer"),
                    manuallyCollapsed.map { it.key },
                )
                assertFalse(
                    manuallyCollapsed.filterIsInstance<AgentTimelineRow.WorkHeader>()
                        .single().expanded,
                )
            }
        }
    }
}
