package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.text.NumberFormat
import java.util.Locale

@Immutable
internal data class AgentModelPickerUiState(
    val providerGroups: List<AgentModelProviderGroupUi> = emptyList(),
    val selectedModel: AgentModelOptionUi? = null,
    val isChanging: Boolean = false,
)

@Immutable
internal data class AgentModelProviderGroupUi(
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val models: List<AgentModelOptionUi>,
)

@Immutable
internal data class AgentModelOptionUi(
    val id: String,
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Int?,
    val preferredReasoningEffort: ReasoningEffort? = null,
    val supportsVision: Boolean = true,
    val supportsImageGeneration: Boolean = false,
    val supportsVideo: Boolean = false,
    val supportsVideoGeneration: Boolean = false,
)

@Immutable
internal data class AgentContextUsageUi(
    val contextTokens: Int?,
    val contextWindow: Int?,
) {
    val progress: Float?
        get() = contextUsageProgress(contextTokens, contextWindow)
}

internal object AgentModelPickerProjector {
    fun project(
        providers: List<ProviderSetting>,
        selectedProviderId: String?,
        selectedModelId: String?,
        includeSpeechModels: Boolean = false,
        speechOnly: Boolean = false,
    ): AgentModelPickerUiState {
        val enabledProviders = providers
            .asSequence()
            .filter(ProviderSetting::isEnabled)
            .filter { !speechOnly || SpeechSynthesisModels.isReadAloudProvider(it) }
            .sortedBy(ProviderSetting::sortOrder)
            .toList()
        val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
        val selectedModel = selectedProvider?.let { provider ->
            listedModels(provider, includeSpeechModels || speechOnly, speechOnly)
                .firstOrNull { it.id == selectedModelId && it.isEnabled }
                ?.let { model -> provider.toOption(model) }
        }
        val groups = enabledProviders
            .asSequence()
            .filter { it.apiKey.isNotBlank() }
            .filter { includeSpeechModels || speechOnly || !SpeechSynthesisModels.isSpeechOnlyProvider(it) }
            .mapNotNull { provider ->
                val sourceType = ProviderSourceRegistry.resolve(provider)
                val models = listedModels(provider, includeSpeechModels || speechOnly, speechOnly)
                    .asSequence()
                    .filter { it.isEnabled }
                    .map { model -> provider.toOption(model) }
                    .toList()
                models.takeIf(List<*>::isNotEmpty)?.let {
                    AgentModelProviderGroupUi(
                        providerId = provider.id,
                        providerName = provider.name,
                        providerSourceType = sourceType,
                        models = models,
                    )
                }
            }
            .toList()
        return AgentModelPickerUiState(
            providerGroups = groups,
            selectedModel = selectedModel,
        )
    }


    private fun listedModels(provider: ProviderSetting, includeSpeechModels: Boolean, speechOnly: Boolean): List<Model> {
        val seen = HashSet<String>()
        return SpeechSynthesisModels.mergeCatalog(provider).filter { model ->
            if (!model.isEnabled) return@filter false
            if (speechOnly && !SpeechSynthesisModels.isReadAloudModel(model, provider)) return@filter false
            if (!includeSpeechModels && model.supportsSpeechSynthesis) return@filter false
            seen.add(model.modelId.lowercase())
        }
    }

    private fun ProviderSetting.toOption(model: Model): AgentModelOptionUi =
        AgentModelOptionUi(
            id = model.id,
            providerId = id,
            providerName = name,
            providerSourceType = ProviderSourceRegistry.resolve(this),
            modelId = model.modelId,
            displayName = model.displayName.ifBlank { model.modelId },
            contextWindow = model.effectiveContextWindow,
            preferredReasoningEffort = model.preferredReasoningEffort,
            supportsVision = model.supportsVision,
            supportsImageGeneration = model.supportsImageGeneration,
            supportsVideo = model.supportsVideo,
            supportsVideoGeneration = model.supportsVideoGeneration,
        )
}

internal fun defaultExpandedModelProviderIds(selectedModel: AgentModelOptionUi?): Set<String> =
    selectedModel?.providerId?.let(::setOf).orEmpty()

internal fun latestContextUsage(
    messages: List<AgentChatMessageUi>,
    selectedModel: AgentModelOptionUi?,
): AgentContextUsageUi = AgentContextUsageUi(
    contextTokens = latestBilledContextTokens(messages),
    contextWindow = selectedModel?.contextWindow,
)

