package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.voice.VoiceChatSnapshot
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModeController
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.agent.voice.VOICE_MODE_SPEECH_OWNER_PREFIX
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.latestBilledContextTokens
import io.github.mangi.eta.ui.model.canContinueDisconnectedRun
import io.github.mangi.eta.ui.model.isRetryableFailure
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress
import io.github.mangi.eta.ui.model.isSteerSupplement
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 聊天主体：消息流 + 底部输入框。
 *
 * AI 对话使用正向时间线：第一条消息从对话区顶部开始，后续回复顺序向下追加。
 * 空 assistant 占位不参与布局，避免刚发送时出现一个无内容消息节点。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
internal fun AgentChatBody(
    voiceController: VoiceModeController,
    messages: List<AgentChatMessageUi>,
    history: List<AgentModelClient.ConversationMessage>,
    modelPickerState: AgentModelPickerUiState,
    requestOverheadTokens: Int = 0,
    measuredContextTokens: Int? = null,
    contextDisplayPolicy: io.github.mangi.eta.ui.model.ContextDisplayPolicy = io.github.mangi.eta.ui.model.ContextDisplayPolicy(),
    billedOverheadTokens: Int? = null,
    livePromptTokens: Int? = null,
    livePromptIsProjected: Boolean = false,
    billedHistoryTokens: Int? = null,
    activeRunContextWindow: Int? = null,
    childContexts: List<io.github.mangi.eta.agent.delegation.SubAgentContextStats> = emptyList(),
    compactingModelName: String = "",
    selectedContextTaskId: String? = null,
    onContextTaskSelected: ((String?) -> Unit)? = null,
    autoCompressEnabled: Boolean = false,
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    canContinueDisconnected: Boolean = false,
    isCompressingContext: Boolean = false,
    isWaitingForCompression: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    messageEdit: MessageEditUiState?,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onBranchMessage: (String) -> Unit = {},
    onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit = { _, _, _ -> },
    onSubmitQuestionAnswer: (String, String) -> Unit = { _, _ -> },
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit = {},
    gptSpeedMode: io.github.mangi.eta.data.model.GptSpeedMode = io.github.mangi.eta.data.model.GptSpeedMode.NORMAL,
    onCycleGptSpeedMode: () -> Unit = {},
    isDrawerOpen: Boolean = false,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val chatComposeStartedNs = if (StreamPerformanceDiagnostics.enabled) System.nanoTime() else 0L
    val chatUiActive = LocalChatUiActive.current
    val routeCovered = LocalChatRouteCovered.current
    val transitionActive = LocalChatTransitionActive.current
    // 停稳且完全盖住时保留当前快照，避免设置页和管理页跟着重排。
    // 横滑手势和回弹期间露出的部分必须继续走正常打字机，不能追平、不能停住。
    val followLiveTranscript = chatUiActive && (!routeCovered || transitionActive)
    val frozenChatSnapshot = remember(followLiveTranscript) {
        if (followLiveTranscript) null else Triple(messages, isStreaming, isPaused)
    }
    val uiMessages = frozenChatSnapshot?.first ?: messages
    val uiStreaming = frozenChatSnapshot?.second ?: isStreaming
    val uiPaused = frozenChatSnapshot?.third ?: isPaused
    io.github.mangi.eta.ui.haptics.StreamingHaptics.Observe(
        enabled = followLiveTranscript && !isPaused,
        conversationId = collaborationConversationId,
    )
    SideEffect {
        StreamPerformanceDiagnostics.record("chat.compose", value = messages.size.toLong())
        if (chatComposeStartedNs != 0L) {
            StreamPerformanceDiagnostics.record(
                "chat.compose.elapsed",
                System.nanoTime() - chatComposeStartedNs,
            )
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val context = LocalContext.current
    val voiceState by voiceController.state.collectAsState()
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottomPx > 0
    val browserShortcut by remember {
        AgentBrowserSession.snapshots
            .map { snapshot ->
                Triple(snapshot.available, snapshot.lastAgentRunId, snapshot.lastAgentToolCallId)
            }
            .distinctUntilChanged()
    }.collectAsState(initial = Triple(false, null, null))
    val visibleMessagesCache = remember { AgentVisibleMessagesCache() }
    val visibleMessages = remember(uiMessages, messageEdit?.targetMessageId) {
        visibleMessagesCache.project(uiMessages, messageEdit?.targetMessageId)
    }
    LaunchedEffect(messages, isStreaming) {
        val last = messages.filterIsInstance<AgentMessageUi>().lastOrNull()
        val speechText = last?.content.orEmpty()
        voiceController.updateChat(
            VoiceChatSnapshot(
                isStreaming = isStreaming,
                lastAgentId = last?.id,
                lastAgentText = speechText,
            )
        )
    }
    val speechPlayback by io.github.mangi.eta.agent.voice.tts.SpeechPlayback.state.collectAsState()
    LaunchedEffect(visibleMessages, messageEdit?.targetMessageId, speechPlayback.owner) {
        val owner = speechPlayback.owner
        val visibleCompletedIds = visibleMessages.mapNotNull { message ->
            (message as? AgentMessageUi)?.takeIf { !it.isStreaming }?.id
        }.toSet()
        if (shouldStopOrphanSpeechPlayback(owner, messageEdit != null, visibleCompletedIds)) {
            io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stop("orphan_reply")
        }
    }
    // Project once for both the initial tail anchor and the rendered rows below. In
    // particular, do not derive a second full timeline just to ask for its size: this
    // list can contain thousands of streaming/tool messages.
    val timelineProjection = remember { AgentTimelineProjectionCache() }
    val timelineEntries = remember(visibleMessages) {
        StreamPerformanceDiagnostics.measure("timeline.project", visibleMessages.size.toLong()) { timelineProjection.project(visibleMessages) }
    }
    val initialBottomItemIndex = remember(visibleMessages, isCompressingContext, isWaitingForCompression, childContexts) {
        initialTimelineItemIndex(
            timelineEntries = timelineEntries,
            isCompressingContext = isCompressingContext,
            isWaitingForCompression = isWaitingForCompression,
            hasCompactingChildContext = childContexts.any { it.isCompacting },
        )
    }
    // A default one-item prefetch is too shallow for mixed short tool rows and tall Markdown.
    // Keep one viewport on both sides: ahead prepares incoming rows; behind prevents
    // immediate disposal/recomposition when expansion or a direction reversal moves the boundary.
    val chatCacheWindow = remember {
        LazyLayoutCacheWindow(
            aheadFraction = CHAT_CACHE_AHEAD_VIEWPORTS,
            behindFraction = CHAT_CACHE_BEHIND_VIEWPORTS,
        )
    }
    val scrollState = rememberLazyListState(
        cacheWindow = chatCacheWindow,
        initialFirstVisibleItemIndex = initialBottomItemIndex,
    )
    val currentBrowserMessageId = remember(
        visibleMessages,
        browserShortcut,
    ) {
        val (available, runId, toolCallId) = browserShortcut
        if (!available || runId == null || toolCallId == null) {
            null
        } else {
            visibleMessages.lastOrNull { message ->
                message is ToolActivityMessageUi &&
                    message.toolName == "browser_use" &&
                    message.id.startsWith("$runId-tool-") &&
                    message.id.endsWith("-$toolCallId")
            }?.id
        }
    }
    val submitScrollScope = rememberCoroutineScope()
    var keepBottomAnchored by remember { mutableStateOf(true) }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            hideChatInputIme(focusManager, keyboard, view)
        }
    }

    val billedContextTokens = remember(livePromptTokens, livePromptIsProjected, messageEdit) {
        if (messageEdit != null) {
            null
        } else {
            livePromptTokens.takeUnless { livePromptIsProjected }
        }
    }
    val projectedContextTokens = livePromptTokens.takeIf { livePromptIsProjected && messageEdit == null && uiStreaming }
    val imageSourceCache = remember { ChatImageSourceCache() }
    val previewGallery by produceState<List<String>>(emptyList(), visibleMessages, pendingImages) {
        // This used to parse EVERY historical reply synchronously on each text delta.
        // Cancelling this producer prevents obsolete galleries from being published.
        value = withContext(Dispatchers.Default) {
            StreamPerformanceDiagnostics.measure("gallery.scan", visibleMessages.size.toLong()) {
                collectPreviewableChatImages(visibleMessages, pendingImages) { message ->
                    imageSourceCache.sources(message.id, message.content)
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAgentContextTelemetry provides AgentContextTelemetry(childContexts, modelPickerState.selectedModel?.displayName.orEmpty(), compactingModelName, selectedContextTaskId, onContextTaskSelected)
    ) {
        ChatImagePreviewHost(gallery = previewGallery) {
            AgentChatScaffold(
                collaborationConversationId = collaborationConversationId,
                visibleMessages = visibleMessages,
                timelineEntries = timelineEntries,
                hasMessages = visibleMessages.isNotEmpty(),
                scrollState = scrollState,
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = history,
                billedContextTokens = billedContextTokens,
                projectedContextTokens = projectedContextTokens,
                billedHistoryTokens = billedHistoryTokens,
                requestOverheadTokens = requestOverheadTokens,
                measuredContextTokens = measuredContextTokens,
                contextDisplayPolicy = contextDisplayPolicy,
                billedOverheadTokens = billedOverheadTokens,
                activeRunContextWindow = activeRunContextWindow,
                autoCompressEnabled = autoCompressEnabled,
                isStreaming = uiStreaming,
                isPaused = uiPaused,
                isCompressingContext = isCompressingContext,
                isWaitingForCompression = isWaitingForCompression,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                conversationMentions = conversationMentions,
                messageEdit = messageEdit,
                assistantId = assistantId,
                voiceState = voiceState,
                onStartVoiceMode = voiceController::start,
                onStopVoiceMode = voiceController::stop,
                showEmptySuggestions = !isKeyboardVisible,
                keepBottomAnchored = keepBottomAnchored,
                onBottomAnchorChanged = { keepBottomAnchored = it },
                onSubmit = { text ->
                    // Hide only for this submit action, never for a later streaming/tool update.
                    keyboard?.hide()
                    // 发送即重新锚定底部：用户从历史上方直接发送时，同帧内 isStreaming 与
                    // 新消息一起到位，立即回到底部并恢复后续的流式平滑跟底。
                    keepBottomAnchored = true
                    onSubmit(text)
                    submitScrollScope.launch {
                        // Cancel an old fling, then anchor the edited/replaced list after layout.
                        scrollState.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) { }
                        withFrameNanos { }
                        keepBottomAnchored = true
                        val last = scrollState.layoutInfo.totalItemsCount - 1
                        if (last >= 0) scrollState.requestScrollToItem(last)
                    }
                },
                onReasoningEffortChange = onReasoningEffortChange,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onContinue = onContinue,
                onAbortPausedRun = onAbortPausedRun,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onEditMessage = onEditMessage,
                onCancelMessageEdit = onCancelMessageEdit,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onBranchMessage = onBranchMessage,
                onQuestionDraftChanged = onQuestionDraftChanged,
                onSubmitQuestionAnswer = onSubmitQuestionAnswer,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                onEditAssistant = onEditAssistant,
                onAssistantSelected = onAssistantSelected,
                gptSpeedMode = gptSpeedMode,
                onCycleGptSpeedMode = onCycleGptSpeedMode,
                currentBrowserMessageId = currentBrowserMessageId,
                scrollToMessageId = scrollToMessageId,
                onScrollToMessageConsumed = onScrollToMessageConsumed,
                modifier = modifier,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentChatScaffold(
    visibleMessages: List<AgentChatMessageUi>,
    timelineEntries: List<AgentTimelineEntry>,
    hasMessages: Boolean,
    scrollState: LazyListState,
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    billedContextTokens: Int? = null,
    projectedContextTokens: Int? = null,
    billedHistoryTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    measuredContextTokens: Int? = null,
    contextDisplayPolicy: io.github.mangi.eta.ui.model.ContextDisplayPolicy = io.github.mangi.eta.ui.model.ContextDisplayPolicy(),
    billedOverheadTokens: Int? = null,
    activeRunContextWindow: Int? = null,
    autoCompressEnabled: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    isCompressingContext: Boolean = false,
    isWaitingForCompression: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    messageEdit: MessageEditUiState?,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    voiceState: VoiceModeState = VoiceModeState(),
    onStartVoiceMode: (VoiceEntryMode) -> Unit = {},
    onStopVoiceMode: () -> Unit = {},
    showEmptySuggestions: Boolean,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onBranchMessage: (String) -> Unit = {},
    onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit = { _, _, _ -> },
    onSubmitQuestionAnswer: (String, String) -> Unit = { _, _ -> },
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit = {},
    currentBrowserMessageId: String?,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    gptSpeedMode: io.github.mangi.eta.data.model.GptSpeedMode = io.github.mangi.eta.data.model.GptSpeedMode.NORMAL,
    onCycleGptSpeedMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val appearance = LocalAppearanceSettings.current
    val showMorphLoading = shouldShowMorphLoadingIndicator(
        messages = visibleMessages,
        isStreaming = isStreaming,
        isPaused = isPaused,
        isCompressingContext = isCompressingContext,
        enabled = appearance.morphLoadingIndicator,
        beforeResponseOnly = appearance.morphLoadingBeforeResponseOnly,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(
            left = 0.dp,
            top = 0.dp,
            right = 0.dp,
            bottom = 0.dp,
        ),
        bottomBar = {
            AgentChatBottomBar(
                collaborationConversationId = collaborationConversationId,
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = historyForContextSendBudget(
                    history, measuredContextTokens, autoCompressEnabled,
                ),
                billedContextTokens = billedContextTokens,
                projectedContextTokens = projectedContextTokens,
                billedHistoryTokens = billedHistoryTokens,
                requestOverheadTokens = requestOverheadTokens,
                measuredContextTokens = measuredContextTokens,
                contextDisplayPolicy = contextDisplayPolicy,
                billedOverheadTokens = billedOverheadTokens,
                activeRunContextWindow = activeRunContextWindow,
                autoCompressEnabled = autoCompressEnabled,
                showContextUsage = hasMessages,
                isStreaming = isStreaming,
                isPaused = isPaused,
                canContinueDisconnected = canContinueDisconnectedRun(visibleMessages),
                isCompressingContext = isCompressingContext,
                showMorphLoading = showMorphLoading,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
            conversationMentions = conversationMentions,
                messageEdit = messageEdit,
                assistantId = assistantId,
                voiceState = voiceState,
                onStartVoiceMode = onStartVoiceMode,
                onStopVoiceMode = onStopVoiceMode,
                onSubmit = onSubmit,
                onReasoningEffortChange = onReasoningEffortChange,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onContinue = onContinue,
                onAbortPausedRun = onAbortPausedRun,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                onEditAssistant = onEditAssistant,
                onAssistantSelected = onAssistantSelected,
                gptSpeedMode = gptSpeedMode,
                onCycleGptSpeedMode = onCycleGptSpeedMode,
            )
        },
    ) { innerPadding ->
        val bottomPadding = innerPadding.calculateBottomPadding()
        if (!hasMessages) {
            EmptyChatState(
                showSuggestions = showEmptySuggestions,
                onSuggestionClick = onSuggestionClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding),
            )
        } else {
            AgentConversationMessages(
                visibleMessages = visibleMessages,
                timelineEntries = timelineEntries,
                scrollState = scrollState,
                isStreaming = isStreaming,
                isPaused = isPaused,
                isCompressingContext = isCompressingContext,
                isWaitingForCompression = isWaitingForCompression,
                bottomInset = bottomPadding,
                keepBottomAnchored = keepBottomAnchored,
                onBottomAnchorChanged = onBottomAnchorChanged,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onBranchMessage = onBranchMessage,
                onQuestionDraftChanged = onQuestionDraftChanged,
                onSubmitQuestionAnswer = onSubmitQuestionAnswer,
                messageActionsEnabled = !isStreaming && !isPaused &&
                    !isCompressingContext &&
                    messageEdit == null,
                branchEnabled = !isCompressingContext && messageEdit == null,
                editTargetMessageId = messageEdit?.targetMessageId,
                currentBrowserMessageId = currentBrowserMessageId,
                scrollToMessageId = scrollToMessageId,
                onScrollToMessageConsumed = onScrollToMessageConsumed,
                modifier = Modifier
                    .fillMaxSize(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentConversationMessages(
    visibleMessages: List<AgentChatMessageUi>,
    scrollState: LazyListState,
    timelineEntries: List<AgentTimelineEntry>? = null,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    isCompressingContext: Boolean = false,
    isWaitingForCompression: Boolean = false,
    bottomInset: Dp,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSuggestionClick: (String) -> Unit = {},
    onRunTraceClick: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onBranchMessage: (String) -> Unit = {},
    onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit = { _, _, _ -> },
    onSubmitQuestionAnswer: (String, String) -> Unit = { _, _ -> },
    messageActionsEnabled: Boolean = false,
    branchEnabled: Boolean = false,
    editTargetMessageId: String? = null,
    currentBrowserMessageId: String? = null,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Independent, trace-gated telemetry also covers idle conversations. No frame loop.
    val scrollTraceEnabled = rememberChatScrollTraceEnabled()
    val chatListTrace = ChatScrollMonitor(state = scrollState, enabled = scrollTraceEnabled)
    // Observe attach/detach only; never change lazy keys or remount completed content.
    val diagnosticGeneration = StreamPerformanceDiagnostics.sessionGeneration.longValue
    val diagnosticList = remember(scrollState, diagnosticGeneration) { nextStreamDiagnosticListId() }
    val diagnosticListAttribution = StreamPerformanceDiagnostics.listAttribution(diagnosticList)
    traceChatListOwnerExecution(chatListTrace, scrollTraceEnabled)
    // Retain successful parses beyond individual lazy-row compositions.
    val completedMarkdownCache = remember(scrollState) { CompletedMarkdownCache() }
    // AgentChatBody supplies this projection so the initial tail anchor and the
    // rendered rows share one remembered full-list derivation. The standalone voice
    // panel still computes it here when it calls this renderer directly.
    val standaloneTimelineProjection = remember { AgentTimelineProjectionCache() }
    val projectedTimelineEntries = timelineEntries ?: remember(visibleMessages) {
        StreamPerformanceDiagnostics.measure("timeline.project", visibleMessages.size.toLong()) {
            standaloneTimelineProjection.project(visibleMessages)
        }
    }
    val expansionSaver = remember {
        listSaver<Map<String, Boolean>, String>(
            save = { value -> value.flatMap { (key, expanded) -> listOf(key, expanded.toString()) } },
            restore = { value -> value.chunked(2).filter { it.size == 2 }.associate { it[0] to (it[1] == "true") } },
        )
    }
    var workExpansionOverrides by rememberSaveable(stateSaver = expansionSaver) {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }
    val workAnimations = remember(scrollState) { mutableStateMapOf<String, WorkGroupAnimation>() }
    val mountedWorkSteps = remember(scrollState) { HashMap<String, MutableSet<String>>() }
    val workAnimationGeneration = remember(scrollState) { longArrayOf(0L) }
    fun finishWorkExit(groupKey: String, rowKey: String, generation: Long) {
        val current = workAnimations[groupKey] ?: return
        val next = current.finishExit(rowKey, generation)
        if (next == null) workAnimations.remove(groupKey)
        else if (next !== current) workAnimations[groupKey] = next
    }
    LaunchedEffect(projectedTimelineEntries) {
        val activeKeys = projectedTimelineEntries.filterIsInstance<AgentTimelineEntry.WorkProcess>().mapTo(mutableSetOf()) { it.key }
        if (workExpansionOverrides.keys.any { it !in activeKeys }) {
            workExpansionOverrides = workExpansionOverrides.filterKeys { it in activeKeys }
        }
        workAnimations.keys.toList().filter { it !in activeKeys }.forEach { workAnimations.remove(it) }
        // A streaming update may remove a pending row without composing it again.
        projectedTimelineEntries.filterIsInstance<AgentTimelineEntry.WorkProcess>().forEach { group ->
            val animation = workAnimations[group.key] ?: return@forEach
            val keys = group.messages.mapTo(HashSet()) { "work-step:${it.id}" }
            (animation.pendingExitKeys - keys).forEach { finishWorkExit(group.key, it, animation.generation) }
        }
    }
    workAnimations.forEach { (groupKey, animation) ->
        androidx.compose.runtime.key(groupKey, animation.generation) {
            LaunchedEffect(Unit) {
                // Only the click's first lazy measurement cohort may enter. Later
                // virtualization is settled, even while another row is animating.
                withFrameNanos { }
                animation.entrance.seal()
            }
        }
    }
    val retainedWorkSteps = workAnimations.mapValues { it.value.retainedStepKeys }
    val timelineRowsProjection = remember { AgentTimelineRowsCache() }
    val timelineRows = remember(projectedTimelineEntries, workExpansionOverrides, isStreaming, retainedWorkSteps) {
        StreamPerformanceDiagnostics.measure("timeline.project", projectedTimelineEntries.size.toLong()) {
            timelineRowsProjection.project(projectedTimelineEntries, workExpansionOverrides, isStreaming, retainedWorkSteps)
        }
    }
    LaunchedEffect(scrollToMessageId, timelineRows) {
        val target = scrollToMessageId ?: return@LaunchedEffect
        val index = timelineRows.indexOfFirst { it.containsMessageId(target) }
        if (index >= 0) {
            onBottomAnchorChanged(false)
            scrollState.animateScrollToItem(index)
            onScrollToMessageConsumed()
        } else if (timelineRows.isNotEmpty()) {
            onScrollToMessageConsumed()
        }
    }
    // Project onto the EXACT rows consumed by LazyColumn. Expansion and late
    // records move only the footer anchor, never the message or callback owner.
    // Generation only relaxes branching on PREVIOUS completed turns. The current
    // turn has no action bar until it closes, even between text/tool blocks.
    val turnFooters = remember(timelineRows, isStreaming, isPaused, isCompressingContext) {
        timelineRows.turnFooters(
            isStreaming = isStreaming,
            isCompressingContext = isCompressingContext,
            isPaused = isPaused,
        )
    }
    val finalResultMessageIds = remember(turnFooters) {
        turnFooters.values.mapTo(mutableSetOf()) { it.id }
    }
    // Reveal dependencies follow the projection's existing anchors, not a
    // second ownership heuristic. A stopped notice can own actions while an
    // earlier answer in that same turn is still revealing. New user boundaries
    // and completed footer anchors prevent one turn from blocking another.
    val footerRevealMessages = remember(timelineRows, turnFooters) {
        buildMap<String, List<AgentMessageUi>> {
            val answers = mutableListOf<AgentMessageUi>()
            timelineRows.forEach { row ->
                val message = (row as? AgentTimelineRow.Message)?.message
                if (message is UserMessageUi && !message.isSteerSupplement()) answers.clear()
                if (message is AgentMessageUi && message.content.isNotBlank()) answers.add(message)
                if (row.key in turnFooters) {
                    put(row.key, answers.toList())
                    answers.clear()
                }
            }
        }
    }
    // 流式消息的渲染会话按 id 提升到列表层持有：item 滚出视口被 LazyColumn 销毁后，
    // 滑回时复用同一解析会话与打字机进度，避免整段内容重新解析并重放显现动画。
    val fallbackStreamingStates = remember { mutableStateMapOf<String, StreamingMarkdownState>() }
    val streamingMarkdownStates = LocalStreamingMarkdownStates.current ?: fallbackStreamingStates
    // Messages already on screen before this live run must not replay. A text block that
    // arrives and ends in one snapshot is absent from this set, so it still gets a typewriter.
    val settledMessageIds = remember { mutableSetOf<String>() }
    var seededSettledMessages by remember { mutableStateOf(false) }
    if (!seededSettledMessages) {
        seededSettledMessages = true
        if (isStreaming || isPaused) visibleMessages.forEach { settledMessageIds.add(it.id) }
    }
    SideEffect {
        if (!isStreaming && !isPaused) {
            settledMessageIds.clear()
            visibleMessages.forEach { settledMessageIds.add(it.id) }
        }
    }
    LaunchedEffect(visibleMessages, streamingMarkdownStates) {
        val activeIds = visibleMessages.mapTo(mutableSetOf()) { it.id }
        streamingMarkdownStates.keys.retainAll(activeIds)
    }
    val telemetry = LocalAgentContextTelemetry.current
    val compressingChildren = telemetry.children.filter { it.isCompacting }
    val compressingItemCount = if (isCompressingContext || isWaitingForCompression || compressingChildren.isNotEmpty()) 1 else 0
    val bottomItemIndex = timelineRows.size + compressingItemCount
    val userMessageTargets = remember(timelineRows) { timelineRows.lazyUserMessageIndices() }
    val directionThreshold = with(LocalDensity.current) { 12.dp.toPx() }
    val directionTracker = remember(scrollState, directionThreshold) {
        ConversationNavigationDirectionTracker(directionThreshold)
    }
    var navigationDirection by remember(scrollState) { mutableStateOf(ConversationNavigationDirection.Down) }
    var messageNavigationJob by remember(scrollState) { mutableStateOf<Job?>(null) }
    DisposableEffect(scrollState) {
        onDispose { messageNavigationJob?.cancel() }
    }
    val isUserDragging by scrollState.interactionSource.collectIsDraggedAsState()
    // 手指拖走后的惯性也算用户滚动；跟底自己的 scrollBy 不能把这个标志打开。
    var isUserScrolling by remember { mutableStateOf(false) }
    // Observe user motion synchronously, before the asynchronous drag collector
    // and before another scheduled follow frame can mutate the list position.
    // 手指是否按在列表上。只观察、不消费事件。
    val pointerDown = remember { BooleanArray(1) }
    var programmaticUserScrolls by remember { mutableIntStateOf(0) }
    val userScrollConnection = remember(scrollState, directionTracker) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    // 有些滚动也用 UserInput 来源，但手指根本没按下：把某块内容带进可视区
                    // （BringIntoView，例如取得焦点的选区）、无障碍滚动。它们不算用户滑动，
                    // 不能关掉跟底；否则快速输出时跟底一断，已经落后的那截一帧画进输入框。
                    if (!isUserScrollGesture(pointerDown[0])) {
                        if (StreamPerformanceDiagnostics.enabled && programmaticUserScrolls < 6) {
                            programmaticUserScrolls++
                            val frames = Throwable().stackTrace
                            StreamPerformanceDiagnostics.note("scroll") {
                                // 跟底状态在后面才声明；同一时刻的 follow 行已由越线诊断记录。
                                "source=userInputWithoutPointer dy=${available.y.toInt()} " +
                                    "userScroll=$isUserScrolling stack=${compactStack(frames)}"
                            }
                        }
                        return Offset.Zero
                    }
                    messageNavigationJob?.cancel()
                    workAnimations.values.forEach { it.entrance.seal() }
                    isUserScrolling = true
                    navigationDirection = directionTracker.onScroll(available.y, userInput = true)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                directionTracker.endGesture()
                return Velocity.Zero
            }
        }
    }
    val densityScale = LocalDensity.current.density
    val coroutineScope = rememberCoroutineScope()
    val currentAnchor = rememberUpdatedState(keepBottomAnchored)
    val currentStreaming = rememberUpdatedState(isStreaming)
    val currentVisibleMessages = rememberUpdatedState(visibleMessages)
    val currentDragging = rememberUpdatedState(isUserDragging)

    LaunchedEffect(scrollState) {
        snapshotFlow { currentDragging.value to scrollState.isScrollInProgress }
            .collect { (dragging, inProgress) ->
                StreamPerformanceDiagnostics.record("scroll.state", value = if (dragging) 1 else 0)
                isUserScrolling = when {
                    dragging -> true
                    !inProgress -> false
                    else -> isUserScrolling
                }
            }
    }

    var hasLeftBottom by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState) {
        snapshotFlow {
            if (messageNavigationJob != null) null
            else Triple(isUserScrolling, scrollState.isConversationAtBottom(), currentAnchor.value)
        }
            .distinctUntilChanged()
            .collect { state ->
                val (userScrolling, atBottom, anchored) = state ?: return@collect
                if (userScrolling && !atBottom) hasLeftBottom = true
                val next = resolveKeepBottomAnchored(
                    current = anchored,
                    isUserDragging = userScrolling,
                    isAtBottom = atBottom,
                    hasLeftBottom = hasLeftBottom,
                )
                if (next && atBottom) hasLeftBottom = false
                if (next != anchored) onBottomAnchorChanged(next)
            }
    }

    var isBottomSettling by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(scrollState) {
        snapshotFlow {
            bottomFollowSettlingState(
                streaming = currentStreaming.value,
                anchored = currentAnchor.value,
                userScrolling = isUserScrolling,
            ) {
                hasPendingAssistantReveal(currentVisibleMessages.value) { message ->
                    val retained = streamingMarkdownStates[message.id]
                    retained != null && retained.revealedContent != message.content
                }
            }
        }
            .distinctUntilChanged()
            .collectLatest { state ->
                if (state == BottomFollowSettlingState.Disabled) {
                    isBottomSettling = false
                } else if (state == BottomFollowSettlingState.Active) {
                    isBottomSettling = true
                } else if (isBottomSettling) {
                    withFrameNanos { }
                    withFrameNanos { }
                    snapshotFlow { !scrollState.canScrollForward }.first { it }
                    isBottomSettling = false
                }
            }
    }

    var initialBottomPositionPending by remember(scrollState) { mutableStateOf(true) }
    val currentScrollTarget by rememberUpdatedState(scrollToMessageId)
    val shouldFollowBottom by rememberUpdatedState(
        resolveBottomFollowEnabled(
            isStreaming = isStreaming,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = initialBottomPositionPending || isUserScrolling || messageNavigationJob != null,
            isBottomSettling = isBottomSettling,
        )
    )
    // 跟底时上提让尾部一直停在静止线上：列表变高多少，同一帧就上提多少，下沿不动。
    // 以前点开最底部一行时先暂停上提，让内容从上沿往下长过静止线、再由跟底滚动追回来，
    // 标签会先不动、再被推上去，看起来像折了两次。现在展开从下沿长出（见 tailDetailsEnter），
    // 上提照常，标签随动画一帧一帧往上让开。
    val shouldLiftTail = shouldFollowBottom
    // 被盖住但仍露在屏幕上时不走整列离屏裁剪。完全打开时仍按原来的条件。
    val shouldClipTail = shouldClipChatTail(
        isStreaming = isStreaming,
        isBottomSettling = isBottomSettling,
        keepBottomAnchored = keepBottomAnchored,
        isUserScrolling = isUserScrolling,
        isUserDragging = isUserDragging,
        navigationActive = messageNavigationJob != null || scrollToMessageId != null,
        routeCovered = routeCovered,
    )
    // 附件、输入框换行和 IME 改的是实际尾部留白，不是新消息增长。
    // 静态时没有跟底控制器接手；只在仍锚定且视口静止线确实变化时重新停靠。
    AnchorChatTailOnViewportChange(scrollState, bottomItemIndex) {
        currentAnchor.value && !currentStreaming.value && !isBottomSettling &&
            !initialBottomPositionPending && !pointerDown[0] && !currentDragging.value &&
            !isUserScrolling && messageNavigationJob == null && currentScrollTarget == null
    }
    SideEffect {
        traceChatListOwnerCommit(
            trace = chatListTrace,
            enabled = scrollTraceEnabled,
            isUserDragging = isUserDragging,
            isUserScrolling = isUserScrolling,
            isBottomSettling = isBottomSettling,
            keepBottomAnchored = keepBottomAnchored,
            shouldClipTail = shouldClipTail,
            shouldFollowBottom = shouldFollowBottom,
            navigationActive = messageNavigationJob != null,
        )
    }
    val isListScrollable by remember {
        derivedStateOf { scrollState.canScrollForward || scrollState.canScrollBackward }
    }
    var streamFilledViewport by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming, isListScrollable) {
        streamFilledViewport = if (isStreaming) streamFilledViewport || isListScrollable else false
    }
    // 点击回调里问一次：这一行下面的内容会不会停在原处。只读 State，不参与组合；
    // remember 后引用不变，作为 CompositionLocal 提供时不会让整页重组。
    val expansionPinnedNow: () -> Boolean = remember(scrollState) {
        {
            resolveExpansionHoldsBottom(
                following = shouldFollowBottom,
                arrangedToBottom = shouldPinConversationToBottom(currentStreaming.value, streamFilledViewport),
                listScrollable = isListScrollable,
            )
        }
    }
    // 下沿被钉住的展开期间，跟底滚动每帧把新增高度一次吃掉，不走平滑加速。
    // 平滑跟底第一帧不动、之后慢慢加速，其余全靠绘制上提；上提一旦超过列表尾部留白，
    // 尾部哨兵被挤出可视区，上提归零，内容一帧掉下去三百多像素。展开动画本身已经是平滑的，
    // 这段时间里标签随动画逐帧上移即可。只在协程里读，写入不触发重组。
    var bottomSnapUntilNanos by remember { mutableLongStateOf(0L) }
    val expansionHoldsBottom: () -> Boolean = remember(scrollState) {
        {
            expansionPinnedNow().also { pinned ->
                if (pinned) bottomSnapUntilNanos = System.nanoTime() + EXPANSION_BOTTOM_SNAP_NANOS
            }
        }
    }
    // A measured/recovered message can be virtualized during a large expansion. Its stable
    // key must not replay the root opacity animation when composed again in the same chat.
    val appearedMessageKeys = remember { HashSet<String>() }
    // 点开工具或推理后，逐帧记下列表状态：跟底、上提、暂停上提、哨兵相对静止线的位置、
    // 用户是否在拖动。只在点击窗口内运行，读 layoutInfo 不参与组合。
    LaunchedEffect(scrollState) {
        snapshotFlow { StreamPerformanceDiagnostics.probeRequests.intValue }
            .collectLatest { request ->
                if (request == 0) return@collectLatest
                while (StreamPerformanceDiagnostics.probing) {
                    val frameNanos = withFrameNanos { it }
                    StreamPerformanceDiagnostics.probeListSample(frameNanos) {
                        val info = scrollState.layoutInfo
                        // shouldLiftTail 是组合时的快照，这里按当前的跟底状态重新算。
                        val liftingNow = shouldFollowBottom
                        val lift = if (liftingNow) {
                            resolveFollowTailLag(true, scrollState.followTailOverflow()).liftPx.toInt()
                        } else 0
                        "follow=$shouldFollowBottom lift=$liftingNow liftPx=$lift pinned=${expansionPinnedNow()} snap=${System.nanoTime() < bottomSnapUntilNanos} " +
                            "userScroll=$isUserScrolling overflowPx=${scrollState.followTailOverflow()} " +
                            "first=${scrollState.firstVisibleItemIndex}:${scrollState.firstVisibleItemScrollOffset} " +
                            "visible=${info.visibleItemsInfo.size} total=${info.totalItemsCount}"
                    }
                }
            }
    }
    // 快速输出时尾部越过静止线、画进输入框的诊断。只在布局或跟底状态变化时取样（snapshotFlow），
    // 不额外请求帧。跟底上提时列表裁在静止线上，真正画进输入框的只能是没在上提的时候；
    // 上提时越线的部分被裁掉，只计数。跟底的各个条件变化、越线开始和结束各记一行。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val generation = StreamPerformanceDiagnostics.sessionGeneration.longValue
            generation to (StreamPerformanceDiagnostics.enabled && (currentStreaming.value || isBottomSettling))
        }
            .distinctUntilChanged()
            .collectLatest { (generation, active) ->
                if (!active) return@collectLatest
                var lastState = ""
                var breachSamples = 0
                var breachMaxPx = 0
                snapshotFlow {
                    if (StreamPerformanceDiagnostics.sessionGeneration.longValue != generation ||
                        !StreamPerformanceDiagnostics.enabled) return@snapshotFlow null
                    val info = scrollState.layoutInfo
                    val sentinel = info.visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey }
                    val last = info.visibleItemsInfo.lastOrNull()
                    val tailBottom = resolveTailBottomPx(
                        sentinelBottom = sentinel?.let { it.offset + it.size },
                        lastVisibleIndex = last?.index,
                        lastVisibleBottom = last?.let { it.offset + it.size },
                        totalItems = info.totalItemsCount,
                    )
                    val restLine = info.viewportEndOffset - info.afterContentPadding
                    val lifting = shouldFollowBottom
                    val lift = if (lifting) {
                        resolveFollowTailLag(true, scrollState.followTailOverflow()).liftPx.toInt()
                    } else 0
                    TailBreachSample(
                        overPx = resolveTailDrawnOverflow(tailBottom, restLine, lift),
                        tailBottomPx = tailBottom,
                        restLinePx = restLine,
                        padPx = info.afterContentPadding,
                        sentinelVisible = sentinel != null,
                        lastIndex = last?.index ?: -1,
                        totalItems = info.totalItemsCount,
                        lifting = lifting,
                        state = "follow=$lifting streaming=${currentStreaming.value} " +
                            "anchored=${currentAnchor.value} userScroll=$isUserScrolling " +
                            "settling=$isBottomSettling initialPending=$initialBottomPositionPending " +
                            "nav=${messageNavigationJob != null}",
                    )
                }
                    .distinctUntilChanged()
                    .collect { sample ->
                        if (sample == null || StreamPerformanceDiagnostics.sessionGeneration.longValue != generation ||
                            !StreamPerformanceDiagnostics.enabled) return@collect
                        val over = sample.overPx
                        if (sample.state != lastState) {
                            StreamPerformanceDiagnostics.note("follow") {
                                "${sample.state} tailVisible=${sample.sentinelVisible} overPx=$over padPx=${sample.padPx}"
                            }
                            lastState = sample.state
                        }
                        if (over != null && over > 1) {
                            StreamPerformanceDiagnostics.record(
                                if (sample.lifting) "tail.clippedPx" else "tail.breachPx", value = over.toLong(),
                            )
                        }
                        if (!sample.lifting && over != null && over > 1) {
                            if (breachSamples == 0) {
                                StreamPerformanceDiagnostics.note("breach") {
                                    "state=start overPx=$over tailBottomPx=${sample.tailBottomPx} " +
                                        "restLinePx=${sample.restLinePx} padPx=${sample.padPx} " +
                                        "sentinel=${sample.sentinelVisible} last=${sample.lastIndex}/${sample.totalItems} " +
                                        sample.state
                                }
                            }
                            breachSamples++
                            breachMaxPx = maxOf(breachMaxPx, over)
                        } else if (breachSamples > 0) {
                            StreamPerformanceDiagnostics.note("breach") {
                                "state=end samples=$breachSamples maxOverPx=$breachMaxPx ${sample.state}"
                            }
                            breachSamples = 0
                            breachMaxPx = 0
                        }
                    }
            }
    }
    val viewportRecovery = remember(scrollState) {
        BottomFollowViewportRecovery(scrollState, ChatBottomSentinelKey)
    }
    val canOwnWorkExpansionViewport: () -> Boolean = remember(scrollState) {
        {
            resolveWorkExpansionViewportOwnership(
                keepBottomAnchored = currentAnchor.value,
                initialBottomPositionPending = initialBottomPositionPending,
                pointerDown = pointerDown[0],
                isUserDragging = currentDragging.value,
                isUserScrolling = isUserScrolling,
                navigationActive = messageNavigationJob != null || currentScrollTarget != null,
            )
        }
    }

    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val bottomFollowDecisions = remember(scrollState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }

    // Initial positioning owns the list only once. New timeline items and network
    // completion must go through the continuous follow controller below.
    LaunchedEffect(scrollState) {
        try {
            val initial = snapshotFlow {
                InitialBottomPosition(
                    bottomItemIndex = currentBottomItemIndex,
                    hasLayout = scrollState.layoutInfo.visibleItemsInfo.isNotEmpty(),
                    anchored = currentAnchor.value,
                    interrupted = isUserScrolling || messageNavigationJob != null || currentScrollTarget != null,
                )
            }.first { it.ready }
            if (initial.shouldPosition) {
                StreamPerformanceDiagnostics.record("follow.initialSnap")
                snapListToBottom(scrollState, initial.bottomItemIndex) {
                    currentAnchor.value && !isUserScrolling &&
                        messageNavigationJob == null && currentScrollTarget == null
                }
            }
        } finally {
            initialBottomPositionPending = false
        }
    }

    // 流式输出及渲染收尾期间发布最新的跟底距离。历史消息中的步骤/思考展开同样会改变
    // 列表高度，但那是用户主动查看内容，不能被误判成尾部文字增长。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            // Preserve the existing gate; disabling must still send a zero target.
            if (!shouldFollowBottom) return@snapshotFlow InactiveBottomFollowLayout
            val layoutInfo = scrollState.layoutInfo
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key == ChatBottomSentinelKey
            }
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            BottomFollowLayout(
                enabled = true,
                bottomItemIndex = currentBottomItemIndex,
                // 哨兵被挤出可视区、但最后一段内容还可见时，用它的下沿，不再按整屏估算。
                sentinelBottom = resolveTailBottomPx(
                    sentinelBottom = sentinel?.let { it.offset + it.size },
                    lastVisibleIndex = lastVisible?.index,
                    lastVisibleBottom = lastVisible?.let { it.offset + it.size },
                    totalItems = layoutInfo.totalItemsCount,
                ),
                // 视口已扣除底栏高度；这里只扣列表自身的尾部留白。
                // 跟底目标仍是 afterContentPadding 之前的正文边界。
                viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                lastVisibleIndex = lastVisible?.index,
                viewportSizePx = layoutInfo.viewportSize.height,
                canScrollForward = scrollState.canScrollForward,
                lastVisibleOffset = lastVisible?.offset,
            )
        }
            .distinctUntilChanged()
            .collect { layout ->
                val decision = resolveBottomFollowDecision(
                    enabled = layout.enabled,
                    bottomItemIndex = layout.bottomItemIndex,
                    sentinelBottom = layout.sentinelBottom,
                    viewportEnd = layout.viewportEnd,
                    lastVisibleIndex = layout.lastVisibleIndex,
                    viewportSizePx = layout.viewportSizePx,
                    canScrollForward = layout.canScrollForward,
                )
                StreamPerformanceDiagnostics.record("follow.decision", value = decision.scrollByPx.toLong())
                bottomFollowDecisions.trySend(decision)
            }
    }

    // One controller retains velocity across layout targets, including line-height
    // steps. Neither a missing sentinel nor stream completion may bypass it.
    LaunchedEffect(scrollState, bottomFollowDecisions, densityScale) {
        val motion = BottomFollowMotion()
        var remainingDistancePx = 0f
        var previousFrameNanos: Long? = null
        fun reset() {
            remainingDistancePx = 0f
            previousFrameNanos = null
            motion.reset()
        }
        fun accept(decision: BottomFollowDecision) {
            remainingDistancePx = decision.scrollByPx.coerceAtLeast(0).toFloat()
        }
        while (currentCoroutineContext().isActive) {
            if (remainingDistancePx <= 0f) {
                reset()
                accept(bottomFollowDecisions.receive())
            }
            while (true) {
                accept(bottomFollowDecisions.tryReceive().getOrNull() ?: break)
            }
            if (!shouldFollowBottom || isUserScrolling || messageNavigationJob != null) {
                reset()
                continue
            }
            if (remainingDistancePx <= 0f) continue
            val frameNanos = withFrameNanos { it }
            previousFrameNanos?.let {
                StreamPerformanceDiagnostics.record("follow.frameGap", frameNanos - it)
            }
            previousFrameNanos = frameNanos
            while (true) {
                accept(bottomFollowDecisions.tryReceive().getOrNull() ?: break)
            }
            if (!shouldFollowBottom || isUserScrolling || messageNavigationJob != null) {
                reset()
                continue
            }
            val snapping = System.nanoTime() < bottomSnapUntilNanos
            val latestOverflow = scrollState.layoutInfo.measuredTailOverflow()
            val expansionOwnsViewport = viewportRecovery.hasActiveExpansion(canOwnWorkExpansionViewport())
            if (expansionOwnsViewport && latestOverflow == null) {
                // The measured-anchor owner recovers unknown-tail expansion. Do not let the
                // ordinary one-viewport fallback race it; retain the pending target for expiry.
                motion.reset()
                previousFrameNanos = null
                continue
            }
            if ((snapping || expansionOwnsViewport) && latestOverflow != null) {
                remainingDistancePx = latestOverflow.coerceAtLeast(0).toFloat()
            }
            val step = if (snapping) {
                motion.reset()
                remainingDistancePx
            } else {
                val smoothStep = motion.step(remainingDistancePx, frameNanos, densityScale)
                val layout = scrollState.layoutInfo
                // 绘制上提不能超过列表底部留白。先用真实滚动补掉超出缓冲的差额，
                // 其余仍由原速度控制器平滑追赶；不能靠扩大裁剪或移走已被裁空的列表。
                // 再收成整像素，避免小数滚动和整数上提把卡片底边顶开 1 像素。
                snapFollowScrollStep(
                    resolveBottomFollowViewportStep(
                        smoothStepPx = smoothStep,
                        measuredOverflowPx = layout.measuredTailOverflow(),
                        afterContentPaddingPx = layout.afterContentPadding,
                    ),
                    remainingDistancePx,
                )
            }
            // Within the draw buffer, the first frame still only establishes timing.
            if (step <= 0f) continue
            var consumedStep = 0f
            try {
                scrollState.scroll {
                    if (!isUserScrolling && messageNavigationJob == null && shouldFollowBottom) {
                        // Post-layout expansion recovery may already have consumed this target.
                        // Recheck fresh geometry inside the scroll owner, not the queued decision.
                        val currentStep = resolveFollowScrollStepAfterRecovery(
                            step, scrollState.layoutInfo.measuredTailOverflow(),
                            expansionOwnsViewport = viewportRecovery.hasActiveExpansion(canOwnWorkExpansionViewport()),
                        )
                        consumedStep = StreamPerformanceDiagnostics.measure("follow.scroll") { scrollBy(currentStep) }
                        if (StreamPerformanceDiagnostics.probing) {
                            StreamPerformanceDiagnostics.probeNote("follow") {
                                "stepPx=${"%.1f".format(step)} consumedPx=${"%.1f".format(consumedStep)} " +
                                    "remainingPx=${"%.1f".format(remainingDistancePx - consumedStep)}"
                            }
                        }
                        StreamPerformanceDiagnostics.record("follow.step", value = (consumedStep * 1000).toLong())
                    }
                }
                if (consumedStep > 0f) {
                    remainingDistancePx = (remainingDistancePx - consumedStep).coerceAtLeast(0f)
                } else reset()
            } catch (cancelled: CancellationException) {
                StreamPerformanceDiagnostics.record("follow.cancelled")
                if (!currentCoroutineContext().isActive) throw cancelled
                reset()
            }
        }
    }

    // 输入器悬浮在会话之上：视口铺满到屏幕底，输入框四周透明、能看到后面的消息。
    // 跟底输出期间（思考/正文生成、未手动滑动），卡片/正文每长一行，跟底滚动要晚几帧
    // 才追上。绘制阶段会把已经量到的尾部上提；输出及渲染收尾时才裁在输入框上方的静止线，
    // 覆盖超快输出在 isStreaming 结束后、列表滚动尚未完成的过渡帧。
    // 静态锚定只用于尾部停靠，不是永久绘制遮挡；无需先滑动，透明周边也能透出消息。
    // 用户一拖动或跳转消息，输出保护也立即解除。
    val restClip = remember(bottomInset) { ComposerRestClip(bottomInset + ConversationComposerGap) }
    // 尾部这一帧量不到时不能把上提清零，否则卡片会掉进输入框再弹回来。
    val heldTailLift = remember { intArrayOf(0) }
    // The callback reports message.id, but a work step's lazy key is work-step:<id>.
    // Both row branches capture the actual entry key before ThinkingRow changes its height.
    val onThinkingRowToggle: (String, Boolean) -> Unit = { rowKey, willExpand ->
        val now = System.nanoTime()
        val alreadyPinned = expansionHoldsBottom()
        val captured = if (willExpand) {
            viewportRecovery.beginThinkingExpansion(
                rowKey = rowKey,
                expiresAtNanos = now + WORK_EXPANSION_RECOVERY_NANOS,
                canOwnViewport = canOwnWorkExpansionViewport() &&
                    (alreadyPinned || scrollState.isConversationAtBottom()),
            )
        } else {
            viewportRecovery.cancelWorkExpansion(rowKey)
            false
        }
        if (alreadyPinned || captured ||
            (!willExpand && canOwnWorkExpansionViewport() && scrollState.isConversationAtBottom())
        ) {
            bottomSnapUntilNanos = now + EXPANSION_BOTTOM_SNAP_NANOS
        }
    }
    Box(
        modifier = modifier
            .clipToBounds()
            // clipRect 和普通 clip 都裁不到子级 graphicsLayer（跟底上提、思考卡片的离屏纹理）。
            // 先把整列画进一张按静止线裁切的离屏纹理，文字才不会露进输入框。
            .then(
                if (shouldClipTail) {
                    Modifier.graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        clip = true
                        shape = restClip
                    }
                } else {
                    Modifier
                },
            )
            .drawWithContent {
                // 不跟底时不要读 layoutInfo，否则每次滑动都让绘制层失效。
                // 上提用的是本帧布局。输出很快时，新长出的一行会先画过静止线、进到输入框里。
                // 跟底期间一律裁在静止线；上提仍然把已经量到的尾部停在线上方。
                if (!shouldClipTail) {
                    drawContent()
                    return@drawWithContent
                }
                val restLine = (size.height - (bottomInset + ConversationComposerGap).toPx()).coerceAtLeast(0f)
                clipRect(bottom = restLine) { this@drawWithContent.drawContent() }
            },
    ) {
        val speechPrefaceProjection = remember { AgentSpeechPrefaceCache() }
        val speechPrefaces = remember(visibleMessages, finalResultMessageIds) {
            StreamPerformanceDiagnostics.measure("timeline.prefaces", visibleMessages.size.toLong()) {
                speechPrefaceProjection.project(visibleMessages, finalResultMessageIds)
            }
        }
        val messageActions = remember { ChatMessageActions() }
        SideEffect {
            messageActions.onSuggestionClick = onSuggestionClick
            messageActions.onRunTraceClick = onRunTraceClick
            messageActions.onOpenBrowser = onOpenBrowser
            messageActions.onEditMessage = onEditMessage
            messageActions.onDeleteMessage = onDeleteMessage
            messageActions.onRegenerateMessage = onRegenerateMessage
            messageActions.onBranchMessage = onBranchMessage
            messageActions.onQuestionDraftChanged = onQuestionDraftChanged
            messageActions.onSubmitQuestionAnswer = onSubmitQuestionAnswer
            // Commit count only (ns=0): proves this content lambda was applied, not its cost.
            StreamPerformanceDiagnostics.record("chat.content.commit", value = 1)
        }
        LazyColumn(
            state = scrollState,
            verticalArrangement = if (shouldPinConversationToBottom(isStreaming, streamFilledViewport)) {
                Arrangement.Bottom
            } else {
                Arrangement.Top
            },
            modifier = Modifier
                .streamDiagnosticMeasure("list.measure", diagnosticListAttribution)
                .streamDiagnosticPlacement("list.place", diagnosticListAttribution)
                .fillMaxSize()
                .graphicsLayer {
                    // 不跟底时不读滚动位置。滑动中读取会让这一层每帧失效，子内容的离屏纹理被整列重录。
                    translationY = if (shouldLiftTail) {
                        val overflow = scrollState.followTailOverflow()
                        // 与滚动步长同一套整像素。这一帧量不到尾部时沿用上一帧，避免底边掉下去再弹回。
                        -nextHeldTailLift(
                            shouldLift = true,
                            overflowPx = overflow,
                            heldPx = heldTailLift[0],
                        ).also { heldTailLift[0] = it }.toFloat()
                    } else {
                        heldTailLift[0] = 0
                        0f
                    }
                }
                .onGloballyPositioned {
                    StreamPerformanceDiagnostics.recordListGeometry(diagnosticList, scrollState, visibleMessages.size)
                    // Post-layout, before draw: pinned work insertion preserves a measured
                    // pre-click key even when the tail is outside the lazy measurement window.
                    // Normal follow still consumes only excess beyond the existing draw buffer.
                    val consumed = viewportRecovery.recover(
                        canRecoverExpansion = canOwnWorkExpansionViewport,
                    ) {
                        shouldFollowBottom && canOwnWorkExpansionViewport()
                    }
                    if (consumed > 0f) {
                        StreamPerformanceDiagnostics.record(
                            "follow.viewportRecovery", value = (consumed * 1000).toLong(),
                        )
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            pointerDown[0] = event.changes.any { it.pressed }
                        }
                    }
                }
                .nestedScroll(userScrollConnection)
                // Navigation already emits one explicit click/long-press haptic.
                .then(if (messageNavigationJob == null) Modifier.scrollEndHaptic() else Modifier)
                .overScrollVertical(),
            // 最后一条静止时停在输入框上方 14dp；手动滑动时内容可以滚到输入框后面。
            contentPadding = PaddingValues(
                top = 14.dp,
                bottom = ConversationComposerGap + bottomInset,
            ),
            overscrollEffect = null,
        ) {
            items(
                items = timelineRows,
                key = { it.key },
                contentType = { row ->
                    when (row) {
                        is AgentTimelineRow.Message -> "message"
                        is AgentTimelineRow.WorkHeader -> "work-header"
                        is AgentTimelineRow.WorkStep -> when (row.message) {
                            is ToolActivityMessageUi -> "work-tool"
                            is ThinkingMessageUi -> "work-thinking"
                            else -> "work-summary"
                        }
                    }
                },
            ) { entry ->
                // No UI container or rendering modifier: record committed row identity only.
                ChatRowTrace(
                    rowKey = entry.key,
                    rowType = when (entry) {
                        is AgentTimelineRow.Message -> when (entry.message) {
                            is UserMessageUi -> "user"
                            is AgentMessageUi -> "agent"
                            is ThinkingMessageUi -> "thinking"
                            is ToolActivityMessageUi -> "tool"
                            else -> "message"
                        }
                        is AgentTimelineRow.WorkHeader -> "work-header"
                        is AgentTimelineRow.WorkStep -> when (entry.message) {
                            is ToolActivityMessageUi -> "work-tool"
                            is ThinkingMessageUi -> "work-thinking"
                            else -> "work-summary"
                        }
                    },
                    enabled = scrollTraceEnabled,
                )
                // Keep the row key/index and animate its root, including its footer.
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalExpansionHoldsBottom provides expansionHoldsBottom,
                    LocalCompletedMarkdownCache provides completedMarkdownCache,
                    LocalStreamDiagnosticRow provides StreamPerformanceDiagnostics.rowAttribution(
                        diagnosticList, entry.key, timelineDiagnosticRowType(entry)),
                ) {
                val diagnosticRow = LocalStreamDiagnosticRow.current
                val firstMessageAppearance = if (entry is AgentTimelineRow.Message) {
                    // Retire an existing root's appearance on an explicit work toggle even
                    // if LazyColumn retains its composition while temporarily unmeasured.
                    // New message keys still get the normal fade; work height animation is unchanged.
                    remember(entry.key, workExpansionOverrides) { appearedMessageKeys.add(entry.key) }
                } else false
                Column(
                    modifier = Modifier
                        .streamDiagnosticMeasure("row.measure", diagnosticRow)
                        .streamDiagnosticPlacement("row.place", diagnosticRow)
                        .streamDiagnosticDraw("row.draw", diagnosticRow)
                        .fillMaxWidth().then(
                        if (entry is AgentTimelineRow.Message) Modifier.animateItem(
                            fadeInSpec = if (firstMessageAppearance) tween(durationMillis = 180) else null,
                            placementSpec = null,
                            fadeOutSpec = null,
                        ) else Modifier,
                    ),
                ) {
                when (entry) {
                    is AgentTimelineRow.Message -> {
                        val message = entry.message
                        ChatMessageItem(
                            message = message,
                            speechPreface = (message as? AgentMessageUi)?.let { speechPrefaces[it.id] }.orEmpty(),
                            retainedStreamingState = (message as? AgentMessageUi)
                                ?.takeIf { agentMessage ->
                                    agentMessage.isStreaming ||
                                        streamingMarkdownStates.containsKey(agentMessage.id) ||
                                        (
                                            (isStreaming || isPaused) &&
                                                agentMessage.id !in settledMessageIds &&
                                                agentMessage.content.isNotEmpty()
                                            )
                                }
                                ?.let { agentMessage ->
                                    streamingMarkdownStates.getOrPut(agentMessage.id) {
                                        StreamingMarkdownState()
                                    }
                                },
                            actions = messageActions,
                            showBrowserShortcut = message is ToolActivityMessageUi &&
                                message.toolName == "browser_use" &&
                                message.id == currentBrowserMessageId,
                            enableLivePreview = !isStreaming,
                            // UserMessageBubble ignores these switches; its toolbar is unchanged.
                            showCopyAction = false,
                            showMessageActions = false,
                            messageActionsEnabled = messageActionsEnabled && !isStreaming && !isPaused,
                            branchEnabled = branchEnabled,
                            isEditing = message.id == editTargetMessageId,
                            isPaused = isPaused,
                            onThinkingToggle = if (message is ThinkingMessageUi) {
                                { _, willExpand -> onThinkingRowToggle(entry.key, willExpand) }
                            } else null,
                            // Keep this modifier stable. Attaching fadeIn only after the run
                            // ends replays appearance on the already-visible answer.
                            modifier = Modifier,
                        )
                    }

                    is AgentTimelineRow.WorkHeader -> {
                        AgentWorkProcessHeader(
                            messages = entry.group.messages,
                            isPaused = isPaused,
                            expanded = entry.expanded,
                            // Deleted retained keys no longer own a card slice or its bottom gap.
                            hasVisibleSteps = entry.expanded || entry.group.messages.any { message ->
                                "work-step:${message.id}" in retainedWorkSteps[entry.key].orEmpty()
                            },
                            onToggle = {
                                // Retire the root fade for every message that already exists
                                // before this explicit toggle mutates the projection. LazyColumn
                                // composes a row only once it enters the viewport, so a pre-click
                                // answer that was never mounted would otherwise be mistaken for a
                                // new one and replay the 180ms fade on collapse/expand. Messages
                                // created after this click are absent here and keep their fade.
                                markExistingMessages(appearedMessageKeys, timelineRows, visibleMessages)
                                val now = System.nanoTime()
                                val alreadyPinned = expansionHoldsBottom()
                                val captured = if (!entry.expanded) {
                                    viewportRecovery.beginWorkExpansion(
                                        groupKey = entry.key,
                                        stepKeys = entry.group.messages.mapTo(HashSet<Any>()) { "work-step:${it.id}" },
                                        expiresAtNanos = now + WORK_EXPANSION_RECOVERY_NANOS,
                                        canOwnViewport = canOwnWorkExpansionViewport() &&
                                            (alreadyPinned || scrollState.isConversationAtBottom()),
                                    )
                                } else {
                                    // Closing a group also retires a captured inner thinking row.
                                    viewportRecovery.cancelWorkExpansion(
                                        entry.key, entry.group.messages.map { "work-step:${it.id}" },
                                    )
                                    false
                                }
                                val pinned = alreadyPinned || captured ||
                                    (entry.expanded && canOwnWorkExpansionViewport() &&
                                        scrollState.isConversationAtBottom())
                                if (pinned) bottomSnapUntilNanos = now + EXPANSION_BOTTOM_SNAP_NANOS
                                workAnimations[entry.key]?.entrance?.seal()
                                val animation = newWorkGroupAnimation(
                                    generation = ++workAnimationGeneration[0],
                                    expanded = !entry.expanded,
                                    fromBottom = pinned,
                                    stepKeys = entry.group.messages.map { "work-step:${it.id}" },
                                    mountedStepKeys = mountedWorkSteps[entry.key].orEmpty(),
                                )
                                if (animation.expanded || animation.pendingExitKeys.isNotEmpty()) {
                                    workAnimations[entry.key] = animation
                                } else workAnimations.remove(entry.key)
                                val token = StreamPerformanceDiagnostics.markToggle("work", !entry.expanded)
                                StreamPerformanceDiagnostics.probeEvent(
                                    token,
                                    "item",
                                    "steps=${entry.group.messages.size} anchor=${if (pinned) "bottom" else "top"}",
                                )
                                workExpansionOverrides = workExpansionOverrides + (entry.key to !entry.expanded)
                            },
                        )
                    }
                    is AgentTimelineRow.WorkStep -> {
                        val message = entry.message
                        val retainedState = if (message is ThinkingMessageUi &&
                            (message.isStreaming || streamingMarkdownStates.containsKey(message.id))) {
                            streamingMarkdownStates.getOrPut(message.id) { StreamingMarkdownState() }
                        } else null
                        val animation = workAnimations[entry.groupKey]
                        // Do not key this state by generation/target: a rapid opposite
                        // click reverses the same transition at its current height.
                        val appear = remember(entry.key) {
                            val animate = entry.expanded && animation?.expanded == true &&
                                animation.entrance.claim(entry.key)
                            MutableTransitionState(entry.expanded && !animate)
                        }
                        SideEffect { appear.targetState = entry.expanded }
                        DisposableEffect(entry.groupKey, entry.key) {
                            mountedWorkSteps.getOrPut(entry.groupKey) { HashSet() }.add(entry.key)
                            onDispose {
                                mountedWorkSteps[entry.groupKey]?.let { keys ->
                                    keys.remove(entry.key)
                                    if (keys.isEmpty()) mountedWorkSteps.remove(entry.groupKey)
                                }
                                // An unmeasured exit must not keep the projection alive.
                                workAnimations[entry.groupKey]?.let { current ->
                                    finishWorkExit(entry.groupKey, entry.key, current.generation)
                                }
                            }
                        }
                        LaunchedEffect(entry.expanded, animation?.generation) {
                            if (!entry.expanded && animation != null) {
                                snapshotFlow { appear.isIdle && !appear.currentState && !appear.targetState }
                                    .first { it }
                                finishWorkExit(entry.groupKey, entry.key, animation.generation)
                            }
                        }
                        val fromBottom = animation?.fromBottom ?: false
                        AnimatedVisibility(
                            visibleState = appear,
                            enter = tailDetailsEnter(fromBottom = fromBottom),
                            exit = tailDetailsExit(toBottom = fromBottom),
                        ) {
                        WorkProcessCardSlice(
                            part = if (entry.isLast) WorkProcessCardPart.Last else WorkProcessCardPart.Middle,
                            // The 4dp exterior card gap belongs outside visibility.
                            // Transfer it to the Whole header only when all exits finish;
                            // neither add a second gap at click nor jump at disposal.
                            modifier = if (entry.isLast) Modifier.layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, (placeable.height - 4.dp.roundToPx()).coerceAtLeast(0)) {
                                    placeable.placeRelative(0, 0)
                                }
                            } else Modifier,
                        ) {
                            ChatMessageItem(
                                message = message,
                                actions = messageActions,
                                retainedStreamingState = retainedState,
                                showBrowserShortcut = message is ToolActivityMessageUi &&
                                    message.id == currentBrowserMessageId,
                                enableLivePreview = !isStreaming,
                                compact = true,
                                isPaused = isPaused,
                                onThinkingToggle = if (message is ThinkingMessageUi) {
                                    { _, willExpand -> onThinkingRowToggle(entry.key, willExpand) }
                                } else null,
                                modifier = Modifier.padding(
                                    top = if (entry.isFirst) 2.dp else 0.dp,
                                    bottom = if (entry.isLast) 8.dp else 0.dp,
                                ),
                            )
                        }
                        }
                        if (entry.isLast) Spacer(Modifier.height(4.dp))
                    }
                }
                turnFooters[entry.key]?.let { owner ->
                    val revealPending = footerRevealMessages[entry.key].orEmpty().any { answer ->
                        val retained = streamingMarkdownStates[answer.id]
                        answer.isStreaming ||
                            (retained != null && retained.revealedContent != answer.content && (isStreaming || isPaused)) ||
                            (retained == null && (isStreaming || isPaused) && answer.id !in settledMessageIds)
                    }
                    AgentTurnFooter(
                        message = owner,
                        actions = messageActions,
                        revealPending = revealPending,
                        isRunActive = (isStreaming || isPaused) && owner.id !in finalResultMessageIds,
                        speechPreface = speechPrefaces[owner.id].orEmpty(),
                        messageActionsEnabled = messageActionsEnabled && !isStreaming && !isPaused,
                        branchEnabled = branchEnabled,
                    )
                }
                }
                }
            }
            if (compressingItemCount > 0) {
                item(key = ChatContextCompressingKey) {
                    Column {
                        if (isCompressingContext || isWaitingForCompression) ContextCompressingIndicator(
                            modelName = "${telemetry.compactingModelName.ifBlank { telemetry.mainModelName }}（主代理）",
                            waiting = isWaitingForCompression && !isCompressingContext,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 180),
                                placementSpec = null,
                                fadeOutSpec = null,
                            ),
                        )
                        compressingChildren.forEach { child ->
                            androidx.compose.runtime.key(child.taskId) {
                                ContextCompressingIndicator(modelName = child.contextLabel())
                            }
                        }
                    }
                }
            }
            item(key = ChatBottomSentinelKey) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                )
            }
        }

        fun navigateUserMessage(toEdge: Boolean) {
            // Do not queue animations on rapid taps; a new drag cancels the active jump.
            if (messageNavigationJob != null) return
            val direction = navigationDirection
            val target = conversationUserMessageTarget(
                userMessageTargets, scrollState.firstVisibleItemIndex, bottomItemIndex, direction, toEdge,
                firstVisibleScrollOffset = scrollState.firstVisibleItemScrollOffset,
            )
            onBottomAnchorChanged(false)
            messageNavigationJob = coroutineScope.launch {
                try {
                    // Let the follow/boundary-haptic observers yield before moving the list.
                    withFrameNanos { }
                    // Keep one continuous motion, slowing only at the selected user-message target.
                    scrollState.animateToConversationTurn(target)
                    if (target == bottomItemIndex) snapListToBottom(scrollState, currentBottomItemIndex)
                    onBottomAnchorChanged(
                        direction == ConversationNavigationDirection.Down && scrollState.isConversationAtBottom(),
                    )
                } finally {
                    messageNavigationJob = null
                }
            }
        }
        val showMessageNavigation by remember(scrollState, navigationDirection, keepBottomAnchored) {
            derivedStateOf {
                !keepBottomAnchored && when (navigationDirection) {
                    ConversationNavigationDirection.Up -> scrollState.canScrollBackward
                    ConversationNavigationDirection.Down -> !scrollState.isConversationAtBottom()
                }
            }
        }
        ConversationTurnNavigationButton(
            direction = navigationDirection,
            visible = showMessageNavigation,
            onStep = { navigateUserMessage(toEdge = false) },
            onEdge = { navigateUserMessage(toEdge = true) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp + bottomInset),
        )
    }
}

