package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompactorTest {
    @Test
    fun keepRecentCountsUserTurnsNotBubbles() {
        val history = (1..6).flatMap { n ->
            listOf(msg("user", "u$n"), msg("assistant", "a$n"))
        }
        assertEquals(12, history.size)
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(history, 6))
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(history, 20))

        val start = AgentContextCompactor.recentKeepStartIndex(history, 2)
        val kept = history.subList(start, history.size)
        assertEquals(listOf("u5", "a5", "u6", "a6"), kept.map { it.content })
    }

    @Test
    fun toolRecordsDoNotConsumeKeepRecentQuota() {
        val history = (1..4).flatMap { turn(it) }
        assertEquals(16, history.size)

        val start = AgentContextCompactor.recentKeepStartIndex(history, 2)
        val kept = history.subList(start, history.size)
        val users = kept.filter { it.role == "user" }.map { it.content }
        assertEquals(listOf("u3", "u4"), users)
        assertTrue(kept.any { it.role == "tool" })
        assertTrue(kept.any { it.content == "a3" })
        assertTrue(kept.any { it.content == "a4" })
        assertFalse(kept.any { it.content == "u2" })
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
        assertEquals(3, AgentContextCompactor.recentKeepStartIndex(history, 2))
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(history, 4))
    }

    @Test
    fun keepRecentIncludesTheUserTurnAndItsToolLoop() {
        val history = turn(1) + turn(2)
        val start = AgentContextCompactor.recentKeepStartIndex(history, 1)
        val kept = history.subList(start, history.size)
        assertEquals("u2", kept.first().content)
        assertEquals("a2", kept.last().content)
        assertTrue(kept.any { it.role == "tool" })
        assertFalse(kept.any { it.content == "u1" })
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

    @Test
    fun shouldCompressPrefersEstimatedTokensOverLocalHistorySum() {
        val history = (1..6).flatMap { turn(it) }
        assertFalse(
            AgentContextCompactor.shouldCompress(
                history = history,
                contextWindow = 500_000,
                keepRecentMessages = 2,
                thresholdPercent = 80,
                estimatedTokens = 262_556,
            ),
        )
        assertTrue(
            AgentContextCompactor.shouldCompress(
                history = history,
                contextWindow = 500_000,
                keepRecentMessages = 2,
                thresholdPercent = 80,
                estimatedTokens = 442_000,
            ),
        )
    }

}
