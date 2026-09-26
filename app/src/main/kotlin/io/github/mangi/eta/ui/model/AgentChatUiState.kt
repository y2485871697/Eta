package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort

@Immutable
internal data class AgentChatUiState(
    val messages: List<AgentChatMessageUi>,
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
    val input: String,
    val isStreaming: Boolean,
    val isPaused: Boolean = false,
    val isCompressingContext: Boolean = false,
    val compactingModelName: String = "",
    val isWaitingForCompression: Boolean = false,
    val thinkingEnabled: Boolean,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.fromLegacy(thinkingEnabled),
    val providerId: String = "",
    val modelId: String = "",
    val assistantId: String = "",
    val availableReasoningEfforts: List<ReasoningEffort> = emptyList(),
    val pendingImages: List<PendingImageUi> = emptyList(),
    val pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    val pendingConversationMentions: List<PendingConversationMentionUi> = emptyList(),
    val appliedRuntimeRunIds: List<String> = emptyList(),
    val messageEdit: MessageEditUiState? = null,
    /** 当前请求的 prompt 占用；工具循环里由 Runtime 按账单+增量投影，对齐 ST 输入。 */
    val livePromptTokens: Int? = null,
    val childContexts: List<io.github.mangi.eta.agent.delegation.SubAgentContextStats> = emptyList(),
    val childContextRunId: String = "",
    val selectedContextTaskId: String? = null,
)

@Immutable
sealed interface AgentChatMessageUi {
    val id: String
}

@Immutable
data class UserMessageUi(
    override val id: String,
    val content: String,
    val images: List<String> = emptyList(),
    val isEdited: Boolean = false,
    val imageSources: List<String> = emptyList(),
    val imageIsVideo: List<Boolean> = emptyList(),
    val imageDurationsMs: List<Long?> = emptyList(),
) : AgentChatMessageUi

@Immutable
data class AgentMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean = false,
    val renderMarkdown: Boolean = true,
    val usage: TokenUsageUi? = null,
    val generatedAtMillis: Long? = null,
) : AgentChatMessageUi

enum class SystemNoticeCode(val wireValue: String) {
    Stopped("stopped"),
    EmptyResult("empty_result"),
    RuntimeFailed("runtime_failed"),
    ModelRetry("model_retry"),
    Interrupted("interrupted"),
    Completed("completed");

