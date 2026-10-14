package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRecoveryContextTest {
    private fun active(): JSONObject = JSONArray().also(AgentRecoveryContext::append).getJSONObject(0)

    @Test fun recoveryAllowsIndependentClientDispatchButNotHostedReplay() {
        val messages = JSONArray()
        AgentRecoveryContext.append(messages)
        AgentRecoveryContext.append(messages)
        assertEquals(1, messages.length())
        assertTrue(AgentRecoveryContext.isActive(messages))
        val content = messages.getJSONObject(0).getString("content")
        assertTrue(content.contains("Provider-hosted tools"))
        assertTrue(content.contains("delegate_task for unrelated unfinished work"))
        assertFalse(content.contains("Hosted/remote tools and opaque remote continuation"))
        assertTrue(content.contains("unknown may already have executed"))
        assertTrue(content.contains("equivalent local/browser/HTTP operation"))
        assertTrue(content.contains("Never reuse a historical tool_call_id"))
    }

    @Test fun newRunPreservesSafetyAndMetadataWithoutMutatingOldRun() {
        val original = active().put("eta_turn_id", "old-turn").put("opaque", "preserve")
        val bytes = original.toString()
        val converted = AgentRecoveryContext.forNewRun(original)
        assertEquals(bytes, original.toString())
        assertTrue(AgentRecoveryContext.isActive(JSONArray().put(original)))
        assertFalse(AgentRecoveryContext.isActive(JSONArray().put(converted)))
        assertEquals("old-turn", converted.getString("eta_turn_id"))
        assertEquals("preserve", converted.getString("opaque"))
        val content = converted.getString("content")
        assertTrue(content.contains("current run's available tools and child configuration"))
        assertTrue(content.contains("Historical remote operations with unknown outcomes"))
        assertTrue(content.contains("do not retry, recreate"))
        assertTrue(content.contains("historical tool_call_id"))
        assertTrue(content.contains("Never claim an unknown operation succeeded"))
        assertSame(converted, AgentRecoveryContext.forNewRun(converted))
    }

    @Test fun persistedLegacyRecoveryDoesNotDisableNextRun() {
        val content = active().getString("content")
        val replacement = "Provider-hosted tools and opaque provider continuation are unavailable during this recovery. " +
            "New, independent client function calls, including delegate_task for unrelated unfinished work, " +
            "remain available under the current run configuration. This does not authorize replay, " +
            "replacement or automatic resumption of an uncertain historical task."
        val legacy = JSONObject().put("role", "system").put("content", content.replace(replacement,
            "Hosted/remote tools and opaque remote continuation are unavailable during this recovery."))
        assertTrue(AgentRecoveryContext.isActive(JSONArray().put(legacy)))
        assertFalse(AgentRecoveryContext.isActive(JSONArray().put(AgentRecoveryContext.forNewRun(legacy))))
    }

    @Test fun matchingOnlyThePrefixNeverChangesUserOrCustomSystemMessages() {
        val messages = listOf(
            JSONObject(active().toString()).put("role", "user"),
            JSONObject().put("role", "system").put("content", "[Eta safe recovery context v1] custom rule"),
            JSONObject().put("role", "assistant").put("content", "old result"),
            JSONObject().put("role", "tool").put("tool_call_id", "old-call").put("content", "committed"),
        )
        messages.forEach { assertSame(it, AgentRecoveryContext.forNewRun(it)) }
        assertFalse(AgentRecoveryContext.isActive(JSONArray(messages)))
    }

    @Test fun aNewTransportFailureCanActivateRecoveryAgain() {
        val messages = JSONArray().put(AgentRecoveryContext.forNewRun(active()))
        assertFalse(AgentRecoveryContext.isActive(messages))
        AgentRecoveryContext.append(messages)
        assertTrue(AgentRecoveryContext.isActive(messages))
        assertEquals(2, messages.length())
        AgentRecoveryContext.append(messages)
        assertEquals(2, messages.length())
    }
}
