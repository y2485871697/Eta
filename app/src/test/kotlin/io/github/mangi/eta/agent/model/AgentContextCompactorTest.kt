package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        assertEquals(1, AgentContextCompactor.recentKeepStartIndex(history, 2))
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
    fun shouldCompressUsesTokenTailRegardlessOfLegacyKeepCount() {
        val history = (1..5).flatMap { turn(it) }
        assertEquals(20, history.size)
        assertTrue(
            AgentContextCompactor.shouldCompress(
                history = history,
                contextWindow = 1,
                keepRecentMessages = 10,
                thresholdPercent = 0,
                estimatedTokens = 1,
            ),
        )
        assertTrue(
            AgentContextCompactor.shouldCompress(
                history = history,
                contextWindow = 1,
                keepRecentMessages = 2,
                thresholdPercent = 0,
                estimatedTokens = 1,
            ),
        )
    }

    @Test
    fun keepZeroRetainsLastUserNotTheLastToolBatch() {
        val history = listOf(
            msg("user", "u1"),
            msg("assistant", "a1"),
            msg("tool", "huge-tool-1"),
            msg("tool", "huge-tool-2"),
            msg("user", "u2"),
            msg("assistant", "a2"),
            msg("tool", "huge-tool-3"),
            msg("tool", "huge-tool-4"),
            msg("tool", "huge-tool-5"),
        )
        val start = AgentContextCompactor.recentKeepStartIndex(history, 0)
        val kept = history.subList(start, history.size)
        assertEquals("u2", kept.first().content)
        assertTrue(kept.any { it.content == "huge-tool-5" })
        assertFalse(kept.any { it.content == "u1" })
        assertFalse(kept.any { it.content == "huge-tool-1" })
    }

    @Test
    fun keepRecentFollowsContinueTaskDefault() {
        assertEquals(0, AgentContextCompactor.keepRecentFor())
    }

    @Test
    fun keepZeroIsAllowedByDefault() {
        assertEquals(0, AgentContextCompactor.coerceKeepRecent(0))
    }

    @Test
    fun keepZeroIsAllowedForContinueTask() {
        val history = (1..3).flatMap { turn(it) }
        assertEquals(
            0,
            AgentContextCompactor.coerceKeepRecent(0),
        )
        val cut = AgentContextCompactor.recentKeepStartIndex(history, 0)
        assertTrue(cut > 0)
        assertTrue(cut < history.size)
        assertEquals(history.indexOfLast { it.role == "user" }, cut)
        assertEquals(history.indexOfLast { it.role == "user" }, AgentContextCompactor.recentKeepStartIndex(history, 1))
    }

    @Test
    fun keepOnePreservesLastUserTurn() {
        val history = (1..3).flatMap { turn(it) }
        val start = AgentContextCompactor.recentKeepStartIndex(history, 1)
        assertEquals(history.indexOfLast { it.role == "user" }, start)
    }

    private fun turn(n: Int) = listOf(
        msg("user", "u$n"),
        msg("assistant", "", toolCallsJson = "[{\"id\":\"t$n\"}]"),
        msg("tool", "tool$n", toolCallId = "t$n"),
        msg("assistant", "a$n"),
    )

    private fun msg(
        role: String,
        content: String,
        toolCallsJson: String = "",
        toolCallId: String = "",
    ) = AgentModelClient.ConversationMessage(
        role = role,
        content = content,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId,
    )

    @Test
    fun shouldCompressPrefersEstimatedTokensOverLocalHistorySum() {
        val history = (1..6).flatMap { turn(it) }
        assertFalse(
            AgentContextCompactor.shouldCompress(
                history = history,
                contextWindow = 500_000,
                keepRecentMessages = 2,
                thresholdPercent = 90,
                estimatedTokens = 262_556,
            ),
        )
        assertTrue(
            AgentContextCompactor.shouldCompress(
                history = history,
                contextWindow = 500_000,
                keepRecentMessages = 2,
                thresholdPercent = 90,
                estimatedTokens = 451_000,
            ),
        )
    }

    @Test
    fun rebuildConversationKeepsSystemPrefix() {
        val messages = org.json.JSONArray()
            .put(org.json.JSONObject().put("role", "system").put("content", "rules"))
            .put(org.json.JSONObject().put("role", "user").put("content", "old"))
            .put(org.json.JSONObject().put("role", "assistant").put("content", "old-a"))
        AgentContextCompactor.rebuildConversation(
            messages,
            systemCount = 1,
            history = listOf(
                msg("system", AgentContextCompactor.SUMMARY_PREFIX_ZH + "\n摘要"),
                msg("user", "new"),
            ),
        )
        assertEquals(3, messages.length())
        assertEquals("rules", messages.getJSONObject(0).getString("content"))
        assertEquals("new", messages.getJSONObject(2).getString("content"))
    }


    @Test
    fun steeringSupplementDoesNotConsumeKeepRecentQuota() {
        val history = listOf(
            msg("user", "u1"),
            msg("assistant", "a1"),
            msg("user", "u2"),
            msg("assistant", "a2-partial"),
            msg("user", AgentContextCompactor.steeringUserContent("再加上这个")),
            msg("assistant", "a2-continue"),
        )
        val start = AgentContextCompactor.recentKeepStartIndex(history, 1)
        val kept = history.subList(start, history.size)
        assertEquals("u2", kept.first().content)
        assertEquals("a2-continue", kept.last().content)
        assertTrue(kept.any { AgentContextCompactor.isSteeringUserMessage(it) })
        assertFalse(kept.any { it.content == "u1" })
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(history, 2))
    }

    @Test
    fun rawAppendWithoutPrefixConsumesKeepRecentQuota() {
        val history = listOf(
            msg("user", "u1"),
            msg("assistant", "a1"),
            msg("user", "u2"),
            msg("assistant", "a2-partial"),
            msg("user", "还有没"),
        )
        val start = AgentContextCompactor.recentKeepStartIndex(history, 1)
        assertEquals(history.indexOfLast { it.content == "还有没" }, start)
        assertFalse(history.subList(start, history.size).any { it.content == "u2" })
    }

    @Test
    fun autoCompressRequiresConfiguredContextWindow() {
        assertFalse(AgentContextCompactor.autoCompressEnabled(true, null))
        assertFalse(AgentContextCompactor.autoCompressEnabled(true, 0))
        assertFalse(AgentContextCompactor.autoCompressEnabled(false, 128_000))
        assertTrue(AgentContextCompactor.autoCompressEnabled(true, 128_000))
        assertNull(AgentContextCompactor.configuredContextWindow(null))
        assertNull(AgentContextCompactor.configuredContextWindow(0))
        assertEquals(32_000, AgentContextCompactor.configuredContextWindow(32_000))
    }

    @Test
    fun shouldCompressRejectsNonPositiveWindow() {
        val history = (1..3).flatMap { turn(it) }
        assertFalse(
            AgentContextCompactor.shouldCompress(
                history = history,
                contextWindow = 0,
                keepRecentMessages = 1,
                thresholdPercent = 0,
            ),
        )
    }
}