/**
 * Reads a bill only within a still-valid message context. Callers must not use
 * message bills to restore occupancy after model/history invalidation.
 * A compaction marker's baseline is local metadata, never cloud occupancy.
 */
internal fun latestBilledContextTokens(messages: List<AgentChatMessageUi>): Int? {
    val compactIndex = messages.indexOfLast { it is ContextCompactedMessageUi }
    val billedIndex = messages.indexOfLast { it is AgentMessageUi }
    if (billedIndex <= compactIndex) return null
    val message = messages[billedIndex] as AgentMessageUi
    // Round numbers restart each run; only the run event reducer may filter stale rounds.
    // Do not skip a missing/output-only bill and resurrect an older prompt.
    return windowTokensFromUsage(message.usage)
}

internal fun countUnbilledTail(
    messages: List<AgentChatMessageUi>,
    startIndex: Int,
    resumeRound: Int = 0,
    billedIndex: Int = -1,
): Int {
    var tail = 0
    for (index in startIndex until messages.size) {
        val message = messages[index]
        if (resumeRound > 0) {
            val round = messageRoundFromId(message.id)
            if (round == null || round < resumeRound) continue
        }
        if (index == billedIndex && message is AgentMessageUi) {
            tail += countAssistantLiveTokens(message)
            continue
        }
        if (index < billedIndex && message is UserMessageUi) continue
        tail += countLiveMessageTokens(message)
    }
    return tail
}

internal fun countUncommittedLiveTokens(messages: List<AgentChatMessageUi>): Int {
    val lastCompletedAssistant = messages.indices.lastOrNull { index ->
        val message = messages[index]
        message is AgentMessageUi && !message.isStreaming
    } ?: -1
    var tail = 0
    for (index in lastCompletedAssistant + 1 until messages.size) {
        val message = messages[index]
        if (message is UserMessageUi) continue
        tail += countLiveMessageTokens(message)
    }
    return tail
}

internal fun countLiveMessageTokens(message: AgentChatMessageUi): Int =
    when (message) {
        is UserMessageUi -> {
            if (message.content.isBlank() && message.images.isEmpty()) {
                0
            } else {
                AgentContextBudget.countCurrentTurn(message.content, emptyList()) +
                    AgentContextBudget.countStoredImages(message.images.size)
            }
        }
        is AgentMessageUi -> countAssistantLiveTokens(message)
        is ThinkingMessageUi -> {
            if (message.content.isBlank()) 0
            else AgentContextBudget.countCurrentTurn(message.content, emptyList())
        }
        is ToolActivityMessageUi -> {
            val text = buildString {
                if (message.argumentsSummary.isNotBlank()) append(message.argumentsSummary)
                message.command?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                message.resultSummary?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            }
            if (text.isBlank()) 0 else AgentContextBudget.countCurrentTurn(text, emptyList())
        }
        else -> 0
    }

internal fun countAssistantLiveTokens(message: AgentMessageUi): Int {
    if (message.content.isBlank()) return 0
    val estimated = AgentContextBudget.countCurrentTurn(message.content, emptyList())
    val billedOutput = message.usage?.outputTokens ?: 0
    return when {
        windowTokensFromUsage(message.usage) == null -> estimated
        message.isStreaming -> (estimated - billedOutput).coerceAtLeast(0)
        else -> 0
    }
}

internal fun messageRoundFromId(id: String): Int? {
    Regex("""-thinking-(\d+)-""").find(id)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    Regex("""-tool-(\d+)-""").find(id)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    Regex("""-hosted-(\d+)-""").find(id)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    if (id.startsWith("assistant-run-")) {
        val parts = id.split("-")
        if (parts.size >= 9) return parts[parts.size - 2].toIntOrNull()
    }
    return null
}

internal fun windowTokensFromUsage(usage: TokenUsageUi?): Int? {
    if (usage == null || usage.isEmpty) return null
    usage.inputTokens?.let { return it.takeIf { tokens -> tokens > 0 } }
    // A total accompanying output without prompt usage is not measured input.
    if (usage.outputTokens != null || usage.reasoningTokens != null) return null
    return usage.contextTokens?.takeIf { it > 0 }
}

/** Compatibility arguments are retained for callers, but estimates never enter
 * the ring, percentage or tooltip. billedContextTokens must be valid cloud input. */
