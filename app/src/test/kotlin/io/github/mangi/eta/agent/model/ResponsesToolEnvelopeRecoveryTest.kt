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
    @Test fun screenshotUnderscoreAndDelimiterVariantsAreRecognizedNarrowly() {
        for (separator in listOf("-", "_")) for (semicolons in listOf(true, false)) {
            val text = message.replace("missing-separator", "missing${separator}separator")
                .let { if (semicolons) it else it.replace("; bytes=", " bytes=").replace("; json_offset=", " json_offset=").replace("; json_failure=", " json_failure=") }
            val error = error().put("message", text)
            assertTrue(text, ResponsesToolEnvelopeRecovery.matches(error, 500))
            assertFalse(ResponsesToolEnvelopeRecovery.matches(error, 403))
            assertFalse(ResponsesToolEnvelopeRecovery.matches(error().put("message", text + " tool executed"), 500))
            assertFalse(ResponsesToolEnvelopeRecovery.matches(error().put("message", text.replace("json_object", "javascript")), 500))
        }
    }

    @Test fun restrictionIsScopedToEndpointModelAndNeverMutatesOriginalRequest() {
        val request = ProviderRequest(AgentModelClient.ModelConfig(baseUrl = "https://unique-restricted.invalid/v1", apiKey = "test", model = "m", systemPrompt = "original"), JSONArray(), JSONArray())
        assertSame(request, ResponsesToolEnvelopeRecovery.prepare(request))
        ResponsesToolEnvelopeRecovery.rememberRestriction(request)
        val prepared = ResponsesToolEnvelopeRecovery.prepare(request)
        assertTrue(prepared.singleToolCall)
        assertFalse(request.singleToolCall)
        assertEquals(0, request.messages.length())
        assertTrue(OpenAiRequestMessages.responsesInstructions(prepared.messages).contains("original"))
        val otherModel = request.copy(config = request.config.copy(model = "other"))
        assertSame(otherModel, ResponsesToolEnvelopeRecovery.prepare(otherModel))
        val otherHost = request.copy(config = request.config.copy(baseUrl = "https://another.invalid/v1"))
        assertSame(otherHost, ResponsesToolEnvelopeRecovery.prepare(otherHost))
    }

    @Test fun correctedWireDisablesParallelCallsAfterCustomBodyAndCodexDefaults() {
        for (url in listOf("https://example.invalid/v1", "https://chatgpt.com/backend-api/codex")) {
            val request = ProviderRequest(AgentModelClient.ModelConfig(baseUrl = url, apiKey = "test", model = "test", systemPrompt = "original", extraBodyJson = "{\"parallel_tool_calls\":true}"), JSONArray(),
                JSONArray().put(AgentToolSchema.function("read_file", "read", JSONObject().put("type", "object"))))
            val corrected = ResponsesToolEnvelopeRecovery.corrected(request)
            val body = OpenAiResponsesProvider.buildRequestJson(corrected.config, corrected.messages, corrected.tools, corrected.sessionId, corrected.singleToolCall)
            assertFalse(body.getBoolean("parallel_tool_calls"))
            assertTrue(body.getString("instructions").contains(ResponsesToolEnvelopeRecovery.CORRECTION))
            assertFalse(request.singleToolCall)
        }
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
