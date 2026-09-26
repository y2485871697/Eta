package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material3.DropdownMenuItem
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.mangi.eta.ui.model.ConversationMention
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.agent.voice.VoiceModePhase
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.config.Prefs
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.ui.screens.assistants.AssistantPickerDialog
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.liveContextUsage
import io.github.mangi.eta.ui.model.shouldBlockSendForContextWindow
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val SendButtonVisualSize = ChatInputActionIconSize
private val SendIconSize = 16.dp
private val StopIconSize = 10.dp
private val ThinkingIconSize = 21.dp
private val InputContainerShape = RoundedCornerShape(20.dp)

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentChatInputBar(
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    billedContextTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    billedOverheadTokens: Int? = null,
    uncommittedLiveTokens: Int = 0,
    autoCompressEnabled: Boolean,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    isCompressingContext: Boolean = false,
    showMorphLoading: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    isEditingMessage: Boolean,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    voiceState: VoiceModeState = VoiceModeState(),
    onStartVoiceMode: (VoiceEntryMode) -> Unit = {},
    onStopVoiceMode: () -> Unit = {},
    editHasLaterTurns: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    isPaused: Boolean = false,
    canContinueDisconnected: Boolean = false,
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
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val textFieldState = draftField ?: rememberTextFieldState(initialText = input)
    var wasEditingMessage by remember { mutableStateOf(isEditingMessage) }
    val draftText = textFieldState.text.toString()
    val liveUsage = remember(
        billedContextTokens,
        requestOverheadTokens,
        billedOverheadTokens,
        uncommittedLiveTokens,
        draftText,
        pendingImages,
        pendingFileReferences,
        conversationMentions.pending,
        modelPickerState.selectedModel,
    ) {
        liveContextUsage(
            // History tokens are cached above so typing does not rescan the transcript.
            history = emptyList(),
            currentInput = draftText,
            pendingImages = pendingImages,
            selectedModel = modelPickerState.selectedModel,
            pendingFileReferences = pendingFileReferences,
            pendingConversationMentions = conversationMentions.pending,
            billedContextTokens = billedContextTokens,
            requestOverheadTokens = requestOverheadTokens,
            billedOverheadTokens = billedOverheadTokens,
            uncommittedLiveTokens = uncommittedLiveTokens,
        )
    }
    val contextSendBlocked = shouldBlockSendForContextWindow(autoCompressEnabled, liveUsage)
    val compressionSendBlocked = isCompressingContext
    val canSend = !modelPickerState.isChanging && modelPickerState.selectedModel != null && !contextSendBlocked && !compressionSendBlocked && (
        textFieldState.text.isNotBlank() ||
            pendingImages.isNotEmpty() ||
            (pendingFileReferences.isNotEmpty() || conversationMentions.pending.isNotEmpty())
        )
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    var inputContainerTopPx by remember { mutableIntStateOf(0) }
    val thinkingPopupMaxHeight = with(density) {
        (inputContainerTopPx - statusBarTopPx).coerceAtLeast(0).toDp()
    }.minus(ChatInputPopupMargin * 2)
        .coerceAtLeast(ListPopupDefaults.MinPopupHeight)

    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val drawerBlocksIme = LocalConversationDrawerBlocksIme.current
    LaunchedEffect(isEditingMessage) {
        // 编辑模式切换由业务状态驱动；日常输入使用会话拥有的 TextFieldState，
        // 不让每个字符触发消息列表和 Markdown 重组。
        if (draftField == null && (isEditingMessage || wasEditingMessage)) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
        }
        if (isEditingMessage && !wasEditingMessage && !drawerBlocksIme) {
            showChatInputIme(focusRequester, keyboard, view)
        }
        wasEditingMessage = isEditingMessage
    }

    CompositionLocalProvider(LocalChatInputFocusRequester provides focusRequester) {
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        val mentionQuery = ConversationMention.queryAtCursor(draftText, textFieldState.selection.end)
            .takeIf { textFieldState.selection.collapsed }
        ConversationMentionPanel(
            state = conversationMentions,
            query = mentionQuery?.query,
            onSelect = { id ->
                val query = mentionQuery
                if (query != null && conversationMentions.onAttach(id)) {
                    val end = textFieldState.selection.end
                    textFieldState.edit {
                        replace(query.start, end, "")
                        selection = TextRange(query.start)
                    }
                    focusRequester.requestFocus()
                }
            },
        )
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            PendingFileReferenceStrip(
                references = pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Text(
                text = if (editHasLaterTurns) {
                    stringResource(R.string.chat_edit_replace_later)
                } else {
                    stringResource(R.string.chat_edit_replace_message)
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )
        }

        AnimatedVisibility(
            visible = contextSendBlocked,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Text(
                text = stringResource(R.string.context_window_send_blocked),
                style = MiuixTheme.textStyles.body2,
                color = StatusError,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )
        }

        AnimatedVisibility(
            visible = compressionSendBlocked,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Text(
                text = stringResource(R.string.compress_conversation_in_progress),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    inputContainerTopPx = coordinates.positionInWindow().y.roundToInt()
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .dropShadow(
                        shape = InputContainerShape,
                        shadow = Shadow(
                            radius = 8.dp,
                            color = Color.Black,
                            alpha = 0.08f,
                        ),
                    )
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        cornerRadius = 20.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        cornerRadius = 20.dp,
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (voiceState.active || voiceState.error != null) {
                    VoiceModeStatusPanel(
                        state = voiceState,
                        onStop = onStopVoiceMode,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (textFieldState.text.isBlank()) {
                            Text(
                                text = if (isStreaming) stringResource(R.string.chat_eta_working) else stringResource(R.string.chat_input_hint),
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        BasicTextField(
                            state = textFieldState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .focusProperties { canFocus = !drawerBlocksIme },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            textStyle = TextStyle(
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                            ),
                            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                            lineLimits = TextFieldLineLimits.MultiLine(
                                minHeightInLines = 1,
                                maxHeightInLines = 6,
                            ),
                        )
                    }
                }

                ChatComposerActionRow(
                    actions = {
                        if (isEditingMessage) {
                            IconButton(
                                onClick = onCancelMessageEdit,
                                minWidth = ChatInputActionSize,
                                minHeight = ChatInputActionSize,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.ui_cancel_edit_c698df),
                                    modifier = Modifier.size(ChatInputActionIconSize),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        } else {
                            AgentAttachmentPickerButton(
                                onAttachImage = onAttachImage,
                                onAttachVideo = onAttachVideo,
                                onAttachFiles = onAttachFiles,
                                onAttachFolder = onAttachFolder,
                                onAttachFilePath = onAttachFilePath,
                            )

                            Spacer(modifier = Modifier.width(2.dp))

                            if (availableReasoningEfforts.isNotEmpty()) {
                                ThinkingEffortChip(
                                    effort = reasoningEffort,
                                    options = availableReasoningEfforts,
                                    enabled = !isStreaming || isPaused,
                                    onEffortChange = onReasoningEffortChange,
                                )
                            }

                            Spacer(modifier = Modifier.width(2.dp))

                            AssistantPickerButton(
                                enabled = !isStreaming || isPaused,
                                selectedAssistantId = assistantId,
                                onEditAssistant = onEditAssistant,
                                onAssistantSelected = onAssistantSelected,
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Keep context details accessible even for an empty draft or an unknown limit.
                        AgentContextUsageButton(
                            usage = liveUsage,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            sendBlocked = contextSendBlocked,
                        )
                        Spacer(modifier = Modifier.width(2.dp))

                        AgentModelPickerButton(
                            conversationId = collaborationConversationId,
                            collaborationTaskRunning = isStreaming || isPaused || isCompressingContext,
                            state = modelPickerState,
                            isStreaming = isStreaming,
                            isPaused = isPaused,
                            popupAnchorTopPx = inputContainerTopPx,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            onModelSelected = onModelSelected,
                        )

                        val sendMode = resolveChatComposerSendMode(
                            isStreaming = isStreaming,
                            isPaused = isPaused,
                            canContinueDisconnected = canContinueDisconnected,
                            hasSteerContent = textFieldState.text.isNotBlank() ||
                                pendingImages.isNotEmpty() ||
                                (pendingFileReferences.isNotEmpty() || conversationMentions.pending.isNotEmpty()),
                            canStartNewSend = canSend,
                            isCompressingContext = isCompressingContext,
                        )
                        val sendInteraction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .size(ChatInputActionSize)
                                .focusProperties { canFocus = false }
                                .combinedClickable(
                                    enabled = sendMode != "idle",
                                    indication = null,
                                    interactionSource = sendInteraction,
                                    hapticFeedbackEnabled = false,
                                    onClick = {
                                        TouchHaptics.click(view)
                                        when (sendMode) {
                                            "stop" -> onStop()
                                            "continue" -> onContinue()
                                            "send" -> {
                                                val submittedText = textFieldState.text.toString()
                                                onSubmit(submittedText)
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (sendMode != "continue" || !isPaused) return@combinedClickable
                                        TouchHaptics.longPress(view)
                                        onAbortPausedRun()
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val sendButtonColor by animateColorAsState(
                                targetValue = when (sendMode) {
                                    "stop", "continue", "send" -> MiuixTheme.colorScheme.onSurface
                                    else -> MiuixTheme.colorScheme.surfaceContainerHigh
                                },
                                animationSpec = tween(durationMillis = 160),
                                label = "send_button_color",
                            )
                            Box(
                                modifier = Modifier
                                    .size(SendButtonVisualSize)
                                    .clip(CircleShape)
                                    .background(sendButtonColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedContent(
                                    targetState = sendMode,
                                    transitionSpec = {
                                        (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f))
                                            .togetherWith(
                                                fadeOut(tween(90)) +
                                                    scaleOut(tween(110), targetScale = 0.72f)
                                            )
                                    },
                                    label = "send_stop_icon",
                                ) { mode ->
                                    Icon(
                                        imageVector = when (mode) {
                                            "stop" -> Icons.Rounded.Stop
                                            "continue" -> Icons.Rounded.PlayArrow
                                            else -> Icons.Rounded.ArrowUpward
                                        },
                                        contentDescription = when (mode) {
                                            "stop" -> stringResource(R.string.chat_stop)
                                            "continue" -> if (isPaused) {
                                                stringResource(R.string.chat_continue) +
                                                    "，" + stringResource(R.string.chat_continue_abort)
                                            } else {
                                                stringResource(R.string.chat_continue)
                                            }
                                            else -> stringResource(R.string.chat_send)
                                        },
                                        modifier = Modifier.size(
                                            if (mode == "stop") StopIconSize else SendIconSize
                                        ),
                                        tint = when (mode) {
                                            "stop", "continue", "send" -> MiuixTheme.colorScheme.surface
                                            else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                                        },
                                    )
                                }
                            }
                        }
                    },
                    indicator = {
                        if (isEditingMessage) {
                            ChatSpeechIndicator(
                                textFieldState = textFieldState,
                                showGeneration = showMorphLoading,
                                interactionBlocked = drawerBlocksIme,
                                resetKey = isStreaming to isEditingMessage,
                            )
                        } else {
                            VoiceEntryButton(
                                textFieldState = textFieldState,
                                showGeneration = showMorphLoading,
                                interactionBlocked = drawerBlocksIme,
                                resetKey = isStreaming to isEditingMessage,
                                voiceState = voiceState,
                                onStartVoiceMode = onStartVoiceMode,
                                onStopVoiceMode = onStopVoiceMode,
                            )
                        }
                    },
                )
            }
        }
    }
    }

}

/** 思考强度选择保持为单一图标，当前状态仅通过图标颜色区分。 */
@Composable
private fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    enabled: Boolean,
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    val active = effort != ReasoningEffort.OFF
    val pickerEnabled = enabled && options.isNotEmpty()
    LaunchedEffect(pickerEnabled) {
        if (!pickerEnabled) showPicker = false
    }
    val contentColor by animateColorAsState(
        targetValue = if (active) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_content",
    )
    ChatInputNonFocusableIconButton(
        onClick = { if (pickerEnabled) showPicker = true },
        contentDescription = stringResource(R.string.chat_reasoning_effort, effort.displayName),
        modifier = modifier,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_atom),
            contentDescription = null,
            modifier = Modifier.size(ThinkingIconSize),
            tint = if (pickerEnabled) contentColor else contentColor.copy(alpha = 0.38f),
        )
    }
    ThinkingEffortPickerDialog(
        show = showPicker && pickerEnabled,
        effort = effort,
        options = options,
        onDismiss = { showPicker = false },
        onEffortChange = onEffortChange,
    )
}

@Composable
internal fun ThinkingEffortPickerDialog(
    show: Boolean,
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    onDismiss: () -> Unit,
    onEffortChange: (ReasoningEffort) -> Unit,
    description: String? = null,
) {
    if (options.isEmpty()) return
    val view = LocalView.current
    val selectedIndex = options.indexOf(effort).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var lastHapticIndex by remember { mutableIntStateOf(selectedIndex) }
    val latestEffort by rememberUpdatedState(effort)
    val latestOptions by rememberUpdatedState(options)
    val latestOnEffortChange by rememberUpdatedState(onEffortChange)
    LaunchedEffect(show, selectedIndex, options) {
        if (show) {
            sliderValue = selectedIndex.toFloat()
            lastHapticIndex = selectedIndex
        }
    }
    val previewIndex = sliderValue.roundToInt().coerceIn(0, options.lastIndex)
    val preview = options[previewIndex]
    val maxIndex = (options.size - 1).toFloat().coerceAtLeast(0f)

    WindowDialog(
        show = show,
        title = stringResource(R.string.reasoning_picker_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (description != null) {
                Text(
                    text = description,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_atom),
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(32.dp),
                tint = if (preview != ReasoningEffort.OFF) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
            Text(
                text = preview.displayName,
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            if (options.size > 1) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = sliderValue.coerceIn(0f, maxIndex),
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        valueRange = 0f..maxIndex,
                        steps = (options.size - 2).coerceAtLeast(0),
                        showKeyPoints = true,
                        keyPoints = options.indices.map { it.toFloat() },
                        magnetThreshold = 0.18f,
                        hapticEffect = SliderDefaults.SliderHapticEffect.None,
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(options.size) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    val width = size.width.toFloat().coerceAtLeast(1f)
                                    var current = discreteSliderIndexForTap(
                                        down.position.x,
                                        width,
                                        latestOptions.size,
                                    )
                                    sliderValue = current.toFloat()
                                    lastHapticIndex = current
                                    TouchHaptics.click(view)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        val index = discreteSliderIndexForTap(
                                            change.position.x,
                                            width,
                                            latestOptions.size,
                                        )
                                        if (index != current) {
                                            current = index
                                            sliderValue = current.toFloat()
                                            if (current != lastHapticIndex) {
                                                lastHapticIndex = current
                                                TouchHaptics.click(view)
                                            }
                                        }
                                        change.consume()
                                        if (!event.changes.any { it.pressed }) break
                                    }
                                    val coerced = current.coerceIn(0, latestOptions.lastIndex)
                                    sliderValue = coerced.toFloat()
                                    lastHapticIndex = coerced
                                    val next = latestOptions[coerced]
                                    if (next != latestEffort) latestOnEffortChange(next)
                                }
                            },
                    )
                }
            }
        }
    }
}

