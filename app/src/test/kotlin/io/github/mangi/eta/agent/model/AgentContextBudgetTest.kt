package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextBudgetTest {

    @Test
    fun countTokensEnglish() {
        assertEquals(1, AgentContextBudget.countTokens("abc"))
        assertEquals(25, AgentContextBudget.countTokens("a".repeat(100)))
    }

    @Test
    fun countTokensChineseUsesOperitStyleEstimate() {
        val text = "一二三四五六七八九十"
        assertEquals(15, AgentContextBudget.countTokens(text))
        assertTrue(AgentContextBudget.countTokens(text) > text.length / 3)
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

    @Test
    fun countMessageDoesNotDoubleCountContentJson() {
        val text = "hello world"
        val json = """[{"type":"text","text":"$text"}]"""
        val withJson = AgentModelClient.ConversationMessage(
            role = "user",
            content = text,
            contentJson = json,
        )
        val jsonOnly = AgentModelClient.ConversationMessage(
            role = "user",
            content = "",
            contentJson = json,
        )
        assertEquals(
            AgentContextBudget.countMessage(jsonOnly),
            AgentContextBudget.countMessage(withJson),
        )
        assertTrue(
            AgentContextBudget.countMessage(withJson) <
                AgentContextBudget.countMessage(
                    AgentModelClient.ConversationMessage(role = "user", content = text),
                ) + AgentContextBudget.countTokens(json),
        )
    }

    @Test
    fun countMessageDoesNotTreatImageDataUrlAsText() {
        val payload = "A".repeat(80_000)
        val json = """[{"type":"text","text":"Latest observation image(s) returned by tool(s): read_image."},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$payload"}}]"""
        val message = AgentModelClient.ConversationMessage(
            role = "user",
            contentJson = json,
        )
        val counted = AgentContextBudget.countMessage(message)
        val naive = 3 + AgentContextBudget.countTokens(json)
        assertTrue("image message counted as text: $counted vs naive $naive", counted < 400)
        assertTrue(counted > 80)
        assertTrue(naive > 10_000)
    }

    @Test
    fun countMessageUsesMinimumTokensForPersistedImageFile() {
        val json = """[{"type":"text","text":"see photo"},{"type":"image_file","path":"/data/user/0/io.github.mangi.eta/cache/eta-chat-images/c1/a.jpg","mime":"image/jpeg","name":"a.jpg"}]"""
        val counted = AgentContextBudget.countMessage(
            AgentModelClient.ConversationMessage(role = "user", contentJson = json),
        )
        val textOnly = AgentContextBudget.countMessage(
            AgentModelClient.ConversationMessage(role = "user", content = "see photo"),
        )
        assertEquals(textOnly + 85, counted)
    }

    @Test
    fun countMessageStripsInlineDataUrlFromPlainContent() {
        val content = """ok=true, image=data:image/png;base64,${"B".repeat(40_000)}"""
        val counted = AgentContextBudget.countMessage(
            AgentModelClient.ConversationMessage(role = "tool", content = content),
        )
        assertTrue("plain data URL counted as text: $counted", counted < 40)
    }
}
