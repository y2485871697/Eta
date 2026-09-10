package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageHistorySearchTest {
    private val roles = MessageSearchRoleLabels(
        user = "You",
        assistant = "Eta",
        thinking = "Thinking",
        tool = "Tool",
    )

    @Test
    fun returnsOldestMatchesFirst() {
        val older = AgentChatUiState(
            messages = listOf(
                UserMessageUi(id = "u1", content = "remember the apple pie"),
                AgentMessageUi(id = "a1", content = "Noted the apple request"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        val newer = AgentChatUiState(
            messages = listOf(
                UserMessageUi(id = "u2", content = "another apple later"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )

        val hits = searchConversationMessages(
            conversations = mapOf("old" to older, "new" to newer),
            titles = mapOf("old" to "Old chat", "new" to "New chat"),
            updatedAt = mapOf("old" to 1L, "new" to 9L),
            query = "apple",
            unnamedTitle = "Untitled",
            roleLabels = roles,
        )

        assertEquals(listOf("u1", "a1", "u2"), hits.map { it.messageId })
        assertEquals("Old chat", hits.first().conversationTitle)
        assertEquals("You", hits.first().roleLabel)
    }

    @Test
    fun ignoresBlankQuery() {
        val hits = searchConversationMessages(
            conversations = mapOf(
                "c" to AgentChatUiState(
                    messages = listOf(UserMessageUi(id = "u1", content = "hello")),
                    input = "",
                    isStreaming = false,
                    thinkingEnabled = false,
                ),
            ),
            titles = emptyMap(),
            updatedAt = emptyMap(),
            query = "   ",
            unnamedTitle = "Untitled",
            roleLabels = roles,
        )
        assertEquals(emptyList<MessageSearchHit>(), hits)
    }
}