internal fun discreteSliderIndexForTap(x: Float, width: Float, count: Int): Int {
    if (count <= 1 || width <= 0f) return 0
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
}


/**
 * 横向跟随 Chip，竖向则避开整个输入面板；默认下拉定位只会避开 Chip 自身。
 */
@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEach { image ->
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                rememberDataUrlBitmap(image.dataUrl, fallback = image.uri)?.let { bitmap ->
                    ChatClickableImage(
                        source = image.uri,
                        bitmap = bitmap,
                        contentDescription = stringResource(
                            if (image.isVideo) R.string.chat_video_preview else R.string.chat_image_preview,
                        ),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (image.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = AgentVideoCodec.formatDuration(image.durationMs ?: 0L),
                            style = MiuixTheme.textStyles.body2,
                            color = Color.White,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(22.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.58f))
                        .clickable { onRemoveImage(image.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_image_089db3),
                        modifier = Modifier.size(11.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}


@Composable
private fun AssistantPickerButton(
    enabled: Boolean,
    selectedAssistantId: String = "",
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId.ifBlank { repositoryActiveId }
    var showPicker by remember { mutableStateOf(false) }
    val assistant = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    LaunchedEffect(enabled) {
        if (!enabled) showPicker = false
    }
    ChatInputNonFocusableIconButton(
        onClick = { if (enabled) showPicker = true },
        contentDescription = stringResource(R.string.assistant_selector),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.size(ThinkingIconSize)) {
            AssistantAvatar(
                assistant = assistant,
                size = ThinkingIconSize,
                modifier = Modifier.graphicsLayer(alpha = if (enabled) 1f else 0.38f),
            )
        }
    }
    AssistantPickerDialog(
        show = showPicker && enabled,
        onDismiss = { showPicker = false },
        onSelect = { id ->
            showPicker = false
            onAssistantSelected(id)
        },
        onEdit = { id ->
            showPicker = false
            onEditAssistant(id)
        },
        selectedAssistantId = activeId,
    )
}

internal fun resolveChatComposerSendMode(
    isStreaming: Boolean,
    isPaused: Boolean,
    hasSteerContent: Boolean,
    canStartNewSend: Boolean,
    isCompressingContext: Boolean = false,
    canContinueDisconnected: Boolean = false,
): String = when {
    // 压缩进行中禁止追加/续写，避免一边压缩一边输出。流式时仍可停止。
    isCompressingContext && isStreaming -> "stop"
    isCompressingContext -> "idle"
    (isStreaming || isPaused) && hasSteerContent -> "send"
    isPaused -> "continue"
    isStreaming -> "stop"
    // Terminal failure: Continue creates a new logical turn, never resumes the failed one.
    canContinueDisconnected && hasSteerContent -> "send"
    canContinueDisconnected -> "continue"
    canStartNewSend -> "send"
    else -> "idle"
}

@Composable
private fun VoiceEntryButton(
    textFieldState: androidx.compose.foundation.text.input.TextFieldState,
    showGeneration: Boolean,
    interactionBlocked: Boolean,
    resetKey: Any?,
    voiceState: VoiceModeState,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    var picker by remember { mutableStateOf(false) }
    var lastSelected by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_VOICE_LAST_ENTRY)) }
    fun rememberMode(mode: VoiceEntryMode) {
        lastSelected = mode.wireValue
        Prefs.putString(Prefs.Keys.AGENT_VOICE_LAST_ENTRY, mode.wireValue)
    }
    var pendingMode by remember { mutableStateOf<VoiceEntryMode?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mode = pendingMode
        pendingMode = null
        if (granted && mode != null && io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode)) onStartVoiceMode(mode)
        else if (!granted && mode != null) Toast.makeText(context, R.string.speech_permission_denied, Toast.LENGTH_LONG).show()
    }
    fun startMode(mode: VoiceEntryMode) {
        if (!io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode)) return
        rememberMode(mode)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onStartVoiceMode(mode)
        } else {
            pendingMode = mode
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val config by io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.collectAsState()
    val modes = io.github.mangi.eta.agent.voice.VoiceEntryPolicy.modes(config)
    val canChoose = io.github.mangi.eta.agent.voice.VoiceEntryPolicy.canChoose(config)
    val directMode = io.github.mangi.eta.agent.voice.VoiceEntryPolicy.directMode(config, lastSelected)
    val directLabel = when (directMode) {
        VoiceEntryMode.DICTATION -> R.string.voice_mode_dictation
        VoiceEntryMode.UNIVERSAL -> R.string.voice_mode_universal
        VoiceEntryMode.DOUBAO_DUPLEX -> R.string.voice_mode_doubao
        null -> R.string.voice_mode_choose
    }
    LaunchedEffect(Unit) { io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.load(context) }
    LaunchedEffect(modes) {
        if (!canChoose) picker = false
        if (pendingMode !in modes) pendingMode = null
        if (voiceState.mode != null && voiceState.mode !in modes) onStopVoiceMode()
    }
    LaunchedEffect(resetKey, interactionBlocked) {
        pendingMode = null
        picker = false
    }
    val active = voiceState.active || voiceState.error != null
    val choose: (() -> Unit)? = if (canChoose) ({ picker = true }) else null
    Box {
    if (VoiceEntryMode.DICTATION in modes && !active) {
        ChatSpeechIndicator(
            textFieldState = textFieldState,
            showGeneration = showGeneration,
            interactionBlocked = interactionBlocked,
            resetKey = resetKey,
            onLongClick = choose,
            onUnavailableClick = if (canChoose) ({ picker = true }) else null,
            onIdleClick = when (directMode) {
                VoiceEntryMode.DICTATION -> null
                null -> if (canChoose) ({ picker = true }) else null
                else -> ({ startMode(directMode) })
            },
            idleDescription = context.getString(directLabel),
            onStartRequested = { rememberMode(VoiceEntryMode.DICTATION) },
            suspendCapture = picker,
            forceVisible = true,
        )
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(if (modes.isNotEmpty() || active) Modifier.semantics {
                    contentDescription = context.getString(if (active) R.string.voice_mode_stop else directLabel)
                }.combinedClickable(
                    enabled = !interactionBlocked,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    hapticFeedbackEnabled = false,
                    onLongClick = if (!active) choose?.let { callback ->
                        {
                            TouchHaptics.longPress(view)
                            callback()
                        }
                    } else null,
                    onClick = {
                        TouchHaptics.click(view)
                        when {
                            active -> onStopVoiceMode()
                            directMode != null -> startMode(directMode)
                            modes.size > 1 -> picker = true
                        }
                    },
                ) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            ContainedMorphLoadingIndicator(
                indicatorSize = if (active) 40.dp else 24.dp,
                animate = showGeneration || active,
            )
        }
    }
    VoiceEntryPickerDialog(
        show = picker && canChoose,
        modes = modes,
        onDismiss = { picker = false },
        onSelect = { mode ->
            picker = false
            pendingMode = null
            if (!interactionBlocked && io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                    io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode)) {
                TouchHaptics.click(view)
                // Choosing changes the default only; a separate tap starts capture or a call.
                rememberMode(mode)
            }
        },
    )
    }
}

