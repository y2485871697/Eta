package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesToolEnvelopeRecoveryTest {
    private val message = "basispoints tool transport code must contain one JSON client-tool envelope; " +
        "OfficeJS and multiple calls are unsupported (format=json_object; bytes=7941; " +
        "json_offset=589; json_failure=missing-separator)"
    private fun error() = JSONObject().put("code", "server_error").put("message", message)
    private fun failure() = AgentModelFailure.stream(error(), "rejected")
    private fun allowed(event: JSONObject): Boolean {
        val guard = ResponsesToolEnvelopeRecovery.DeliveryGuard()
        guard.observe(event)
        return (guard.protect(failure()) as AgentModelFailure).envelopeCorrectionAllowed
    }
    @Test fun exactSignatureAndFlattenedErrorAreRecognizedWithoutBroadMatching() {
        assertTrue(ResponsesToolEnvelopeRecovery.matches(error(), 500))
        assertTrue(ResponsesToolEnvelopeRecovery.matches(error().put("type", "error")))
        assertFalse(ResponsesToolEnvelopeRecovery.matches(error(), 401))
        assertFalse(ResponsesToolEnvelopeRecovery.matches(error().put("message", "quoted: $message")))
        assertFalse(ResponsesToolEnvelopeRecovery.matches(error().put("code", "authentication_error")))
    }
    @Test fun rawTextRefusalToolAndUnknownEventsForbidReplay() {
        for (event in listOf(
            JSONObject().put("type", "response.output_text.done").put("text", "already generated"),
            JSONObject().put("type", "response.refusal.done").put("refusal", "no"),
            JSONObject().put("type", "response.function_call_arguments.delta").put("delta", "{}"),
            JSONObject().put("type", "unknown.gateway.event")
        )) assertFalse(event.toString(), allowed(event))
    }
    @Test fun terminalSnapshotsAreCheckedBeforeProviderParsesFailure() {
        for (item in listOf(
            JSONObject().put("type", "message").put("content", JSONArray().put(
                JSONObject().put("type", "output_text").put("text", "done"))),
            JSONObject().put("type", "function_call"),
            JSONObject().put("type", "message"),
            JSONObject().put("type", "custom_tool_call")
        )) {
            val response = JSONObject().put("error", error()).put("output", JSONArray().put(item))
            assertFalse(allowed(JSONObject().put("type", "response.failed").put("response", response)))
            val http = AgentModelFailure.http(500, JSONObject().put("error", error())
                .put("response", response).toString())
            assertFalse(http.envelopeCorrectionAllowed)
        }
    }
    @Test fun cleanRejectedGenerationCanBeCorrectedButHostedRequestsCannot() {
        assertTrue(allowed(JSONObject().put("type", "error").put("error", error())))
        val guard = ResponsesToolEnvelopeRecovery.DeliveryGuard(false)
        assertFalse((guard.protect(failure()) as AgentModelFailure).envelopeCorrectionAllowed)
        assertTrue(AgentModelFailure.http(500, JSONObject().put("error", error()).toString()).envelopeCorrectionAllowed)
    }
    @Test fun correctionKeepsOriginalInstructionsAndDoesNotMutateHistory() {
        val request = ProviderRequest(AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "test", model = "model", systemPrompt = "original instructions"
        ), JSONArray().put(JSONObject().put("role", "user").put("content", "task")), JSONArray())
        val original = request.messages.toString()
        val corrected = ResponsesToolEnvelopeRecovery.corrected(request)
        assertEquals(original, request.messages.toString())
        val instructions = OpenAiRequestMessages.responsesInstructions(corrected.messages)
        assertTrue(instructions.contains("original instructions"))
        assertTrue(instructions.contains(ResponsesToolEnvelopeRecovery.CORRECTION))
        assertFalse(instructions.contains("CATALOG_NAME"))
    }
}
