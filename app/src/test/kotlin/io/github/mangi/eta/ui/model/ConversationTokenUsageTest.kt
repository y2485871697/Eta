package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTokenUsageTest {
    @Test
    fun sumsAssistantUsageAndIgnoresOtherMessages() {
        val usage = conversationTokenUsage(
            listOf(
                UserMessageUi(id = "u1", content = "hello"),
                AgentMessageUi(
                    id = "a1",
                    content = "hi",
                    usage = TokenUsageUi(inputTokens = 100, outputTokens = 20, cachedTokens = 40),
                ),
                ThinkingMessageUi(id = "t1", content = "think", isStreaming = false),
                AgentMessageUi(
                    id = "a2",
                    content = "again",
                    usage = TokenUsageUi(inputTokens = 50, outputTokens = 10, cachedTokens = 10),
                ),
            ),
        )
        assertEquals(150L, usage.inputTokens)
        assertEquals(30L, usage.outputTokens)
        assertEquals(50L, usage.cachedTokens)
        assertEquals(180L, usage.totalTokens)
        assertEquals(50.0 / 150.0 * 100.0, usage.cachePercent!!, 0.0001)
        assertTrue(usage.hasUsage)
    }

    @Test
    fun emptyConversationHasNoUsageOrCacheRate() {
        val usage = conversationTokenUsage(emptyList())
        assertFalse(usage.hasUsage)
        assertNull(usage.cachePercent)
        assertEquals(0L, usage.totalTokens)
    }

    @Test
    fun includesPreservedUsageFromCompactedMarker() {
        val usage = conversationTokenUsage(
            listOf(
                ContextCompactedMessageUi(
                    id = "c1",
                    compactedCount = 8,
                    summary = "旧对话",
                    preservedUsage = ConversationTokenUsageUi(
                        inputTokens = 1200,
                        outputTokens = 80,
                        cachedTokens = 400,
                    ),
                ),
                AgentMessageUi(
                    id = "a1",
                    content = "新答复",
                    usage = TokenUsageUi(inputTokens = 50, outputTokens = 10, cachedTokens = 5),
                ),
            ),
        )
        assertEquals(1250L, usage.inputTokens)
        assertEquals(90L, usage.outputTokens)
        assertEquals(405L, usage.cachedTokens)
        assertTrue(usage.hasUsage)
    }
}
