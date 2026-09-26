package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ConversationMention
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTimelineProjectionTest {
    @Test
    fun consecutiveWorkIsBoundedWithoutEmptyGroupsOrLostMessages() {
        for (count in listOf(0, 1, 31, 32, 33, 64, 65, 1_000)) {
            val messages = List(count, ::workMessage)
            val entries = messages.toTimelineEntries()
            val groups = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>()

            assertEquals(entries.size, groups.size)
            assertEquals(messages.chunked(32).map { it.size }, groups.map { it.messages.size })
            assertTrue(groups.all { it.messages.size in 1..32 })
            assertEquals(messages, entries.flattenMessages())
            assertEquals(entries.size, entries.map { it.key }.toSet().size)
            groups.forEach { group ->
                assertEquals("work-${group.messages.first().id}", group.key)
            }
            messages.zip(entries.flattenMessages()).forEach { (original, projected) ->
                assertSame(original, projected)
            }
        }
    }

    @Test
    fun appendAndStreamingUpdatesKeepExistingGroupKeysAndProjectionSnapshots() {
        val messages = List(97, ::workMessage)
        var previous = emptyList<AgentTimelineEntry>()
        for (count in listOf(1, 31, 32, 33, 63, 64, 65, 96, 97)) {
            val previousMessages = previous.flattenMessages()
            val entries = messages.take(count).toTimelineEntries()

            assertEquals(previous.map { it.key }, entries.take(previous.size).map { it.key })
            assertEquals(previousMessages, previous.flattenMessages())
            assertEquals(messages.take(count), entries.flattenMessages())
            previous = entries
        }

        val updatedMessages = messages.map { message ->
            when (message) {
                is ThinkingMessageUi -> message.copy(content = message.content + " appended", isStreaming = false)
                is ToolActivityMessageUi -> message.copy(status = ToolActivityStatusUi.Success, resultSummary = "done")
                else -> message
            }
        }
        val updated = updatedMessages.toTimelineEntries()
        assertEquals(previous.map { it.key }, updated.map { it.key })
        assertEquals(messages, previous.flattenMessages())
        assertEquals(updatedMessages, updated.flattenMessages())
    }

    @Test
    fun batchesRespectMessageBoundariesAndPreserveFullTranscriptAndNavigation() {
        val firstRun = List(65, ::workMessage)
        val secondRun = List(33) { workMessage(65 + it) }
        val resume = UserMessageUi("user-1-supplement-resume", "hidden resume")
        val messages = buildList<AgentChatMessageUi> {
            add(UserMessageUi("user-1", "first task"))
            addAll(firstRun.take(31))
            add(resume)
            addAll(firstRun.drop(31))
            add(AgentMessageUi("answer-1", "intermediate answer"))
            addAll(secondRun)
            add(SystemNoticeMessageUi("notice", SystemNoticeCode.Stopped))
            add(workMessage(98))
            add(ContextCompactedMessageUi("compacted", 1, "saved summary"))
            add(workMessage(99))
            add(UserMessageUi("user-2", "second task"))
            add(AgentMessageUi("answer-2", "final answer"))
        }
        val originalMessages = messages.toList()
        val originalTranscript = ConversationMention.transcript(messages)

        val entries = messages.toTimelineEntries()
        val projectedMessages = entries.flattenMessages()

        assertEquals(
            listOf(
                "user-1", "work-step-0", "work-step-32", "work-step-64", "answer-1",
                "work-step-65", "work-step-97", "notice", "work-step-98", "compacted",
                "work-step-99", "user-2", "answer-2",
            ),
            entries.map { it.key },
        )
        assertEquals(listOf(32, 32, 1, 32, 1, 1, 1),
            entries.filterIsInstance<AgentTimelineEntry.WorkProcess>().map { it.messages.size })
        // Keep the existing hidden-resume policy; pagination must not drop anything else.
        assertEquals(messages.filterNot { it === resume }, projectedMessages)
        assertEquals(originalMessages, messages)
        assertEquals(listOf(0, 11), entries.userMessageIndices())
        assertEquals(originalTranscript, ConversationMention.transcript(projectedMessages))
        assertEquals(originalTranscript, ConversationMention.transcript(messages))
        assertFalse(originalTranscript.contains(ConversationMention.OMISSION_MARKER))
        for (index in 0..99) {
            assertTrue("Missing work step $index from transcript", originalTranscript.contains("payload-$index-end"))
        }
    }

    private fun workMessage(index: Int): AgentChatMessageUi = when (index % 3) {
        0 -> ThinkingMessageUi(
            id = "step-$index",
            content = "payload-$index-end",
            isStreaming = true,
        )
        1 -> ToolActivityMessageUi(
            id = "step-$index",
            toolName = "read_file",
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "payload-$index-end",
            command = "cat step-$index.txt",
            resultSummary = "result-$index-end",
        )
        else -> ToolSummaryMessageUi(
            id = "step-$index",
            tools = listOf("payload-$index-end"),
        )
    }

    private fun List<AgentTimelineEntry>.flattenMessages(): List<AgentChatMessageUi> = flatMap { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> listOf(entry.message)
            is AgentTimelineEntry.WorkProcess -> entry.messages
        }
    }
}