    companion object {
        fun fromWireValue(value: String): SystemNoticeCode? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

/** Eta 自己生成的消息只保存稳定状态码，展示时再按当前语言解析。 */
@Immutable
data class SystemNoticeMessageUi(
    override val id: String,
    val code: SystemNoticeCode,
    val detail: String? = null,
) : AgentChatMessageUi

internal fun SystemNoticeCode.isRetryableFailure(): Boolean =
    this == SystemNoticeCode.Stopped || this == SystemNoticeCode.RuntimeFailed

internal fun SystemNoticeCode.canContinueDisconnectedRun(): Boolean =
    this == SystemNoticeCode.RuntimeFailed

internal fun List<AgentChatMessageUi>.stoppedDuringModelRetry(): Boolean {
    val last = lastOrNull { message ->
        when (message) {
            is UserMessageUi -> !message.isSteerSupplement()
            is AgentMessageUi, is SystemNoticeMessageUi -> true
            else -> false
        }
    } ?: return false
    if (last !is SystemNoticeMessageUi || last.code != SystemNoticeCode.Stopped) return false
    val stopIndex = indexOf(last)
    val boundary = take(stopIndex).indexOfLast {
        (it is UserMessageUi && !it.isSteerSupplement()) ||
            (it is SystemNoticeMessageUi && it.code.isRetryableFailure())
    }
    return subList(boundary + 1, stopIndex).any {
        it is SystemNoticeMessageUi && it.code == SystemNoticeCode.ModelRetry
    }
}

/** Last visible chat item after the original user turn, ignoring steer/resume supplements. */
internal fun lastContinuableNotice(messages: List<AgentChatMessageUi>): SystemNoticeMessageUi? {
    val last = messages.lastOrNull { message ->
        when (message) {
            is UserMessageUi -> !message.isSteerSupplement()
            is AgentMessageUi, is SystemNoticeMessageUi -> true
            else -> false
        }
    }
    return last as? SystemNoticeMessageUi
}

internal fun canContinuePausedGeneration(messages: List<AgentChatMessageUi>): Boolean {
    val notice = lastContinuableNotice(messages) ?: return false
    return notice.code == SystemNoticeCode.Stopped
}

internal fun canContinueDisconnectedRun(messages: List<AgentChatMessageUi>): Boolean {
    val notice = lastContinuableNotice(messages) ?: return false
    return notice.code.canContinueDisconnectedRun() || messages.stoppedDuringModelRetry()
}

@Immutable
data class TokenUsageUi(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null
}

@Immutable
data class ConversationTokenUsageUi(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    val freshInputTokens: Long get() = (inputTokens - cachedTokens).coerceAtLeast(0L)
    val hasUsage: Boolean get() = inputTokens > 0 || outputTokens > 0 || cachedTokens > 0
    val cachePercent: Double?
        get() = if (inputTokens > 0) {
            cachedTokens.toDouble() / inputTokens.toDouble() * 100.0
        } else {
            null
        }
}

fun clearBilledTokenUsage(messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
    messages.map { message ->
        if (message is AgentMessageUi && message.usage != null) {
            message.copy(usage = null)
        } else {
            message
        }
    }

fun conversationTokenUsage(messages: List<AgentChatMessageUi>): ConversationTokenUsageUi {
    var input = 0L
    var output = 0L
    var cached = 0L
    messages.forEach { message ->
        when (message) {
            is AgentMessageUi -> {
                val usage = message.usage ?: return@forEach
                input += usage.inputTokens ?: 0
                output += usage.outputTokens ?: 0
                cached += usage.cachedTokens ?: 0
            }
            is ContextCompactedMessageUi -> {
                input += message.preservedUsage.inputTokens
                output += message.preservedUsage.outputTokens
                cached += message.preservedUsage.cachedTokens
            }
            else -> Unit
        }
    }
    return ConversationTokenUsageUi(
        inputTokens = input,
        outputTokens = output,
        cachedTokens = cached,
    )
}

@Immutable
data class ThinkingMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean,
    val elapsedSeconds: Int? = null,
    val collapsed: Boolean = false,
) : AgentChatMessageUi

/**
 * 首页的 Run trace 入口卡片：展示 Agent 当前可调用的能力分组。
 */
@Immutable
data class RunTraceMessageUi(
    override val id: String,
    val capabilities: List<CapabilityUi>,
) : AgentChatMessageUi

@Immutable
data class CapabilityUi(
    val title: String,
    val items: List<String>,
)

/**
 * 工具调用摘要：出现在消息流中，显示当前/最近一步调用了哪些工具。
 */
@Immutable
data class ToolSummaryMessageUi(
    override val id: String,
    val tools: List<String>,
) : AgentChatMessageUi

/** 上下文压缩分界：插在被折叠消息和保留消息之间，点击可查看摘要。 */
@Immutable
data class ContextCompactedMessageUi(
    override val id: String,
    val compactedCount: Int,
    val summary: String,
    val compressorLabel: String = "",
    val baselineTokens: Int = 0,
    val resumeRound: Int = 0,
    val preservedUsage: ConversationTokenUsageUi = ConversationTokenUsageUi(),
) : AgentChatMessageUi

@Immutable
data class ToolActivityMessageUi(
    override val id: String,
    val toolName: String,
    val status: ToolActivityStatusUi,
    val argumentsSummary: String,
    val command: String? = null,
    val resultSummary: String? = null,
    val imageCount: Int = 0,
) : AgentChatMessageUi

enum class ToolActivityStatusUi {
    Running,
    Success,
    Failed,
    Unknown,
}

/**
 * 建议语 chip 行。
 */
@Immutable
data class SuggestionChipsMessageUi(
    override val id: String,
    val prompts: List<String>,
) : AgentChatMessageUi

@Immutable
data class PendingImageUi(
    val id: String,
    val uri: String,
    val dataUrl: String,
    val mimeType: String,
    val isVideo: Boolean = false,
    val durationMs: Long? = null,
    val byteSize: Int = 0,
)

@Immutable
data class PendingFileReferenceUi(
    val id: String,
    val reference: AgentFileReference,
)

@Immutable
data class PendingConversationMentionUi(
    val id: String,
    val conversationId: String,
    val title: String,
    val transcript: String,
)

@Immutable
data class MessageEditUiState(
    val targetMessageId: String,
    val previousInput: String,
    val previousImages: List<PendingImageUi>,
    val previousFileReferences: List<PendingFileReferenceUi>,
    val hasLaterTurns: Boolean,
    val previousConversationMentions: List<PendingConversationMentionUi> = emptyList(),
)

internal fun UserMessageUi.isSteerSupplement(): Boolean =
    id.contains("-supplement-")

internal fun UserMessageUi.isResumeAfterCompress(): Boolean =
    id.contains("-supplement-resume")

internal fun AgentChatUiState.hasRunningTools(): Boolean =
    messages.any { message ->
        message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running
    }

internal fun AgentChatUiState.lastRealUserIndex(): Int =
    messages.indexOfLast { message ->
        message is UserMessageUi && !message.isSteerSupplement()
    }

/** Failed/stopped notices close that round. Later continue output starts after this boundary. */
internal fun AgentChatUiState.lastTurnBoundaryIndex(): Int =
    messages.indexOfLast { message ->
        (message is UserMessageUi && !message.isSteerSupplement()) ||
            (message is SystemNoticeMessageUi && message.code.isRetryableFailure())
    }

private fun AgentChatUiState.currentTurnMessages(): List<AgentChatMessageUi> {
    val lastUserIndex = lastTurnBoundaryIndex()
    return if (lastUserIndex >= 0) {
        messages.subList(lastUserIndex + 1, messages.size)
    } else {
        messages
    }
}

/** 当前用户消息之后是否已经出现工具。用于避免自动压缩打断工具循环。 */
internal fun AgentChatUiState.hasCurrentTurnTools(): Boolean =
    currentTurnMessages().any { it is ToolActivityMessageUi || it is ToolSummaryMessageUi }

/** 最后一条非空助手正文是否在本轮原问题之后，追加/续写不另开一轮。 */
internal fun AgentChatUiState.hasPartialAssistantAfterLastUser(): Boolean {
    val lastUserIndex = lastTurnBoundaryIndex()
    val lastAssistantIndex = messages.indexOfLast { message ->
        message is AgentMessageUi && message.content.isNotBlank()
    }
    return lastAssistantIndex > lastUserIndex
}

/** 当前用户消息之后是否已经开始思考、工具或正文。不看更早轮次。 */
internal fun AgentChatUiState.hasStartedCurrentTurnOutput(): Boolean {
    val lastUserIndex = lastTurnBoundaryIndex()
    val currentTurn = if (lastUserIndex >= 0) {
        messages.subList(lastUserIndex + 1, messages.size)
    } else {
        messages
    }
    return currentTurn.any { message ->
        when (message) {
            is ThinkingMessageUi -> message.isStreaming || message.content.isNotBlank()
            is AgentMessageUi -> message.isStreaming || message.content.isNotBlank()
            is ToolActivityMessageUi, is ToolSummaryMessageUi -> true
            else -> false
        }
    }
}

