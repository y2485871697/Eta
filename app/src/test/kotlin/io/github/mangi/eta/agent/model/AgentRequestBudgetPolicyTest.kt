package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class AgentRequestBudgetPolicyTest {
    @Test fun onlyInitialAndPostCompactionBoundariesMayEstimate() {
        val policy = AgentRequestBudgetPolicy()
        var estimates = 0
        val estimate = { estimates++; 12000 }
        assertTrue(policy.consumeLocalBoundary())
        assertEquals(12000, policy.tokens(null, estimate))
        policy.requestStarted()
        assertFalse(policy.consumeLocalBoundary())
        assertEquals(8000, policy.tokens(8000, estimate))
        assertEquals(0, policy.tokens(null, estimate))
        policy.requestStarted() // Tool loop, retry, and steering are not compactions.
        assertEquals(0, policy.tokens(null, estimate))
        assertEquals(1, estimates)
        policy.contextReplaced()
        assertTrue(policy.consumeLocalBoundary())
        assertEquals(12000, policy.tokens(null, estimate))
        policy.requestStarted()
        assertEquals(2000, policy.tokens(2000, estimate))
        assertEquals(0, policy.tokens(null, estimate))
        assertEquals(2, estimates)
    }

    @Test fun estimatesIncludeMessagesSystemInstructionsAndTools() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "instructions".repeat(500)))
            .put(AgentConversationCodec.userTextMessage("task"))
        val tools = JSONArray().put(AgentToolSchema.function("read_file", "description".repeat(500), JSONObject().put("type", "object")))
        val expected = AgentContextBudget.estimate(messages) + AgentContextBudget.countTokens(tools.toString())
        val policy = AgentRequestBudgetPolicy()
        assertTrue(expected > AgentContextBudget.estimate(messages))
        assertEquals(expected, policy.tokens(null) { expected })
        policy.requestStarted()
        assertEquals(25, policy.tokens(25) { error("No local estimate after request") })
        policy.contextReplaced()
        assertEquals(expected, policy.tokens(null) { expected })
    }
}