internal enum class BottomFollowSettlingState { Disabled, Active, Draining }

/** Branch-equivalent short circuit: do not scan messages until reveal completion matters. */
internal inline fun bottomFollowSettlingState(
    streaming: Boolean,
    anchored: Boolean,
    userScrolling: Boolean,
    pendingReveal: () -> Boolean,
): BottomFollowSettlingState = when {
    !anchored || userScrolling -> BottomFollowSettlingState.Disabled
    streaming || pendingReveal() -> BottomFollowSettlingState.Active
    else -> BottomFollowSettlingState.Draining
}

private val InactiveBottomFollowLayout = BottomFollowLayout(
    enabled = false, bottomItemIndex = 0, sentinelBottom = null, viewportEnd = 0,
    lastVisibleIndex = null, viewportSizePx = 0, canScrollForward = false, lastVisibleOffset = null,
)

internal data class BottomFollowLayout(
    val enabled: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
    val viewportSizePx: Int,
    val canScrollForward: Boolean,
    // Keep missing-sentinel targets live while scrolling inside one very tall item.
    val lastVisibleOffset: Int?,
)

internal data class BottomFollowDecision(
    val scrollByPx: Int = 0,
    val requestIndex: Int? = null,
)

internal fun resolveBottomFollowDecision(
    enabled: Boolean,
    bottomItemIndex: Int,
    sentinelBottom: Int?,
    viewportEnd: Int,
    lastVisibleIndex: Int?,
    viewportSizePx: Int = viewportEnd,
    canScrollForward: Boolean = true,
): BottomFollowDecision {
    if (!enabled || !canScrollForward) return BottomFollowDecision()
    val overflow = sentinelBottom?.minus(viewportEnd)
    return when {
        overflow != null && overflow > 0 -> BottomFollowDecision(scrollByPx = overflow)
        sentinelBottom == null && lastVisibleIndex != null && lastVisibleIndex < bottomItemIndex ->
            BottomFollowDecision(scrollByPx = viewportSizePx.coerceAtLeast(1))
        else -> BottomFollowDecision()
    }
}

