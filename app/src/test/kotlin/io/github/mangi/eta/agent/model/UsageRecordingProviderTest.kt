package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.repository.ModelUsageDelta
import io.github.mangi.eta.data.repository.applyModelUsageDelta
import io.github.mangi.eta.data.repository.decodeModelUsageSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageRecordingProviderTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "unused", model = "actual-model",
        systemPrompt = "", providerId = "actual-provider", providerName = "Actual", modelDisplayName = "Actual model")
    private val request get() = ProviderRequest(config, JSONArray(), JSONArray(), "conversation")
    private fun provider(block: ((ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fake"
        override val capabilities = ProviderCapabilities(EndpointKind.RESPONSES, true, true, true, true, false, true)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(onEvent)
    }
    private fun answer() = ProviderResponse(JSONObject().put("content", "done").put("finish_reason", "stop"))

    @Test fun identicalUsageEventsAreIdempotentAndNewInvocationsAccumulate() {
        var raw = ""
        val records = mutableListOf<ModelUsageDelta>()
        val decorated = UsageRecordingProvider(provider { emit ->
            repeat(2) { emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100, outputTokens = 20, cachedTokens = 80))) }
            answer()
        }) { records += it; raw = applyModelUsageDelta(raw, it) }
        repeat(2) { decorated.complete(request, AgentRunController()) {} }
        assertEquals(2, records.size)
        assertNotEquals(records[0].requestId, records[1].requestId)
        assertEquals("actual-model", records[0].modelId)
        assertEquals("actual-provider", records[0].providerId)
        assertEquals(200L, decodeModelUsageSnapshot(raw).totalInputTokens)
    }

    @Test fun reportedUsageSurvivesFailureAndPartialSnapshotsKeepInput() {
        var raw = ""
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100, cachedTokens = 80)))
            emit(ProviderEvent.Usage(AgentTokenUsage(outputTokens = 30)))
            throw IllegalStateException("stream interrupted")
        }) { raw = applyModelUsageDelta(raw, it) }
        assertThrows(IllegalStateException::class.java) { decorated.complete(request, AgentRunController()) {} }
        val result = decodeModelUsageSnapshot(raw)
        assertEquals(100L, result.totalInputTokens)
        assertEquals(30L, result.totalOutputTokens)
        assertEquals(80L, result.totalCachedTokens)
        assertEquals(1, result.providers.single().models.single().events.size)
    }

    @Test fun missingUsageIsNotEstimatedAndStorageFailureDoesNotBreakResponse() {
        val unreported = UsageRecordingProvider(provider { answer() }) { fail("No usage was returned") }
        assertEquals("done", unreported.complete(request, AgentRunController()) {}.assistantMessage.getString("content"))
        val failingStore = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 10))); answer()
        }) { error("storage failure") }
        assertEquals("done", failingStore.complete(request, AgentRunController()) {}.assistantMessage.getString("content"))
    }

    @Test fun failingUiConsumerCannotDropReceivedUsage() {
        val records = mutableListOf<ModelUsageDelta>()
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100))); answer()
        }) { records += it }
        assertThrows(IllegalStateException::class.java) {
            decorated.complete(request, AgentRunController()) { error("UI disconnected") }
        }
        assertEquals(1, records.size)
    }
    @Test fun usageIsDeliveredBeforeAccountingAndCallbackFailureIsPreserved() {
        val order = mutableListOf<String>()
        val original = IllegalStateException("consumer stopped")
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100)))
            answer()
        }) { order += "record" }
        val thrown = assertThrows(IllegalStateException::class.java) {
            decorated.complete(request, AgentRunController()) {
                order += "event"
                assertTrue(order == listOf("event"))
                throw original
            }
        }
        assertSame(original, thrown)
        assertEquals(listOf("event", "record"), order)
    }

    @Test fun accountingUsesConversationOwnerNotNetworkSession() {
        val records = mutableListOf<ModelUsageDelta>()
        val decorated = UsageRecordingProvider(provider { emit ->
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100))); answer()
        }) { records += it }
        decorated.complete(request.copy(sessionId = "isolated-title-session", usageConversationId = "owner"), AgentRunController()) {}
        assertEquals("owner", records.single().conversationId)
    }
}
