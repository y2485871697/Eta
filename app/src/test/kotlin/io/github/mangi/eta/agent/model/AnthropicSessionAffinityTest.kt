package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ProviderTypes
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnthropicSessionAffinityTest {
    private val config = AgentModelClient.ModelConfig(
        providerType = ProviderTypes.ANTHROPIC,
        baseUrl = "https://example.invalid", apiKey = "test", model = "claude-test", systemPrompt = "",
    )
    private fun build(sessionId: String, body: List<CustomBody> = emptyList()): JSONObject =
        AnthropicMessagesProvider.buildRequestJson(config.copy(customBody = body),
            JSONArray().put(JSONObject().put("role", "user").put("content", "hello")), JSONArray(), sessionId)
    private fun custom(value: String) = CustomBody("metadata", Json.parseToJsonElement(value))
    private fun userId(body: JSONObject): String = body.getJSONObject("metadata").getString("user_id")

    @Test fun freshBuildersKeepTheSameAnonymousAnchorForTheSameConversation() {
        val before = userId(build("conv-restore"))
        val restored = userId(build(String("conv-restore".toCharArray())))
        assertEquals(before, restored)
        assertTrue(before.matches(Regex("eta-session-[0-9a-f]{64}")))
        assertFalse(before.contains("conv-restore"))
        assertTrue(before.length <= 256)
    }

    @Test fun differentConversationsDoNotShareAnAnchor() {
        assertNotEquals(userId(build("conv-a")), userId(build("conv-b")))
    }

    @Test fun blankSessionNeverCreatesAPerRequestRandomAnchor() {
        assertFalse(build("").has("metadata"))
        assertFalse(build("   ").has("metadata"))
    }

    @Test fun explicitCustomUserIdWinsEvenWhenEmptyOrNull() {
        assertEquals("caller", userId(build("conv-a", listOf(custom("{\"user_id\":\"caller\"}")))))
        assertEquals("", userId(build("conv-a", listOf(custom("{\"user_id\":\"\"}")))))
        assertTrue(build("conv-a", listOf(custom("{\"user_id\":null}")))
            .getJSONObject("metadata").isNull("user_id"))
    }

    @Test fun metadataMembersWithoutUserIdAreNotDropped() {
        val request = build("conv-a", listOf(custom("{\"custom\":\"kept\"}")))
        assertEquals("kept", request.getJSONObject("metadata").getString("custom"))
        assertEquals(userId(build("conv-a")), userId(request))
    }

    @Test fun explicitNonObjectMetadataIsNotRewritten() {
        for (raw in listOf("null", "[]", "\"caller-owned\"")) {
            val request = build("conv-a", listOf(custom(raw)))
            assertEquals(JSONObject("{\"metadata\":$raw}").toString(),
                JSONObject().put("metadata", request.get("metadata")).toString())
        }
    }

    @Test fun defaultAffinityIsIdempotentAndDoesNotTouchThePrompt() {
        val request = JSONObject().put("messages", JSONArray().put("unchanged"))
        val prompt = request.getJSONArray("messages").toString()
        AnthropicSessionAffinity.applyDefault(request, "private-session")
        val first = request.toString()
        AnthropicSessionAffinity.applyDefault(request, "private-session")
        assertEquals(first, request.toString())
        assertEquals(prompt, request.getJSONArray("messages").toString())
    }

    @Test fun nonsensitiveHistoryStorageRoundTripKeepsTheWireAndAnchorIdentical() {
        val history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "old prompt", turnId = "turn-a"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "old answer", turnId = "turn-a"),
        )
        val restored = AgentConversationCodec.decodeTranscript(AgentConversationCodec.encodeTranscriptForTransfer(history))
        fun wire(source: List<AgentModelClient.ConversationMessage>): JSONObject {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "same instructions"))
            source.forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
            messages.put(JSONObject().put("role", "user").put("content", "next prompt"))
            return AnthropicMessagesProvider.buildRequestJson(config, messages, JSONArray(), "conv-restored")
        }
        assertEquals(wire(history).toString(), wire(restored).toString())
    }
}