internal data class InitialBottomPosition(
    val bottomItemIndex: Int,
    val hasLayout: Boolean,
    val anchored: Boolean,
    val interrupted: Boolean,
) {
    val ready: Boolean get() = !anchored || interrupted || (bottomItemIndex > 0 && hasLayout)
    val shouldPosition: Boolean get() = ready && anchored && !interrupted
}

/**
 * 一轮对话（两条用户消息之间）里最后一条 Agent 正文视为最终结果，其余为中间步骤。
 * 流式或中途压缩期间当前轮次尚未结束，最后一轮不标记，等结束后复制按钮才出现；
 * 之前已结束轮次的最终结果不受影响。
 *
 * 追加/steering 的用户消息不算新一轮：被打断的正文和继续输出同属一段，
 * 操作栏只出现在整段结束后的最后一条。
 */
internal fun resolveFinalResultMessageIds(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean = false,
    isCompressingContext: Boolean = false,
): Set<String> {
    val ids = LinkedHashSet<String>()
    var lastAgentMessageId: String? = null
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> if (!message.isSteerSupplement()) {
                lastAgentMessageId?.let(ids::add)
                lastAgentMessageId = null
            }
            is AgentMessageUi -> lastAgentMessageId = message.id
            is io.github.mangi.eta.ui.model.ErrorReconnectMessageUi -> if (message.isRetryableFailure()) {
                ids.add(lastAgentMessageId ?: message.id)
                lastAgentMessageId = null
            }
            is SystemNoticeMessageUi -> if (message.code.isRetryableFailure()) {
                ids.add(message.id)
                lastAgentMessageId = null
            }
            else -> Unit
        }
    }
    if (!isStreaming && !isCompressingContext) {
        lastAgentMessageId?.let(ids::add)
    }
    return ids
}

