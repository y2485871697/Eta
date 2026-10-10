package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.incrementalSnapshot
import org.junit.Assert.*
import org.junit.Test

class AgentTimelineRowsCacheTest {
    private class Fixture {
        var builds = 0
        val cache = AgentTimelineRowsCache { entries, expanded, streaming, retained ->
            builds++
            entries.toLazyTimelineRows(expanded, streaming, retained)
        }
        fun project(
            entries: List<AgentTimelineEntry>,
            expanded: Map<String, Boolean> = emptyMap(),
            streaming: Boolean = true,
            retained: Map<String, Set<String>> = emptyMap(),
        ): List<AgentTimelineRow> {
            val before = entries.toList()
            return cache.project(entries, expanded, streaming, retained).also { rows ->
                val expected = before.toLazyTimelineRows(expanded, streaming, retained)
                assertEquals(expected, rows)
                assertEquals(expected.lazyUserMessageIndices(), rows.lazyUserMessageIndices())
                for (paused in listOf(false, true)) {
                    assertEquals(expected.turnFooters(streaming, false, paused), rows.turnFooters(streaming, false, paused))
                }
                assertEquals(before, entries)
            }
        }
    }

    private fun input(): List<AgentChatMessageUi> = listOf(
        UserMessageUi("user-run", "task"),
        ThinkingMessageUi("run-thinking-1", "work", false),
        ThinkingMessageUi("run-thinking-2", "more work", false),
        SystemNoticeMessageUi("interrupted-run", SystemNoticeCode.Completed),
        AgentMessageUi("assistant-run-1", "answer", true),
    )

    @Test fun sameSlotAssistantDeltaPatchesOnlyItsRowAndKeepsLatestFooterOwner() {
        for (expanded in listOf(false, true)) {
            val fixture = Fixture()
            val projection = AgentTimelineProjectionCache()
            val source = input()
            val entries = projection.project(source)
            val groupKey = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>().single().key
            val overrides = mapOf(groupKey to expanded)
            val retained = mapOf(groupKey to setOf("work-step:run-thinking-2"))
            val old = fixture.project(entries, overrides, false, retained)
            val latest = (source.last() as AgentMessageUi).copy(content = "answer appended", isStreaming = false)
            val next = projection.project(source.dropLast(1) + latest)
            val rows = fixture.project(next, overrides, false, retained)
            assertEquals(1, fixture.builds)
            assertNotSame(old, rows)
            rows.zip(old).forEach { (current, previous) ->
                if (current is AgentTimelineRow.Message && current.message is AgentMessageUi) {
                    assertSame(latest, current.message)
                } else assertSame(previous, current)
            }
            // Do not cache footer owners with the row skeleton. The notice anchor
            // must still dispatch actions to the NEW assistant payload.
            assertSame(latest, rows.turnFooters(false, false, false).values.single())
            assertEquals(source.toTimelineEntries().toLazyTimelineRows(overrides, false, retained), old)
        }
    }

    @Test fun unchangedReferencesReuseOutputAndMultipleAssistantSlotsPatch() {
        val fixture = Fixture()
        val entries: List<AgentTimelineEntry> = listOf(
            AgentTimelineEntry.Message(AgentMessageUi("a", "one")),
            AgentTimelineEntry.Message(AgentMessageUi("b", "two")),
        )
        val old = fixture.project(entries)
        assertSame(old, fixture.project(entries.toList()))
        val current = entries.map { entry ->
            AgentTimelineEntry.Message(((entry as AgentTimelineEntry.Message).message as AgentMessageUi).copy(content = "new"))
        }
        fixture.project(current)
        assertEquals(1, fixture.builds)
    }

