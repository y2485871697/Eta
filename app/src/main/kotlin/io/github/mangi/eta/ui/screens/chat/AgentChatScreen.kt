package io.github.mangi.eta.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.components.AgentChatBody
import io.github.mangi.eta.ui.components.chatConversationCompositionKey
import io.github.mangi.eta.ui.model.AgentChatAction
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentModelPickerUiState

/**
 * 独立对话页：与首页聊天主舞台共用同一套消息/输入组件，
 * 区别仅在于顶部返回由 Shell 统一提供。
 */
@Composable
internal fun AgentChatScreen(
    state: AgentChatUiState,
    modelPickerState: AgentModelPickerUiState,
    autoCompressEnabled: Boolean,
    requestOverheadTokens: Int = 0,
    billedOverheadTokens: Int? = null,
    conversationKey: String?,
    onAction: (AgentChatAction) -> Unit,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    key(chatConversationCompositionKey(conversationKey)) {
        AgentChatBody(
            messages = state.messages,
            history = AgentConversationRevisionReducer.outboundHistory(state),
            modelPickerState = modelPickerState,
            autoCompressEnabled = autoCompressEnabled,
            requestOverheadTokens = requestOverheadTokens,
            billedOverheadTokens = billedOverheadTokens,
            livePromptTokens = state.livePromptTokens,
            input = state.input,
            isStreaming = state.isStreaming,
            isPaused = state.isPaused,
            isCompressingContext = state.isCompressingContext,
            reasoningEffort = state.reasoningEffort,
            availableReasoningEfforts = state.availableReasoningEfforts,
            pendingImages = state.pendingImages,
            pendingFileReferences = state.pendingFileReferences,
            messageEdit = state.messageEdit,
            onReasoningEffortChange = { onAction(AgentChatAction.ReasoningEffortChanged(it)) },
            onModelSelected = { onAction(AgentChatAction.ModelSelected(it)) },
            onSubmit = { text -> onAction(AgentChatAction.SubmitMessage(text)) },
            onStop = { onAction(AgentChatAction.StopRun) },
            onContinue = { onAction(AgentChatAction.ContinueRun) },
            onAbortPausedRun = { onAction(AgentChatAction.AbortPausedRun) },
            onAttachImage = { uri -> onAction(AgentChatAction.ImageAttached(uri)) },
            onRemoveImage = { id -> onAction(AgentChatAction.RemoveImage(id)) },
            onAttachFiles = { uris -> onAction(AgentChatAction.FilesAttached(uris)) },
            onAttachFolder = { uri -> onAction(AgentChatAction.FolderAttached(uri)) },
            onAttachFilePath = { path -> onAction(AgentChatAction.FilePathAttached(path)) },
            onRemoveFileReference = { id -> onAction(AgentChatAction.RemoveFileReference(id)) },
            onEditMessage = { id -> onAction(AgentChatAction.EditMessage(id)) },
            onCancelMessageEdit = { onAction(AgentChatAction.CancelMessageEdit) },
            onDeleteMessage = { id -> onAction(AgentChatAction.DeleteMessage(id)) },
            onRegenerateMessage = { id -> onAction(AgentChatAction.RegenerateMessage(id)) },
            onSuggestionClick = { prompt ->
                onAction(AgentChatAction.SubmitMessage(prompt))
            },
            onRunTraceClick = { /* 对话页暂不做 Run trace 展开 */ },
            onOpenBrowser = { onAction(AgentChatAction.OpenBrowser) },
            onEditAssistant = { id -> onAction(AgentChatAction.EditAssistant(id)) },
            onAssistantSelected = { id -> onAction(AgentChatAction.AssistantSelected(id)) },
            scrollToMessageId = scrollToMessageId,
            onScrollToMessageConsumed = onScrollToMessageConsumed,
            modifier = modifier,
        )
    }
}