/** One traversal for all final bubbles, rather than one history scan per answer. */
internal fun visibleTurnSpeechPrefaces(
    messages: List<AgentChatMessageUi>,
    finalIds: Set<String>,
): Map<String, String> {
    if (finalIds.isEmpty()) return emptyMap()
    val result = HashMap<String, String>(finalIds.size)
    val parts = ArrayList<String>()
    for (message in messages) {
        if (message.id in finalIds) result[message.id] = parts.joinToString("\n\n")
        when (message) {
            is UserMessageUi -> if (!message.isSteerSupplement()) parts.clear()
            is AgentMessageUi -> message.content.trim().takeIf { it.isNotBlank() }?.let(parts::add)
            else -> Unit
        }
    }
    return result
}

/** Visible assistant bubbles in the same turn, excluding collapsed thinking. */
internal fun visibleTurnSpeechPreface(
    messages: List<AgentChatMessageUi>,
    finalId: String,
): String {
    val parts = ArrayList<String>()
    for (message in messages) {
        if (message.id == finalId) break
        when (message) {
            is UserMessageUi -> if (!message.isSteerSupplement()) parts.clear()
            is AgentMessageUi -> message.content.trim().takeIf { it.isNotBlank() }?.let(parts::add)
            else -> Unit
        }
    }
    return parts.joinToString("\n\n")
}