    @Test fun unhintedThinkingDeltaPatchesOnlyItsContiguousGroup() {
        for (expanded in listOf(false, true)) {
            for (retainExitStep in listOf(false, true)) {
                val fixture = Fixture()
                val visible = AgentVisibleMessagesCache()
                val projection = AgentTimelineProjectionCache()
                var messages: List<AgentChatMessageUi> = buildList {
                    repeat(40) { run ->
                        add(UserMessageUi("user-$run", "task"))
                        add(ThinkingMessageUi("thinking-$run", "completed", false))
                        add(AgentMessageUi("answer-$run", "answer"))
                    }
                    add(UserMessageUi("live-user", "new task"))
                    add(ThinkingMessageUi("live-thinking", "work", true))
                    add(AgentMessageUi("live-answer", "intermediate"))
                }
                val key = "work-live-thinking"
                val overrides = mapOf(key to expanded)
                val retained = if (retainExitStep) mapOf(key to setOf("work-step:live-thinking")) else emptyMap()
                val old = fixture.project(projection.project(visible.project(messages, null)), overrides, true, retained)
                val groupStart = old.indexOfFirst { it.key == key }
                repeat(8) {
                    val latest = (messages[messages.lastIndex - 1] as ThinkingMessageUi).copy(content = "work $it")
                    messages = messages.toMutableList().apply { this[lastIndex - 1] = latest }
                    // No singleReplacementFrom hint; every entry wrapper is rebuilt.
                    val next = fixture.project(projection.project(visible.project(messages, null)), overrides, true, retained)
                    assertSame(latest, next.filterIsInstance<AgentTimelineRow.WorkHeader>().last().group.messages.single())
                    old.indices.forEach { row ->
                        val belongsToGroup = row == groupStart || (old[row] as? AgentTimelineRow.WorkStep)?.groupKey == key
                        if (!belongsToGroup) assertSame(old[row], next[row])
                    }
                }
                assertEquals(1, fixture.builds)
                assertEquals("work", (old.filterIsInstance<AgentTimelineRow.WorkHeader>().last().group.messages.single() as ThinkingMessageUi).content)
            }
        }
    }

    @Test fun wrapperOnlySnapshotReuseKeepsTheNextReplacementCertificate() {
        val fixture = Fixture()
        val entries = input().toTimelineEntries().incrementalSnapshot()
        val old = fixture.project(entries)
        val wrappers = entries.map { entry ->
            when (entry) {
                is AgentTimelineEntry.Message -> entry.copy()
                is AgentTimelineEntry.WorkProcess -> entry.copy(messages = entry.messages.toList())
            }
        }.incrementalSnapshot()
        assertSame(old, fixture.project(wrappers))
        val groupIndex = wrappers.indexOfFirst { it is AgentTimelineEntry.WorkProcess }
        val group = wrappers[groupIndex] as AgentTimelineEntry.WorkProcess
        val current = group.copy(messages = group.messages.map { (it as ThinkingMessageUi).copy(content = "latest") })
        val after = fixture.project(wrappers.replacing(groupIndex, current))
        assertEquals(1, fixture.builds)
        assertSame(current, (after.first { it is AgentTimelineRow.WorkHeader } as AgentTimelineRow.WorkHeader).group)
        assertEquals(entries.toLazyTimelineRows(emptyMap(), true), old)
    }

    @Test fun hintedToolCompletionPatchesGroupButExpansionChangeFallsBack() {
        for (expanded in listOf(false, true)) {
            val fixture = Fixture()
            val running = ToolActivityMessageUi("tool", "terminal", ToolActivityStatusUi.Running, argumentsSummary = "command")
            val group = AgentTimelineEntry.WorkProcess("work-tool", listOf(running))
            val entries = listOf<AgentTimelineEntry>(group, AgentTimelineEntry.Message(AgentMessageUi("a", "answer"))).incrementalSnapshot()
            val overrides = mapOf(group.key to expanded)
            val before = fixture.project(entries, overrides)
            val done = running.copy(status = ToolActivityStatusUi.Success, resultSummary = "result")
            val current = entries.replacing(0, group.copy(messages = listOf(done)))
            val after = fixture.project(current, overrides)
            assertEquals(1, fixture.builds)
            assertSame(before.last(), after.last())
            assertSame(done, (after.first() as AgentTimelineRow.WorkHeader).group.messages.single())
            assertEquals(ToolActivityStatusUi.Running, ((before.first() as AgentTimelineRow.WorkHeader).group.messages.single() as ToolActivityMessageUi).status)
        }
        val fixture = Fixture()
        val group = AgentTimelineEntry.WorkProcess("work-t", listOf(ThinkingMessageUi("t", "work", true)))
        val entries = listOf<AgentTimelineEntry>(group, AgentTimelineEntry.Message(AgentMessageUi("a", "answer"))).incrementalSnapshot()
        fixture.project(entries)
        val done = group.copy(messages = listOf(ThinkingMessageUi("t", "work", false)))
        val collapsed = fixture.project(entries.replacing(0, done))
        assertEquals(2, fixture.builds)
        assertEquals(2, collapsed.size)
        assertFalse((collapsed.first() as AgentTimelineRow.WorkHeader).expanded)
    }

