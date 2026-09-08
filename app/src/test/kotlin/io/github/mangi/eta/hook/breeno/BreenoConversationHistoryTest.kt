package io.github.mangi.eta.hook.breeno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreenoConversationHistoryTest {
    @Test
    fun buildsCurrentRoomHistoryAndExcludesCurrentQuestion() {
        val history = BreenoConversationHistory.build(
            entries = listOf(
                entry(1, "q1", "我叫小明"),
                entry(2, "q1", "你好，小明"),
                entry(1, "q2", "我叫什么？"),
            ),
            currentRecordId = "q2",
        )

        assertEquals(listOf("user", "assistant"), history.map { it.role })
        assertEquals(listOf("我叫小明", "你好，小明"), history.map { it.content })
    }

    @Test
    fun ignoresCardsAndMergesAdjacentMessagesWithTheSameRole() {
        val history = BreenoConversationHistory.build(
            entries = listOf(
                entry(2, "orphan", "没有对应问题"),
                entry(3, "card", "推荐卡片"),
                entry(1, "q1", "第一张图片"),
                entry(1, "q2", "补充说明"),
                entry(2, "q2", "收到"),
            ),
            currentRecordId = "",
        )

        assertEquals(listOf("user", "assistant"), history.map { it.role })
        assertEquals("第一张图片\n\n补充说明", history.first().content)
    }

    @Test
    fun excludesCurrentQuestionByContentWhenRecordIdIsUnavailable() {
        val history = BreenoConversationHistory.build(
            entries = listOf(
                entry(1, "", "重复问题"),
                entry(2, "", "较早回答"),
                entry(1, "", "重复问题"),
            ),
            currentRecordId = "",
            currentContent = "重复问题",
        )

        assertEquals(listOf("重复问题", "较早回答"), history.map { it.content })
    }

    @Test
    fun keepsRecentBoundedHistoryWithoutLeadingAssistant() {
        val entries = buildList {
            repeat(40) { index ->
                add(entry(1, "q$index", "问题$index"))
                add(entry(2, "q$index", "回答$index"))
            }
        }

        val history = BreenoConversationHistory.build(entries, currentRecordId = "")

        assertTrue(history.size <= 24)
        assertFalse(history.isEmpty())
        assertEquals("user", history.first().role)
        assertEquals("回答39", history.last().content)
    }

    private fun entry(
        chatType: Int,
        recordId: String,
        content: String,
    ) = BreenoConversationHistory.Entry(chatType, recordId, content)
}