@Composable
private fun AgentChatBottomBar(
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    billedContextTokens: Int? = null,
    projectedContextTokens: Int? = null,
    billedHistoryTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    measuredContextTokens: Int? = null,
    contextDisplayPolicy: io.github.mangi.eta.ui.model.ContextDisplayPolicy = io.github.mangi.eta.ui.model.ContextDisplayPolicy(),
    billedOverheadTokens: Int? = null,
    activeRunContextWindow: Int? = null,
    autoCompressEnabled: Boolean,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    canContinueDisconnected: Boolean = false,
    isCompressingContext: Boolean = false,
    showMorphLoading: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    messageEdit: MessageEditUiState?,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    voiceState: VoiceModeState = VoiceModeState(),
    onStartVoiceMode: (VoiceEntryMode) -> Unit = {},
    onStopVoiceMode: () -> Unit = {},
    onSubmit: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit = {},
    gptSpeedMode: io.github.mangi.eta.data.model.GptSpeedMode = io.github.mangi.eta.data.model.GptSpeedMode.NORMAL,
    onCycleGptSpeedMode: () -> Unit = {},
) {
    val drawerBlocksIme = LocalConversationDrawerBlocksIme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (drawerBlocksIme) Modifier else Modifier.imePadding()),
    ) {
        // 输入框周围保持透明：消息列表延伸到底栏之后，只有输入框本体不透明。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        ) {
            AgentChatInputBar(
                collaborationConversationId = collaborationConversationId,
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = history,
                billedContextTokens = billedContextTokens,
                projectedContextTokens = projectedContextTokens,
                billedHistoryTokens = billedHistoryTokens,
                requestOverheadTokens = requestOverheadTokens,
                measuredContextTokens = measuredContextTokens,
                contextDisplayPolicy = contextDisplayPolicy,
                billedOverheadTokens = billedOverheadTokens,
                activeRunContextWindow = activeRunContextWindow,
                autoCompressEnabled = autoCompressEnabled,
                showContextUsage = showContextUsage,
                isStreaming = isStreaming,
                isPaused = isPaused,
                canContinueDisconnected = canContinueDisconnected,
                isCompressingContext = isCompressingContext,
                showMorphLoading = showMorphLoading,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
            conversationMentions = conversationMentions,
                isEditingMessage = messageEdit != null,
                assistantId = assistantId,
                voiceState = voiceState,
                onStartVoiceMode = onStartVoiceMode,
                onStopVoiceMode = onStopVoiceMode,
                editHasLaterTurns = messageEdit?.hasLaterTurns == true,
                onSubmit = onSubmit,
                onReasoningEffortChange = onReasoningEffortChange,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onContinue = onContinue,
                onAbortPausedRun = onAbortPausedRun,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                onEditAssistant = onEditAssistant,
                onAssistantSelected = onAssistantSelected,
                gptSpeedMode = gptSpeedMode,
                onCycleGptSpeedMode = onCycleGptSpeedMode,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun shouldShowMorphLoadingIndicator(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    isCompressingContext: Boolean = false,
    enabled: Boolean,
    beforeResponseOnly: Boolean,
): Boolean {
    if (!enabled || !isStreaming || isPaused || isCompressingContext) return false
    if (!beforeResponseOnly) return true
    return isWaitingForFirstModelOutput(messages)
}

internal fun isWaitingForFirstModelOutput(messages: List<AgentChatMessageUi>): Boolean {
    val lastUserIndex = messages.indexOfLast { message ->
        (message is UserMessageUi && !message.isSteerSupplement()) ||
            (message is SystemNoticeMessageUi && message.code.isRetryableFailure()) ||
            (message is io.github.mangi.eta.ui.model.ErrorReconnectMessageUi && message.isRetryableFailure())
    }
    if (lastUserIndex < 0) return false
    return messages.asSequence()
        .drop(lastUserIndex + 1)
        .none(::isModelOutputMessage)
}

private fun isModelOutputMessage(message: AgentChatMessageUi): Boolean = when (message) {
    is ThinkingMessageUi -> true
    is ToolActivityMessageUi -> true
    is ToolSummaryMessageUi -> true
    is io.github.mangi.eta.ui.model.ErrorReconnectMessageUi -> true
    is AgentMessageUi -> message.content.isNotBlank()
    else -> false
}

private val ChatBackToBottomButtonSlot = 52.dp

private const val CHAT_CACHE_AHEAD_VIEWPORTS = 1f
private const val CHAT_CACHE_BEHIND_VIEWPORTS = 1f

private const val ChatBottomSentinelKey = "agent-chat-bottom-sentinel"
private const val ChatContextCompressingKey = "agent-chat-context-compressing"

@Composable
private fun ContextCompressingIndicator(waiting: Boolean = false, modelName: String = "", modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
        Text(
            text = if (waiting) stringResource(R.string.compress_conversation_waiting) else
                "${modelName.ifBlank { "主代理" }} • 正在压缩上下文",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

internal fun resolveKeepBottomAnchored(
    current: Boolean,
    isUserDragging: Boolean,
    isAtBottom: Boolean,
    hasLeftBottom: Boolean = false,
): Boolean = when {
    // 手指一开始拖就停跟底；流式长高让 sentinel 离开视口时不能当成用户上滑。
    isUserDragging -> false
    // 只有真正滑离过底部再回来，才重新贴底，避免轻触后被跟底拽回去。
    isAtBottom && (current || hasLeftBottom) -> true
    else -> current
}

internal fun shouldPinConversationToBottom(
    isStreaming: Boolean,
    isScrollable: Boolean,
): Boolean = !(isStreaming && isScrollable)

internal fun resolveBottomFollowEnabled(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    isBottomSettling: Boolean = false,
): Boolean = (isStreaming || isBottomSettling) && keepBottomAnchored && !isUserDragging

/** 尾部留白负责静态停靠；整宽裁剪只负责输出及布局追平前的防溢出。 */
internal fun shouldClipChatTail(
    isStreaming: Boolean,
    isBottomSettling: Boolean,
    keepBottomAnchored: Boolean,
    isUserScrolling: Boolean,
    isUserDragging: Boolean,
    navigationActive: Boolean,
    routeCovered: Boolean = false,
): Boolean = (isStreaming || isBottomSettling) && keepBottomAnchored &&
    !isUserScrolling &&
    !isUserDragging &&
    !navigationActive &&
    !routeCovered

internal fun shouldRequestInitialBottom(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
): Boolean = isStreaming && keepBottomAnchored && !isUserDragging

internal fun shouldSnapConversationToBottom(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    hasItems: Boolean,
    scrollToMessageId: String? = null,
): Boolean = hasItems &&
    keepBottomAnchored &&
    !isUserDragging &&
    !isStreaming &&
    scrollToMessageId == null

internal fun resolveConversationBottomSnap(
    bottomItemIndex: Int,
    lastVisibleIndex: Int?,
    lastVisibleBottom: Int?,
    viewportEnd: Int,
): BottomFollowDecision {
    if (lastVisibleIndex == null || lastVisibleBottom == null || lastVisibleIndex < bottomItemIndex) {
        return BottomFollowDecision(requestIndex = bottomItemIndex)
    }
    return BottomFollowDecision(scrollByPx = lastVisibleBottom - viewportEnd)
}

/**
 * Reposition an idle anchored tail only when the measured rest line changes.
 * Observe LazyColumn's applied padding/viewport, not a new bottomInset paired with
 * stale layoutInfo. Do not restart on enable/content changes: those belong to initial
 * positioning, navigation and the streaming follow controller respectively.
 */
@Composable
internal fun AnchorChatTailOnViewportChange(
    scrollState: LazyListState,
    bottomItemIndex: Int,
    canPosition: () -> Boolean,
) {
    val currentBottomIndex by rememberUpdatedState(bottomItemIndex)
    val currentCanPosition by rememberUpdatedState(canPosition)
    LaunchedEffect(scrollState) {
        var previousRestLine: Int? = null
        snapshotFlow {
            val info = scrollState.layoutInfo
            if (info.totalItemsCount == 0 || info.viewportSize.height == 0) null
            else info.viewportEndOffset - info.afterContentPadding
        }
            .distinctUntilChanged()
            .collectLatest { restLine ->
                if (restLine == null) return@collectLatest
                val changed = previousRestLine != null && previousRestLine != restLine
                previousRestLine = restLine
                if (changed && currentCanPosition()) {
                    snapListToBottom(scrollState, currentBottomIndex) { currentCanPosition() }
                }
            }
    }
}

private suspend fun snapListToBottom(
    scrollState: LazyListState,
    bottomItemIndex: Int,
    canPosition: () -> Boolean = { true },
) {
    repeat(3) {
        if (!canPosition()) return
        val layout = scrollState.layoutInfo
        val lastVisible = layout.visibleItemsInfo.lastOrNull()
        val viewportEnd = layout.viewportEndOffset - layout.afterContentPadding
        val decision = resolveConversationBottomSnap(
            bottomItemIndex = bottomItemIndex,
            lastVisibleIndex = lastVisible?.index,
            lastVisibleBottom = lastVisible?.let { it.offset + it.size },
            viewportEnd = viewportEnd,
        )
        decision.requestIndex?.let { scrollState.scrollToItem(it) }
        if (decision.scrollByPx != 0) {
            scrollState.scroll { if (canPosition()) scrollBy(decision.scrollByPx.toFloat()) }
        }
        if (decision.requestIndex == null && decision.scrollByPx == 0) return
        withFrameNanos { }
    }
}

/** 尾部哨兵超出静止线的像素；哨兵不在可见项中时返回 null（尾部位置未知）。 */
private fun LazyListState.followTailOverflow(): Int? {
    val info = layoutInfo
    // 列表自身在布局边界裁剪；上提超过尾部留白只会在输入框上方露出空白，最多上提到留白为止。
    return info.measuredTailOverflow()?.coerceAtMost(info.afterContentPadding)
}

/** 未经过绘制上限截断的真实布局差额，供实际滚动消化超出缓冲的部分。 */
private fun LazyListLayoutInfo.measuredTailOverflow(): Int? {
    val sentinel = visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey }
    val last = visibleItemsInfo.lastOrNull()
    val bottom = resolveTailBottomPx(
        sentinelBottom = sentinel?.let { it.offset + it.size },
        lastVisibleIndex = last?.index,
        lastVisibleBottom = last?.let { it.offset + it.size },
        totalItems = totalItemsCount,
    ) ?: return null
    return bottom - (viewportEndOffset - afterContentPadding)
}

private data class TailBreachSample(
    val overPx: Int?,
    val tailBottomPx: Int?,
    val restLinePx: Int,
    val padPx: Int,
    val sentinelVisible: Boolean,
    val lastIndex: Int,
    val totalItems: Int,
    val lifting: Boolean,
    val state: String,
)

/** UserInput 来源的滚动只有在手指按着时才算用户滑动；惯性走的是 SideEffect 来源，不经过这里。 */
internal fun isUserScrollGesture(pointerDown: Boolean): Boolean = pointerDown

/** 只保留类名和方法名（与主线程消息日志同一套截断规则），跳过本文件与协程、Compose 调度的帧。 */
internal fun compactStack(frames: Array<StackTraceElement>, limit: Int = 14): String =
    frames.asSequence()
        .drop(1)
        .map { "${toggleProbeClassName(it.className)}.${toggleProbeClassName(it.methodName)}" }
        .filterNot { it.startsWith("kotlin.") || it.startsWith("java.") }
        .take(limit)
        .joinToString("<")

/** 尾部画出来的位置越过静止线多少像素（上提之后）；尾部位置未知时为 null。 */
internal fun resolveTailDrawnOverflow(tailBottomPx: Int?, restLinePx: Int, liftPx: Int): Int? =
    tailBottomPx?.let { it - restLinePx - liftPx }

/**
 * 尾部下沿。哨兵是最后一项；它被挤出可视区、但紧挨着它的最后一段内容仍可见时，
 * 用那一段的下沿代替（相差哨兵自身 1dp）。两者都看不到时返回 null。
 */
internal fun resolveTailBottomPx(
    sentinelBottom: Int?,
    lastVisibleIndex: Int?,
    lastVisibleBottom: Int?,
    totalItems: Int,
): Int? = when {
    sentinelBottom != null -> sentinelBottom
    lastVisibleIndex != null && lastVisibleBottom != null && totalItems >= 2 &&
        lastVisibleIndex == totalItems - 2 -> lastVisibleBottom
    else -> null
}

internal data class FollowTailLag(val liftPx: Float, val unknown: Boolean = false) {
    companion object {
        val None = FollowTailLag(0f)
        val Unknown = FollowTailLag(0f, unknown = true)
    }
}

/**
 * 跟底输出时，跟底滚动尚未追上的尾部超出量改为绘制上提，让尾部停在静止线上。
 * 不跟底（用户拖动、浏览历史、输出结束）时不做任何处理；尾部不可见时交给静止线裁剪兜底。
 */
/**
 * 点开一行时，它下面的内容会不会停在屏幕原处。跟底时上提让尾部停在静止线；
 * 内容不满一屏且贴底排列时，列表变高也是往上长。两种情况下展开都该从下沿长出。
 */
internal fun resolveExpansionHoldsBottom(
    following: Boolean,
    arrangedToBottom: Boolean,
    listScrollable: Boolean,
): Boolean = following || (arrangedToBottom && !listScrollable)

// Preserve the measured recovery budget; it is NOT an entrance admission window.
private const val WORK_EXPANSION_RECOVERY_NANOS = 680_000_000L
// 180ms 展开动画加淡入，再留一点给最后一帧布局。
private const val EXPANSION_BOTTOM_SNAP_NANOS = 400_000_000L

internal fun resolveFollowTailLag(following: Boolean, tailOverflowPx: Int?): FollowTailLag = when {
    !following -> FollowTailLag.None
    tailOverflowPx == null -> FollowTailLag.Unknown
    tailOverflowPx <= 0 -> FollowTailLag.None
    else -> FollowTailLag(tailOverflowPx.toFloat())
}

/** 测量暂时缺失时保留上一帧上提，避免卡片底边在输入框附近来回跳。 */
internal fun nextHeldTailLift(shouldLift: Boolean, overflowPx: Int?, heldPx: Int): Int = when {
    !shouldLift -> 0
    overflowPx == null -> heldPx.coerceAtLeast(0)
    else -> overflowPx.coerceAtLeast(0)
}

private fun LazyListState.isConversationAtBottom(): Boolean {
    val info = layoutInfo
    val sentinel = info.visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey }
    return if (sentinel == null) {
        !canScrollForward
    } else {
        val viewportEnd = info.viewportEndOffset - info.afterContentPadding
        sentinel.offset + sentinel.size <= viewportEnd + 8
    }
}

@Composable
private fun EmptyChatState(
    showSuggestions: Boolean,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = listOf(
        SuggestionItem(
            title = stringResource(R.string.ui_analyze_current_screen_ebf08f),
            icon = Icons.Rounded.DocumentScanner,
            prompt = stringResource(R.string.suggestion_analyze_screen_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_open_wechat_6b2c28),
            icon = Icons.Rounded.RocketLaunch,
            prompt = stringResource(R.string.suggestion_open_wechat_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_browse_the_web_da7afb),
            icon = Icons.Rounded.Language,
            prompt = stringResource(R.string.suggestion_browse_web_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_check_memory_pressure_2d9600),
            icon = Icons.Rounded.Terminal,
            prompt = stringResource(R.string.suggestion_memory_pressure_prompt),
        ),
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.ui_how_can_i_help_you_e75391),
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(30.dp))

            AnimatedVisibility(
                visible = showSuggestions,
                enter = fadeIn(
                    animationSpec = tween(durationMillis = 220)
                ) + slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetY = { it / 3 },
                ),
                exit = fadeOut(
                    animationSpec = tween(durationMillis = 130)
                ) + slideOutVertically(
                    animationSpec = tween(durationMillis = 180),
                    targetOffsetY = { it / 4 },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    suggestions.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowItems.forEach { item ->
                                SuggestionCard(
                                    item = item,
                                    onClick = { onSuggestionClick(item.prompt) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    item: SuggestionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = MiuixTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(9.dp))
        Text(
            text = item.title,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

private data class SuggestionItem(
    val title: String,
    val icon: ImageVector,
    val prompt: String,
)

internal fun shouldStopOrphanSpeechPlayback(
    owner: String?,
    messageEditActive: Boolean,
    visibleCompletedAgentIds: Set<String>,
): Boolean {
    if (owner.isNullOrBlank()) return false
    // 试听、语音模式和 Agent 朗读工具都不绑定某条回复，不能按“回复不在可见列表里”收掉。
    if (owner == "tts-preview" || owner == io.github.mangi.eta.agent.voice.tts.AGENT_SPEECH_OWNER || owner.startsWith(VOICE_MODE_SPEECH_OWNER_PREFIX)) return false
    return messageEditActive || owner !in visibleCompletedAgentIds
}

/** 最后一条消息静止时与输入框上沿的间距；跟底输出时正文也被裁在这条线上。 */
private val ConversationComposerGap = 14.dp

internal fun composerRestLinePx(heightPx: Float, occlusionPx: Float): Float =
    (heightPx - occlusionPx).coerceAtLeast(0f)

/** 把列表裁在输入框上沿加间隙处，子级 graphicsLayer 也遵守这条边界。 */
internal class ComposerRestClip(private val occlusion: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bottom = with(density) { composerRestLinePx(size.height, occlusion.toPx()) }
        return Outline.Rectangle(Rect(0f, 0f, size.width, bottom))
    }

    override fun equals(other: Any?): Boolean = other is ComposerRestClip && other.occlusion == occlusion

    override fun hashCode(): Int = occlusion.hashCode()
}