    @Test fun hintedAssistantGrowthPatchesTheRowAndKeepsTheFooterOwner() {
        val fixture = Fixture()
        val live = AgentMessageUi("a", "partial", true)
        val entries = listOf<AgentTimelineEntry>(AgentTimelineEntry.Message(live)).incrementalSnapshot()
        val before = fixture.project(entries, streaming = false)
        val grown = live.copy(content = "partial more")
        val after = fixture.project(entries.replacing(0, AgentTimelineEntry.Message(grown)), streaming = false)
        assertEquals(1, fixture.builds)
        assertSame(grown, (after.single() as AgentTimelineRow.Message).message)
        assertSame(grown, after.turnFooters(false, false, false).values.single())
        assertSame(live, (before.single() as AgentTimelineRow.Message).message)
    }

    @Test fun workKeyStepIdsAndMultipleChangedGroupsFallBack() {
        val first = AgentTimelineEntry.WorkProcess("work-one", listOf(ThinkingMessageUi("one", "work", true)))
        val second = AgentTimelineEntry.WorkProcess("work-two", listOf(ThinkingMessageUi("two", "work", true)))
        val entries = listOf<AgentTimelineEntry>(first, AgentTimelineEntry.Message(AgentMessageUi("a", "answer")), second)
        val variants = listOf(
            entries.toMutableList().apply { this[0] = first.copy(key = "new-work-key") },
            entries.toMutableList().apply { this[0] = first.copy(messages = listOf(ThinkingMessageUi("different", "work", true))) },
            entries.toMutableList().apply {
                this[0] = first.copy(messages = listOf(ThinkingMessageUi("one", "new", true)))
                this[2] = second.copy(messages = listOf(ThinkingMessageUi("two", "new", true)))
            },
            entries.toMutableList().apply {
                this[0] = first.copy(messages = listOf(ThinkingMessageUi("one", "new", true)))
                this[1] = AgentTimelineEntry.Message(AgentMessageUi("a", "new answer"))
            },
        )
        variants.forEach { current ->
            val fixture = Fixture()
            val before = fixture.project(entries)
            fixture.project(current)
            assertEquals(2, fixture.builds)
            assertEquals(entries.toLazyTimelineRows(emptyMap(), true), before)
        }
    }

    @Test fun nonContiguousRowsDisableAssistantAndWorkFastPaths() {
        for (hinted in listOf(false, true)) {
            for (changeWork in listOf(false, true)) {
                var builds = 0
                val cache = AgentTimelineRowsCache { entries, expanded, streaming, retained ->
                    builds++
                    val result = entries.toLazyTimelineRows(expanded, streaming, retained)
                    listOf(result[0], result[2], result[1]) // header, assistant, detached step
                }
                val group = AgentTimelineEntry.WorkProcess("work-t", listOf(ThinkingMessageUi("t", "work", true)))
                val answer = AgentTimelineEntry.Message(AgentMessageUi("a", "answer", true))
                val entries = listOf<AgentTimelineEntry>(group, answer).incrementalSnapshot()
                cache.project(entries, emptyMap(), true)
                val replacement = if (changeWork) group.copy(messages = listOf(ThinkingMessageUi("t", "more", true)))
                    else AgentTimelineEntry.Message(AgentMessageUi("a", "answer more", true))
                val index = if (changeWork) 0 else 1
                val current = entries.replacing(index, replacement)
                cache.project(if (hinted) current else current.withoutReplacementHint(), emptyMap(), true)
                assertEquals(2, builds)
            }
        }
    }

