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
            supportsVision = model.supportsVision,
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
 * 下一轮即将发出的上下文：最近一次接口账单占用的窗口 + 那次请求之后的增量。
 *
 * 对齐 DeepSeek Harness 的 token-meter：provider usage 是锚点，只对账单之后
 * 新出现的表层做启发式加总。prompt 已经包含系统提示、工具 schema 和此前的
 * 思考/工具结果，不能再从历史头重扫一遍，否则会从本地估算直接跳到「账单+历史」。
 *
 * 优先用 total_tokens（prompt+completion）。没有 total 时退回 input+output。
 * 流式中途只有 prompt 时，只把当前这条助手回复的未入账输出加上去。
 */
internal fun latestBilledContextTokens(messages: List<AgentChatMessageUi>): Int? {
    val compactIndex = messages.indexOfLast { it is ContextCompactedMessageUi }
    val marker = compactIndex.takeIf { it >= 0 }?.let { messages[it] as ContextCompactedMessageUi }
    val resumeRound = marker?.resumeRound ?: 0
    val billedIndex = messages.indices.lastOrNull { index ->
        val message = messages[index]
        message is AgentMessageUi &&
            windowTokensFromUsage(message.usage) != null &&
            index > compactIndex &&
            (resumeRound <= 0 || (messageRoundFromId(message.id) ?: 0) >= resumeRound)
    } ?: -1
    if (billedIndex >= 0) {
        val billedMessage = messages[billedIndex] as AgentMessageUi
        val billed = windowTokensFromUsage(billedMessage.usage) ?: return null
        val tailStart = if (billedMessage.isStreaming) billedIndex else billedIndex + 1
        return billed + countUnbilledTail(
            messages = messages,
            startIndex = tailStart,
            resumeRound = resumeRound,
            billedIndex = billedIndex,
        )
    }
    val baseline = marker?.baselineTokens ?: 0
    if (compactIndex >= 0 && baseline > 0 && resumeRound > 0) {
        return baseline + countUnbilledTail(messages, compactIndex + 1, resumeRound)
    }
    return null
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
    usage.contextTokens?.takeIf { it > 0 }?.let { return it }
    val combined = (usage.inputTokens ?: 0) + (usage.outputTokens ?: 0)
    return combined.takeIf { it > 0 }
}

internal fun liveContextUsage(
    history: List<AgentModelClient.ConversationMessage>,
    currentInput: String,
    pendingImages: List<PendingImageUi>,
    selectedModel: AgentModelOptionUi?,
    pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    historyTokenCount: Int? = null,
    billedContextTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    billedOverheadTokens: Int? = null,
    uncommittedLiveTokens: Int = 0,
): AgentContextUsageUi {
    val supportsVision = selectedModel?.supportsVision ?: true
    val imageFileReferences = if (supportsVision) {
        emptyList()
    } else {
        pendingImages.mapIndexed { index, image ->
            AgentFileReference(
                displayName = image.cacheDisplayName(index),
                absolutePath = "/cache/chat-image-${index + 1}",
                kind = AgentFileReferenceKind.File,
            )
        }
    }
    val prompt = AgentFileReferencePromptCodec.format(
        currentInput,
        pendingFileReferences.map { it.reference } + imageFileReferences,
    )
    val images = if (supportsVision) pendingImages.map { it.toLiveModelImage() } else emptyList()
    val currentTurnTokens = if (prompt.isEmpty() && images.isEmpty()) {
        0
    } else {
        AgentContextBudget.countCurrentTurn(prompt, images)
    }
    val overhead = requestOverheadTokens.coerceAtLeast(0)
    val historyTokens = when {
        billedContextTokens != null && billedContextTokens > 0 -> {
            val billedOverhead = billedOverheadTokens ?: overhead
            (billedContextTokens + (overhead - billedOverhead)).coerceAtLeast(0)
        }
        else -> (historyTokenCount ?: history.sumOf { AgentContextBudget.countMessage(it) }) +
            overhead +
            uncommittedLiveTokens.coerceAtLeast(0)
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

internal fun PendingImageUi.cacheDisplayName(index: Int): String {
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
