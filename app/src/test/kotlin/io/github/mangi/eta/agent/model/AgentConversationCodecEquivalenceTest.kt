package io.github.mangi.eta.agent.model

import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** Frozen pre-optimization oracle; not executed by the non-build Python suite. */
class AgentConversationCodecEquivalenceTest {
    private val owner = AgentConversationCodec::class.java
    private fun field(name: String): Any = owner.getDeclaredField(name)
        .apply { isAccessible = true }.get(AgentConversationCodec)
    private val json = field("json") as Json
    private val COMPACTION_NOTICE = field("COMPACTION_NOTICE") as String
    private val encoder = owner.getDeclaredMethod(
        "encodeBounded", List::class.java, Int::class.javaPrimitiveType!!,
    ).apply { isAccessible = true }
    private val sanitizer = owner.getDeclaredMethod(
        "sanitizeMessage", AgentModelClient.ConversationMessage::class.java,
    ).apply { isAccessible = true }

    private fun sanitizeMessage(message: AgentModelClient.ConversationMessage) =
        sanitizer.invoke(AgentConversationCodec, message) as AgentModelClient.ConversationMessage

    private fun actual(messages: List<AgentModelClient.ConversationMessage>, limit: Int): String =
        encoder.invoke(AgentConversationCodec, messages, limit) as String

    private fun outcome(block: () -> String): Pair<String, String> = try {
        "ok" to block()
    } catch (error: Exception) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        if (cause !is IllegalArgumentException && cause !is IllegalStateException) throw cause
        cause.javaClass.name to cause.message.orEmpty()
    }

    @Test
    fun matchesLegacyAcrossEscapesBudgetsAndProtectedTurns() {
        val random = Random(9146)
        val special = listOf(34, 92, 10, 13, 9).map { it.toChar() }.joinToString("") + "中文😀"
        val roles = listOf("user", "assistant", "tool", "system")
        repeat(72) { sample ->
            val count = sample % 19
            val rows = List(count) { index ->
                AgentModelClient.ConversationMessage(
                    role = roles[random.nextInt(roles.size)],
                    content = special.repeat(random.nextInt(12)) + index,
                    reasoningContent = special.repeat(random.nextInt(4)),
                    turnId = when {
                        sample % 3 == 0 && index >= count / 2 -> "live"
                        sample % 5 == 0 && index % 4 == 0 -> "older"
                        else -> ""
                    },
                )
            }
            val full = json.encodeToString(rows.map(::sanitizeMessage)).length
            val limits = setOf(0, 1, 2, 16, 80, 256, 1_024, 16_384,
                (full - 1).coerceAtLeast(0), full, full + 1)
            limits.forEach { limit ->
                assertEquals("sample=$sample limit=$limit",
                    outcome { legacyEncodeBounded(rows, limit) },
                    outcome { actual(rows, limit) })
            }
        }
    }

    @Test
    fun latestProtectedTurnIsNeverSilentlyTruncated() {
        val rows = listOf(
            AgentModelClient.ConversationMessage("user", "old".repeat(12_000)),
            AgentModelClient.ConversationMessage("assistant", "protected".repeat(12_000), turnId = "live"),
        )
        for (limit in listOf(16_000, 1_000_000)) {
            assertEquals(outcome { legacyEncodeBounded(rows, limit) }, outcome { actual(rows, limit) })
        }
    }

    private fun legacyEncodeBounded(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
    ): String {
        val bounded = messages.map(::sanitizeMessage).toMutableList()
        var encoded = json.encodeToString(bounded)
        if (encoded.length <= maxChars) return encoded
        // Only the latest in-flight turn is protected. Older turns may still be dropped so a
        // large existing conversation cannot block saving a new one.
        val protectedTurnId = bounded.lastOrNull { it.turnId.isNotBlank() }?.turnId.orEmpty()
        val protectedCount = if (protectedTurnId.isBlank()) 0 else bounded.indexOfFirst { it.turnId == protectedTurnId }
            .let { start -> if (start < 0) 0 else bounded.size - start }
        if (protectedCount > 0) {
            val protectedEncoded = json.encodeToString(bounded.takeLast(protectedCount))
            require(protectedEncoded.length <= maxChars) {
                "受保护会话超过持久化容量上限；未截断或丢弃原消息，请压缩历史后重试"
            }
        }

        val notice = AgentModelClient.ConversationMessage(
            role = "system",
            content = COMPACTION_NOTICE,
        )
        val keep = protectedCount.coerceAtLeast(1)
        while (bounded.size > keep) {
            bounded.removeAt(0)
            while (bounded.size > keep && bounded.firstOrNull()?.role == "tool") bounded.removeAt(0)
            encoded = json.encodeToString(listOf(notice) + bounded)
            if (encoded.length <= maxChars) return encoded
        }
        if (protectedCount > 0) {
            encoded = json.encodeToString(bounded)
            if (encoded.length <= maxChars) return encoded
            error("受保护会话超过持久化容量上限；未截断或丢弃原消息，请压缩历史后重试")
        }

        val last = bounded.lastOrNull() ?: return "[]"
        val compacted = last.copy(
            content = last.content.take(maxChars / 4),
            contentJson = "",
            reasoningContent = last.reasoningContent.take(maxChars / 4),
            toolCallsJson = "",
            responsesReasoningJson = "",
        )
        return json.encodeToString(listOf(notice, compacted))
            .takeIf { it.length <= maxChars }
            ?: json.encodeToString(listOf(notice)).takeIf { it.length <= maxChars }
            ?: "[]"
    }

}