    @Test fun insertDeleteReorderBoundaryAndWorkChangesUseFullProjection() {
        val entries = input().toTimelineEntries()
        val variants = listOf(
            entries + AgentTimelineEntry.Message(AgentMessageUi("new", "body")),
            entries.toMutableList().apply { add(1, AgentTimelineEntry.Message(AgentMessageUi("new", "body"))) },
            entries.dropLast(1),
            entries.toMutableList().apply { removeAt(1) },
            entries.reversed(),
            entries.toMutableList().apply { this[0] = AgentTimelineEntry.Message(UserMessageUi("user-run-supplement-resume", "task")) },
            entries.toMutableList().apply { this[0] = AgentTimelineEntry.Message(UserMessageUi("user-run", "edited")) },
            entries.toMutableList().apply { this[1] = (entries[1] as AgentTimelineEntry.WorkProcess).copy(
                messages = listOf(ThinkingMessageUi("run-thinking-1", "work", true))) },
            entries.toMutableList().apply { this[2] = AgentTimelineEntry.Message(AgentMessageUi("different-id", "body")) },
        )
        variants.forEach { current ->
            val fixture = Fixture()
            val old = fixture.project(entries)
            fixture.project(current)
            assertEquals(2, fixture.builds)
            assertEquals(entries.toLazyTimelineRows(emptyMap(), true), old)
        }
    }

    @Test fun policyChangesAlwaysRebuildEvenWithTheSameEntries() {
        val entries = input().toTimelineEntries()
        val key = (entries[1] as AgentTimelineEntry.WorkProcess).key
        val fixture = Fixture()
        fixture.project(entries)
        fixture.project(entries, mapOf(key to false))
        fixture.project(entries, mapOf(key to false), false)
        fixture.project(entries, mapOf(key to false), false, mapOf(key to setOf("work-step:run-thinking-1")))
        assertEquals(4, fixture.builds)
    }

    @Test fun duplicateIdsIncludingHiddenWorkStepsDisableFastPath() {
        val assistant = AgentMessageUi("same", "body", true)
        for (duplicate in listOf<AgentTimelineEntry>(
            AgentTimelineEntry.Message(assistant),
            AgentTimelineEntry.Message(UserMessageUi("same", "task")),
            AgentTimelineEntry.WorkProcess("work-same", listOf(ThinkingMessageUi("same", "work", false))),
        )) {
            val fixture = Fixture()
            val entries = listOf(duplicate, AgentTimelineEntry.Message(assistant))
            fixture.project(entries, streaming = false)
            fixture.project(entries.dropLast(1) + AgentTimelineEntry.Message(assistant.copy(content = "body more")), streaming = false)
            assertEquals(2, fixture.builds)
        }
    }

    @Test fun mutablePolicyAndSourceContainersDoNotRewriteSavedSnapshots() {
        val fixture = Fixture()
        val entries = input().toTimelineEntries().toMutableList()
        val key = (entries[1] as AgentTimelineEntry.WorkProcess).key
        val expanded = mutableMapOf(key to false)
        val retainedIds = mutableSetOf("work-step:run-thinking-1")
        val retained: Map<String, Set<String>> = mapOf(key to retainedIds)
        val old = fixture.project(entries, expanded, false, retained)
        entries[2] = AgentTimelineEntry.Message(AgentMessageUi("assistant-run-1", "latest"))
        val latestRows = fixture.project(entries, expanded, false, retained)
        assertEquals(1, fixture.builds)
        assertEquals("answer", ((old[old.indexOfFirst { it is AgentTimelineRow.Message && it.message is AgentMessageUi }] as AgentTimelineRow.Message).message as AgentMessageUi).content)
        retainedIds.add("work-step:run-thinking-2")
        fixture.project(entries, expanded, false, retained)
        expanded[key] = true
        fixture.project(entries, expanded, false, retained)
        assertEquals(3, fixture.builds)
        assertEquals(1, latestRows.count { it is AgentTimelineRow.WorkStep })
    }

    @Test fun unexpectedRowMappingFailsClosed() {
        var builds = 0
        val cache = AgentTimelineRowsCache { _, _, _, _ -> builds++; emptyList() }
        cache.project(listOf(AgentTimelineEntry.Message(AgentMessageUi("a", "old"))), emptyMap(), true)
        cache.project(listOf(AgentTimelineEntry.Message(AgentMessageUi("a", "new"))), emptyMap(), true)
        assertEquals(2, builds)
    }
}
