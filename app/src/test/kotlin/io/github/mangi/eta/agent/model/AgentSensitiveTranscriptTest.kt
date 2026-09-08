package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSensitiveTranscriptTest {
    @Test
    fun memoryToolArgumentsAndResultsAreAlwaysSensitive() {
        assertTrue(AgentSensitiveToolPolicy.isSensitive("memory_get"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("memory_write"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("search_coloros_memories"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("search_notification_history"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("recent_app_activity"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("get_health_summary"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("search_personal_orders"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("mcp_server_search_deadbeef"))
    }

    @Test
    fun sensitiveToolArgumentsAndResultAreRemovedTogether() {
        val callId = "call_sensitive"
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject.NULL)
                    .put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("id", callId)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "set_setting")
                                        .put(
                                            "arguments",
                                            """{"namespace":"global","key":"demo","value":"敏感值"}""",
                                        ),
                                ),
                        ),
                    ),
            )
            .put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", callId)
                    .put("content", """{"ok":true,"password":"secret-value"}"""),
            )

        val encoded = AgentConversationCodec.transcript(
            messages = messages,
            startIndex = 0,
            sensitiveToolCallIds = setOf(callId),
        ).joinToString { it.content + it.toolCallsJson }

        assertFalse(encoded.contains("敏感值"))
        assertFalse(encoded.contains("secret-value"))
        assertTrue(encoded.contains("redacted"))
        assertTrue(encoded.contains("未写入持久会话"))
    }
}
