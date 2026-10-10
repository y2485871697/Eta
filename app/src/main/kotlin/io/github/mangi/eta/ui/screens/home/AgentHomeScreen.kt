package io.github.mangi.eta.ui.screens.home

import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.components.rememberChatVoiceController
import io.github.mangi.eta.ui.components.AgentChatBody
import io.github.mangi.eta.ui.components.chatConversationCompositionKey
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentModelPickerUiState

/**
 * AgentChatHome：首屏为聊天主舞台。
 *
 * 顶部入口统一由 [io.github.mangi.eta.ui.app.AgentAppShell] 提供，
 * 本 Screen 只负责消息流、Run trace、工具摘要和底部输入框。
 */
@Composable
internal fun AgentHomeScreen(
    // Read the live state here, not in the caller: each streamed delta publishes a new
    // homeState, and a caller-side read would recompose the whole navigation entry.
    stateProvider: () -> AgentChatHomeUiState,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    modelPickerState: AgentModelPickerUiState,
    autoCompressEnabled: Boolean,
    requestOverheadTokens: Int = 0,
    measuredContextTokens: () -> Int? = { null },
    billedOverheadTokens: Int? = null,
    conversationKey: String?,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    onAction: (AgentHomeAction) -> Unit,
    isDrawerOpen: Boolean = false,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val voiceController = rememberChatVoiceController(conversationKey) { text ->
        onAction(AgentHomeAction.SubmitMessage(text))
    }
    key(chatConversationCompositionKey(conversationKey)) {
        val state = stateProvider()
        AgentChatBody(
            collaborationConversationId = conversationKey,
            voiceController = voiceController,
            messages = state.messages,
            history = AgentConversationRevisionReducer.outboundHistory(state),
            modelPickerState = modelPickerState,
            autoCompressEnabled = autoCompressEnabled,
            requestOverheadTokens = requestOverheadTokens,
            measuredContextTokens = measuredContextTokens(),
            contextDisplayPolicy = io.github.mangi.eta.ui.model.contextDisplayPolicy(state),
            billedOverheadTokens = state.cloudRequestOverheadTokens,
            livePromptTokens = state.livePromptTokens,
            livePromptIsProjected = state.livePromptIsProjected,
            billedHistoryTokens = state.cloudHistoryTokens,
            activeRunContextWindow = state.activeRunContextWindow,
            childContexts = state.childContexts,
            selectedContextTaskId = state.selectedContextTaskId,
            onContextTaskSelected = { onAction(AgentHomeAction.ContextTaskSelected(it)) },
            compactingModelName = state.compactingModelName,
            input = state.input,
            draftField = draftField,
            isStreaming = state.isStreaming,
            isPaused = state.isPaused,
            isCompressingContext = state.isCompressingContext,
            isWaitingForCompression = state.isWaitingForCompression,
            reasoningEffort = state.reasoningEffort,
            availableReasoningEfforts = state.availableReasoningEfforts,
            pendingImages = state.pendingImages,
            pendingFileReferences = state.pendingFileReferences,
            conversationMentions = conversationMentions,
            messageEdit = state.messageEdit,
            assistantId = state.assistantId,
            onReasoningEffortChange = { onAction(AgentHomeAction.ReasoningEffortChanged(it)) },
            onModelSelected = { providerId, modelId -> onAction(AgentHomeAction.ModelSelected(modelId, providerId)) },
            onSubmit = { text -> onAction(AgentHomeAction.SubmitMessage(text)) },
            onStop = { onAction(AgentHomeAction.StopRun) },
            onContinue = { onAction(AgentHomeAction.ContinueRun) },
            onAbortPausedRun = { onAction(AgentHomeAction.AbortPausedRun) },
            onAttachImage = { uri -> onAction(AgentHomeAction.ImageAttached(uri)) },
            onAttachVideo = { uri -> onAction(AgentHomeAction.VideoAttached(uri)) },
            onRemoveImage = { id -> onAction(AgentHomeAction.RemoveImage(id)) },
            onAttachFiles = { uris -> onAction(AgentHomeAction.FilesAttached(uris)) },
            onAttachFolder = { uri -> onAction(AgentHomeAction.FolderAttached(uri)) },
            onAttachFilePath = { path -> onAction(AgentHomeAction.FilePathAttached(path)) },
            onRemoveFileReference = { id -> onAction(AgentHomeAction.RemoveFileReference(id)) },
            onEditMessage = { id -> onAction(AgentHomeAction.EditMessage(id)) },
            onCancelMessageEdit = { onAction(AgentHomeAction.CancelMessageEdit) },
            onDeleteMessage = { id -> onAction(AgentHomeAction.DeleteMessage(id)) },
            onRegenerateMessage = { id -> onAction(AgentHomeAction.RegenerateMessage(id)) },
            onBranchMessage = { id -> onAction(AgentHomeAction.BranchMessage(id)) },
            onQuestionDraftChanged = { c, q, a -> onAction(AgentHomeAction.QuestionDraftChanged(c, q, a)) },
            onSubmitQuestionAnswer = { c, q -> onAction(AgentHomeAction.SubmitQuestionAnswer(c, q)) },
            onSuggestionClick = { prompt ->
                onAction(AgentHomeAction.SubmitMessage(prompt))
            },
            onRunTraceClick = { onAction(AgentHomeAction.ExpandRunTrace) },
            onOpenBrowser = { onAction(AgentHomeAction.OpenBrowser) },
            onEditAssistant = { id -> onAction(AgentHomeAction.EditAssistant(id)) },
            onAssistantSelected = { id -> onAction(AgentHomeAction.AssistantSelected(id)) },
            gptSpeedMode = state.gptSpeedMode,
            onCycleGptSpeedMode = { onAction(AgentHomeAction.CycleGptSpeedMode) },
            isDrawerOpen = isDrawerOpen,
            scrollToMessageId = scrollToMessageId,
            onScrollToMessageConsumed = onScrollToMessageConsumed,
            modifier = modifier,
        )
    }
}