@Suppress("UNUSED_PARAMETER")
internal fun liveContextUsage(
    history: List<AgentModelClient.ConversationMessage>,
    currentInput: String,
    pendingImages: List<PendingImageUi>,
    selectedModel: AgentModelOptionUi?,
    pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    pendingConversationMentions: List<PendingConversationMentionUi> = emptyList(),
    historyTokenCount: Int? = null,
    billedContextTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    billedOverheadTokens: Int? = null,
    uncommittedLiveTokens: Int = 0,
): AgentContextUsageUi = AgentContextUsageUi(
    contextTokens = billedContextTokens?.takeIf { it > 0 },
    contextWindow = selectedModel?.contextWindow,
)

internal fun PendingImageUi.toLiveModelImage(): AgentModelClient.ModelImage =
    if (isVideo) {
        AgentModelClient.ModelImage(
            reference = uri,
            mimeType = mimeType,
            bytes = byteSize,
            source = uri,
        )
    } else {
        AgentModelClient.ModelImage(
            reference = dataUrl,
            mimeType = mimeType,
            bytes = dataUrl.length,
            source = uri,
        )
    }

internal fun PendingImageUi.toOutboundModelImage(supportsVideo: Boolean): AgentModelClient.ModelImage =
    if (isVideo && !supportsVideo) {
        AgentModelClient.ModelImage(
            reference = dataUrl,
            mimeType = "image/jpeg",
            bytes = dataUrl.length,
            source = uri,
        )
    } else {
        toLiveModelImage()
    }

internal fun PendingImageUi.cacheDisplayName(index: Int): String {
    if (isVideo) {
        val extension = io.github.mangi.eta.agent.media.AgentVideoCodec.extensionForMime(mimeType, uri)
        return "chat-video-${index + 1}.$extension"
    }
    val extension = when {
        mimeType.contains("png", ignoreCase = true) -> "png"
        mimeType.contains("webp", ignoreCase = true) -> "webp"
        mimeType.contains("gif", ignoreCase = true) -> "gif"
        else -> "jpg"
    }
    return "chat-image-${index + 1}.$extension"
}

internal fun isContextWindowExceeded(
    usage: AgentContextUsageUi,
    thresholdPercent: Float = 0.99f,
): Boolean {
    val tokens = usage.contextTokens ?: return false
    val window = usage.contextWindow ?: return false
    return window > 0 && tokens.toFloat() / window.toFloat() >= thresholdPercent
}

internal fun shouldBlockSendForContextWindow(
    autoCompressEnabled: Boolean,
    usage: AgentContextUsageUi,
): Boolean = !autoCompressEnabled && isContextWindowExceeded(usage)

internal fun contextUsageProgress(contextTokens: Int?, contextWindow: Int?): Float? {
    if (contextTokens == null || contextTokens < 0 || contextWindow == null || contextWindow <= 0) {
        return null
    }
    return (contextTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
}

@Suppress("UNUSED_PARAMETER")
internal fun formatContextUsage(
    usage: AgentContextUsageUi,
    noUsageText: String = "No conversation context yet",
    noLimitText: String = "The current model does not provide a context limit",
    locale: Locale = Locale.getDefault(),
): String {
    // Zero is a display placeholder, never a fabricated cloud measurement.
    val tokens = usage.contextTokens?.coerceAtLeast(0) ?: 0
    val tokenText = if (tokens == 0) "0K" else formatCompactTokenCount(tokens, locale)
    val window = usage.contextWindow
    if (window == null || window <= 0) return "$tokenText tokens" + 10.toChar() + noLimitText
    val percentFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return "$tokenText / ${formatCompactTokenCount(window, locale)} tokens · " +
        "${percentFormat.format(tokens.toDouble() / window * 100.0)}%"
}

internal fun formatCompactTokenCount(value: Int, locale: Locale = Locale.getDefault()): String {
    val absolute = kotlin.math.abs(value.toLong())
    val divisor = when {
        absolute >= 1_000_000 -> 1_000_000.0
        absolute >= 1_000 -> 1_000.0
        else -> return NumberFormat.getIntegerInstance(locale).format(value)
    }
    val suffix = if (divisor == 1_000_000.0) "M" else "K"
    val formatted = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(value / divisor)
    return "$formatted$suffix"
}
