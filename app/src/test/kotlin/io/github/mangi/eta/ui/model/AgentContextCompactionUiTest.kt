package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompactionUiTest {
    @Test
    fun insertMarkerSitsBeforeKeptUserMessages() {
        val messages = listOf(
            UserMessageUi("u1", "旧问题"),
            AgentMessageUi("a1", "旧答复"),
            UserMessageUi("u2", "继续"),
            AgentMessageUi("a2", "新答复"),
        )
        val marker = ContextCompactedMessageUi("c1", compactedCount = 2, summary = "旧问题已解决")
        val updated = AgentContextCompactionUi.insertMarker(messages, marker, keptUserCount = 1)
        assertEquals(listOf("u1", "a1", "c1", "u2", "a2"), updated.map { it.id })
        assertEquals(2, (updated[2] as ContextCompactedMessageUi).compactedCount)
    }

    @Test
    fun insertMarkerReplacesExistingMarkerAtSameBoundary() {
        val messages = listOf(
            UserMessageUi("u1", "旧问题"),
            ContextCompactedMessageUi("old", compactedCount = 1, summary = "旧"),
            UserMessageUi("u2", "继续"),
        )
        val marker = ContextCompactedMessageUi("new", compactedCount = 3, summary = "新摘要")
        val updated = AgentContextCompactionUi.insertMarker(messages, marker, keptUserCount = 1)
        assertEquals(listOf("u1", "new", "u2"), updated.map { it.id })
        assertEquals("新摘要", (updated[1] as ContextCompactedMessageUi).summary)
    }

    @Test
    fun applyMarkerCountsVisibleMessagesAndKeepsSummaryText() {
        val original = listOf(
            msg("user", "第一轮"),
            msg("assistant", "第一轮答复"),
            msg("tool", "observe_screen ok"),
            msg("user", "第二轮"),
            msg("assistant", "第二轮答复"),
        )
        val compressed = listOf(
            msg("system", "${AgentContextCompactor.SUMMARY_PREFIX_ZH}\n第一轮已经看过屏幕"),
            msg("user", "第二轮"),
            msg("assistant", "第二轮答复"),
        )
        val ui = listOf(
            UserMessageUi("u1", "第一轮"),
            AgentMessageUi("a1", "第一轮答复"),
            UserMessageUi("u2", "第二轮"),
            AgentMessageUi("a2", "第二轮答复"),
            UserMessageUi("u3", "刚发的"),
        )
        val updated = AgentContextCompactionUi.applyMarker(
            messages = ui,
            originalHistory = original,
            compressedHistory = compressed,
            extraKeptUserMessages = 1,
            compressorLabel = "魚 · grok-4.6",
            markerId = "c1",
        )
        assertEquals(listOf("u1", "a1", "c1", "u2", "a2", "u3"), updated.map { it.id })
        val marker = updated[2] as ContextCompactedMessageUi
        assertEquals(2, marker.compactedCount)
        assertEquals("第一轮已经看过屏幕", marker.summary)
        assertEquals("魚 · grok-4.6", marker.compressorLabel)
        assertTrue("summary should not keep the prefix", !marker.summary.startsWith("["))
    }

    @Test
    fun applyMarkerKeepsCumulativeUsageOnMarkerAndClearsAssistantBills() {
        val original = listOf(
            msg("user", "第一轮"),
            msg("assistant", "第一轮答复"),
            msg("user", "第二轮"),
            msg("assistant", "第二轮答复"),
        )
        val compressed = listOf(
            msg("system", "${AgentContextCompactor.SUMMARY_PREFIX_ZH}\n第一轮已经看过屏幕"),
            msg("user", "第二轮"),
            msg("assistant", "第二轮答复"),
        )
        val ui = listOf(
            UserMessageUi("u1", "第一轮"),
            AgentMessageUi(
                "a1",
                "第一轮答复",
                usage = TokenUsageUi(inputTokens = 100, outputTokens = 20, cachedTokens = 40),
            ),
            UserMessageUi("u2", "第二轮"),
            AgentMessageUi(
                "a2",
                "第二轮答复",
                usage = TokenUsageUi(inputTokens = 50, outputTokens = 10, cachedTokens = 10),
            ),
        )
        val updated = AgentContextCompactionUi.applyMarker(
            messages = ui,
            originalHistory = original,
            compressedHistory = compressed,
            markerId = "c1",
        )
        val marker = updated.filterIsInstance<ContextCompactedMessageUi>().single()
        assertEquals(150L, marker.preservedUsage.inputTokens)
        assertEquals(30L, marker.preservedUsage.outputTokens)
        assertEquals(50L, marker.preservedUsage.cachedTokens)
        assertTrue(updated.filterIsInstance<AgentMessageUi>().all { it.usage == null })
        val usage = conversationTokenUsage(updated)
        assertEquals(150L, usage.inputTokens)
        assertEquals(30L, usage.outputTokens)
        assertEquals(50L, usage.cachedTokens)
    }

    @Test
    fun applyMarkerFoldsExistingMarkerUsageWhenCompressingAgain() {
        val original = listOf(
            msg("user", "第二轮"),
            msg("assistant", "第二轮答复"),
        )
        val compressed = listOf(
            msg("system", "${AgentContextCompactor.SUMMARY_PREFIX_ZH}\n继续压缩"),
            msg("user", "第二轮"),
            msg("assistant", "第二轮答复"),
        )
        val ui = listOf(
            ContextCompactedMessageUi(
                id = "old",
                compactedCount = 4,
                summary = "旧摘要",
                preservedUsage = ConversationTokenUsageUi(
                    inputTokens = 150,
                    outputTokens = 30,
                    cachedTokens = 50,
                ),
            ),
            UserMessageUi("u2", "第二轮"),
            AgentMessageUi(
                "a2",
                "第二轮答复",
                usage = TokenUsageUi(inputTokens = 80, outputTokens = 12, cachedTokens = 20),
            ),
        )
        val updated = AgentContextCompactionUi.applyMarker(
            messages = ui,
            originalHistory = original,
            compressedHistory = compressed,
            markerId = "new",
        )
        val marker = updated.filterIsInstance<ContextCompactedMessageUi>().single()
        assertEquals("new", marker.id)
        assertEquals(230L, marker.preservedUsage.inputTokens)
        assertEquals(42L, marker.preservedUsage.outputTokens)
        assertEquals(70L, marker.preservedUsage.cachedTokens)
        val usage = conversationTokenUsage(updated)
        assertEquals(230L, usage.inputTokens)
        assertEquals(42L, usage.outputTokens)
        assertEquals(70L, usage.cachedTokens)
    }

    private fun msg(role: String, content: String) =
        AgentModelClient.ConversationMessage(role = role, content = content)
}
