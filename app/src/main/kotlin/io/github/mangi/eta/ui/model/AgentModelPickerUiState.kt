package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ProviderSetting
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
    ): AgentModelPickerUiState {
        val enabledProviders = providers
            .asSequence()
            .filter(ProviderSetting::isEnabled)
            .sortedBy(ProviderSetting::sortOrder)
            .toList()
        val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
        val selectedModel = selectedProvider
            ?.models
            ?.firstOrNull { it.id == selectedModelId && it.isEnabled }
            ?.let { model -> selectedProvider.toOption(model) }
            ?: enabledProviders.asSequence()
                .flatMap { provider ->
                    provider.models.asSequence()
                        .filter { it.isEnabled }
                        .map { model -> provider.toOption(model) }
                }
                .firstOrNull { it.id == selectedModelId }
        val groups = enabledProviders
            .asSequence()
            .filter { it.apiKey.isNotBlank() }
            .mapNotNull { provider ->
                val sourceType = ProviderSourceRegistry.resolve(provider)
                val models = provider.models
                    .asSequence()
                    .filter { it.isEnabled }
                    .sortedBy { it.sortOrder }
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
        )
}

internal fun defaultExpandedModelProviderIds(selectedModel: AgentModelOptionUi?): Set<String> =
    selectedModel?.providerId?.let(::setOf).orEmpty()

internal fun latestContextUsage(
    messages: List<AgentChatMessageUi>,
    selectedModel: AgentModelOptionUi?,
): AgentContextUsageUi = AgentContextUsageUi(
    contextTokens = messages.asReversed()
        .asSequence()
        .filterIsInstance<AgentMessageUi>()
        .mapNotNull { it.usage?.contextTokens }
        .firstOrNull(),
    contextWindow = selectedModel?.contextWindow,
)

/**
 * Counts the real outbound context: history + the current turn that would be sent.
 * Current-turn images use the same payload size as [PendingImageUi.dataUrl], matching
 * the history image conversion used when a message is actually submitted.
 */
internal fun liveContextUsage(
    history: List<AgentModelClient.ConversationMessage>,
    currentInput: String,
    pendingImages: List<PendingImageUi>,
    selectedModel: AgentModelOptionUi?,
    pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    historyTokenCount: Int? = null,
): AgentContextUsageUi {
    val prompt = AgentFileReferencePromptCodec.format(
        currentInput,
        pendingFileReferences.map { it.reference },
    )
    val images = pendingImages.map { it.toLiveModelImage() }
    val historyTokens = historyTokenCount ?: history.sumOf { AgentContextBudget.countMessage(it) }
    val currentTurnTokens = if (prompt.isEmpty() && images.isEmpty()) {
        0
    } else {
        AgentContextBudget.countCurrentTurn(prompt, images)
    }
    return AgentContextUsageUi(
        contextTokens = historyTokens + currentTurnTokens,
        contextWindow = selectedModel?.contextWindow,
    )
}

internal fun PendingImageUi.toLiveModelImage(): AgentModelClient.ModelImage =
    AgentModelClient.ModelImage(
        reference = dataUrl,
        mimeType = mimeType,
        bytes = dataUrl.length,
        source = uri,
    )

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

internal fun shouldShowLiveContextUsage(
    showContextUsage: Boolean,
    contextSendBlocked: Boolean,
    usage: AgentContextUsageUi,
): Boolean {
    if (showContextUsage || contextSendBlocked) return true
    val tokens = usage.contextTokens ?: 0
    return usage.contextWindow != null && tokens > 0
}

internal fun contextUsageProgress(contextTokens: Int?, contextWindow: Int?): Float? {
    if (contextTokens == null || contextTokens < 0 || contextWindow == null || contextWindow <= 0) {
        return null
    }
    return (contextTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
}

internal fun formatContextUsage(
    usage: AgentContextUsageUi,
    noUsageText: String = "No conversation context yet",
    noLimitText: String = "The current model does not provide a context limit",
    locale: Locale = Locale.getDefault(),
): String = when {
    usage.contextTokens == null -> noUsageText
    usage.contextWindow == null || usage.contextWindow <= 0 ->
        "${formatCompactTokenCount(usage.contextTokens, locale)} tokens\n$noLimitText"
    else -> {
        val percent = usage.contextTokens.toDouble() / usage.contextWindow.toDouble() * 100.0
        val percentFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        "${formatCompactTokenCount(usage.contextTokens, locale)} / " +
            "${formatCompactTokenCount(usage.contextWindow, locale)} tokens · " +
            "${percentFormat.format(percent)}%"
    }
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