@Composable
private fun VoiceEntryPickerDialog(
    show: Boolean,
    modes: List<VoiceEntryMode>,
    onDismiss: () -> Unit,
    onSelect: (VoiceEntryMode) -> Unit,
) {
    EtaDropdownMenu(
        expanded = show,
        onDismissRequest = onDismiss,
        preferAbove = true,
    ) {
        listOf(
            Triple(VoiceEntryMode.DICTATION, Icons.Rounded.KeyboardVoice, R.string.voice_mode_dictation),
            Triple(VoiceEntryMode.UNIVERSAL, Icons.Rounded.RecordVoiceOver, R.string.voice_mode_universal),
            Triple(VoiceEntryMode.DOUBAO_DUPLEX, Icons.Rounded.GraphicEq, R.string.voice_mode_doubao),
        ).filter { it.first in modes }.forEach { (mode, icon, label) ->
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                text = { Text(stringResource(label), style = MiuixTheme.textStyles.body2) },
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun VoiceModeStatusPanel(
    state: VoiceModeState,
    onStop: () -> Unit,
) {
    val phase = when {
        state.error != null -> state.error
        state.phase == VoiceModePhase.Connecting -> stringResource(R.string.voice_mode_connecting)
        state.phase == VoiceModePhase.Listening -> stringResource(R.string.voice_mode_listening)
        state.phase == VoiceModePhase.Transcribing -> stringResource(R.string.voice_mode_transcribing)
        state.phase == VoiceModePhase.Thinking -> stringResource(R.string.voice_mode_thinking)
        state.phase == VoiceModePhase.Speaking -> stringResource(R.string.voice_mode_speaking)
        else -> stringResource(R.string.voice_mode_open)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (state.mode == VoiceEntryMode.DOUBAO_DUPLEX) {
                    Icons.Rounded.GraphicEq
                } else {
                    Icons.Rounded.RecordVoiceOver
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (state.error == null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
            )
            Text(
                text = phase,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onStop, minWidth = 36.dp, minHeight = 36.dp) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = stringResource(R.string.voice_mode_stop),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        val detail = listOfNotNull(
            state.transcript.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.voice_recognized_text, it)
            },
            state.reply.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.voice_reply_text, it)
            },
        ).joinToString("\n")
        if (detail.isNotBlank()) {
            VoiceTranscript(detail, resetKey = state.mode)
        }
    }
}

@Composable
private fun VoiceTranscript(detail: String, resetKey: Any?) {
    val scroll = rememberScrollState()
    var follow by remember(resetKey) { mutableStateOf(true) }
    var userGesture by remember(resetKey) { mutableStateOf(false) }
    val connection = remember(scroll, resetKey) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userGesture = true
                    follow = false
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && userGesture) follow = !scroll.canScrollForward
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (userGesture) {
                    follow = !scroll.canScrollForward
                    userGesture = false
                }
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(scroll, resetKey) {
        snapshotFlow { Triple(scroll.maxValue, follow, scroll.isScrollInProgress) }.collectLatest { (end, enabled, moving) ->
            if (enabled && !moving && end > 0) scroll.scrollTo(end)
        }
    }
    Text(
        text = detail,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        modifier = Modifier.padding(start = 30.dp, end = 8.dp, bottom = 4.dp)
            .heightIn(max = 160.dp).nestedScroll(connection).verticalScroll(scroll),
    )
}
