package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompactorTest {
    @Test
    fun keepRecentCountsUserTurnsInsteadOfRawApiRecords() {
        val history = (1..6).flatMap { turn(it) }
        assertEquals(24, history.size)
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(history, 10))

        val start = AgentContextCompactor.recentKeepStartIndex(history, 3)
        val kept = history.subList(start, history.size)
        assertEquals(listOf("u4", "u5", "u6"), kept.filter { it.role == "user" }.map { it.content })
        assertEquals(12, kept.size)
        assertEquals(6, kept.count { it.role == "assistant" })
    }

    @Test
    fun previousSummariesDoNotConsumeKeepRecentQuota() {
        val history = listOf(
            msg("system", "${AgentContextCompactor.SUMMARY_PREFIX}\nold"),
            msg("user", "u1"),
            msg("assistant", "a1"),
            msg("user", "u2"),
            msg("assistant", "a2"),
        )
        assertEquals(1, AgentContextCompactor.recentKeepStartIndex(history, 2))
        assertEquals(3, AgentContextCompactor.recentKeepStartIndex(history, 1))
    }

    @Test
    fun shouldCompressUsesUserTurnsNotRawHistorySize() {
        val history = (1..5).flatMap { turn(it) }
        assertEquals(20, history.size)
        assertFalse(
            AgentContextCompactor.shouldCompress(
                history = history,
                contextWindow = 1,
                keepRecentMessages = 10,
                thresholdPercent = 0,
            ),
        )
        assertTrue(
            AgentContextCompactor.shouldCompress(
                history = history,
                contextWindow = 1,
                keepRecentMessages = 2,
                thresholdPercent = 0,
            ),
        )
    }

    private fun turn(n: Int) = listOf(
        msg("user", "u$n"),
        msg("assistant", "", toolCallsJson = "[{\"id\":\"t$n\"}]"),
        msg("tool", "tool$n"),
        msg("assistant", "a$n"),
    )

    private fun msg(
        role: String,
        content: String,
        toolCallsJson: String = "",
    ) = AgentModelClient.ConversationMessage(
        role = role,
        content = content,
        toolCallsJson = toolCallsJson,
    )
}
