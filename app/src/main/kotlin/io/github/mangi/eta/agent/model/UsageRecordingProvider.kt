package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.repository.ModelUsageDelta
import io.github.mangi.eta.data.repository.UsageStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Accounting belongs to a provider request, not to a UI/display round or the selected model. */
internal class UsageRecordingProvider(
    private val delegate: AgentProviderClient,
    private val record: (ModelUsageDelta) -> Unit = { delta ->
        // Finish the received usage write even if the network/agent thread was interrupted.
        val interrupted = Thread.interrupted()
        try { runBlocking(Dispatchers.IO) { UsageStatsRepository.recordModelUsage(delta) } }
        finally { if (interrupted) Thread.currentThread().interrupt() }
    },
) : AgentProviderClient by delegate {
    override fun complete(request: ProviderRequest, runController: AgentRunController,
                          onEvent: (ProviderEvent) -> Unit): ProviderResponse {
        val requestId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        var latest: AgentTokenUsage? = null
        var saved: AgentTokenUsage? = null
        fun persist() {
            val usage = latest ?: return
            if (usage == saved || (usage.inputTokens == null && usage.outputTokens == null && usage.cachedTokens == null)) return
            val config = request.config
            // Statistics failure must not suppress a completed model response or its error.
            runCatching {
                record(ModelUsageDelta(
                    providerId = config.providerId, providerName = config.providerName,
                    modelId = config.model, modelDisplayName = config.modelDisplayName.ifBlank { config.model },
                    inputTokens = (usage.inputTokens ?: 0).toLong(),
                    outputTokens = (usage.outputTokens ?: 0).toLong(),
                    cachedTokens = (usage.cachedTokens ?: 0).toLong(),
                    conversationId = request.usageConversationId, requestId = requestId, atMillis = startedAt,
                ))
            }.onSuccess { saved = usage }
        }
        try {
            return delegate.complete(request, runController) { event ->
                if (event is ProviderEvent.Usage) {
                    val previous = latest
                    latest = event.usage.copy(
                        inputTokens = event.usage.inputTokens ?: previous?.inputTokens,
                        outputTokens = event.usage.outputTokens ?: previous?.outputTokens,
                        cachedTokens = event.usage.cachedTokens ?: previous?.cachedTokens,
                    )
                    try { onEvent(event) } finally { persist() }
                } else {
                    onEvent(event)
                }
            }
        } finally { persist() }
    }
}
