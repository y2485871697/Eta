package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextBudgetTest {

    @Test
    fun countTokensEnglish() {
        assertEquals(1, AgentContextBudget.countTokens("abc"))
        assertTrue(AgentContextBudget.countTokens("a".repeat(100)) >= 30)
    }

    @Test
    fun countTokensChinese() {
        // 中文 codepoint / 3
        val text = "一二三四五六七八九十"
        assertEquals(4, AgentContextBudget.countTokens(text))
    }

    @Test
    fun countImageTokens() {
        assertEquals(85, AgentContextBudget.countImageTokens(32, 32))
        // 1024x1024 -> ceil(1024/32)=32 -> 32*32=1024 tokens
        assertEquals(1024, AgentContextBudget.countImageTokens(1024, 1024))
    }

    @Test
    fun trimHistoryKeepsRecent() {
        val history = List(100) { index ->
            AgentModelClient.ConversationMessage(
                role = if (index % 2 == 0) "user" else "assistant",
                content = "message $index",
            )
        }
        val budget = AgentContextBudget.countMessage(history.last()) * 10
        val trimmed = AgentContextBudget.trimHistory(history, budget)
        assertTrue(trimmed.size in 1..10)
        assertEquals("message 99", trimmed.last().content)
    }

    @Test
    fun trimHistoryAlwaysKeepsLastMessage() {
        val history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "a".repeat(100_000)),
            AgentModelClient.ConversationMessage(role = "assistant", content = "reply"),
        )
        val trimmed = AgentContextBudget.trimHistory(history, 1)
        assertEquals(1, trimmed.size)
        assertEquals("reply", trimmed.first().content)
    }

    @Test
    fun countCurrentTurnIncludesMessageOverhead() {
        val prompt = "hello"
        val image = AgentModelClient.ModelImage(
            reference = "data:image/png;base64,AA",
            mimeType = "image/png",
            bytes = 12_000,
            source = "user_attach",
        )
        val expected = 3 + AgentContextBudget.countTokens(prompt) + AgentContextBudget.countImageTokens(image)
        assertEquals(expected, AgentContextBudget.countCurrentTurn(prompt, listOf(image)))
        assertEquals(0, AgentContextBudget.countTokens(""))
    }

    @Test
    fun historyBudgetRespectsContextWindow() {
        val budget = AgentContextBudget.historyBudget(
            contextWindow = 128_000,
            prompt = "hello",
            images = emptyList(),
        )
        assertTrue(budget in 110_000..120_000)
    }
}
