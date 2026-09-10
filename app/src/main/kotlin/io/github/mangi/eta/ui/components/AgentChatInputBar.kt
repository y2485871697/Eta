package io.github.mangi.eta.ui.components

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.liveContextUsage
import io.github.mangi.eta.ui.model.shouldBlockSendForContextWindow
import io.github.mangi.eta.ui.model.shouldShowLiveContextUsage
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SendButtonVisualSize = ChatInputActionIconSize
private val SendIconSize = 16.dp
private val StopIconSize = 10.dp
private val ThinkingIconSize = 21.dp
private val InputContainerShape = RoundedCornerShape(20.dp)

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@Composable
internal fun AgentChatInputBar(
    input: String,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    autoCompressEnabled: Boolean,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    isEditingMessage: Boolean,
    editHasLaterTurns: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    isPaused: Boolean = false,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onOpenAssistantPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val textFieldState = rememberTextFieldState(initialText = input)
    var wasEditingMessage by remember { mutableStateOf(isEditingMessage) }
    val draftText = textFieldState.text.toString()
    val historyTokenCount = remember(history) {
        history.sumOf { AgentContextBudget.countMessage(it) }
    }
    val liveUsage = remember(
        historyTokenCount,
        draftText,
        pendingImages,
        pendingFileReferences,
        modelPickerState.selectedModel,
    ) {
        liveContextUsage(
            // History tokens are cached above so typing does not rescan the transcript.
            history = emptyList(),
            currentInput = draftText,
            pendingImages = pendingImages,
            selectedModel = modelPickerState.selectedModel,
            pendingFileReferences = pendingFileReferences,
            historyTokenCount = historyTokenCount,
        )
    }
    val contextSendBlocked = shouldBlockSendForContextWindow(autoCompressEnabled, liveUsage)
    val canSend = !contextSendBlocked && (
        textFieldState.text.isNotBlank() ||
            pendingImages.isNotEmpty() ||
            pendingFileReferences.isNotEmpty()
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
    LaunchedEffect(isEditingMessage) {
        // 编辑态由外部业务状态驱动；普通输入只保留在本地，避免每个字符把聊天舞台
        // 的消息流、滚动和 Markdown 一起带入重组。
        if (isEditingMessage || wasEditingMessage) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
        }
        if (isEditingMessage && !wasEditingMessage) {
            showChatInputIme(focusRequester, keyboard, view)
        }
        wasEditingMessage = isEditingMessage
    }

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            // 发送按钮、建议词和外部恢复都可能启动流式任务，统一清掉本地草稿。
            textFieldState.clearText()
        }
    }

    CompositionLocalProvider(LocalChatInputFocusRequester provides focusRequester) {
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
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
                            .focusRequester(focusRequester),
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                                onAttachFiles = onAttachFiles,
                                onAttachFolder = onAttachFolder,
                                onAttachFilePath = onAttachFilePath,
                            )

                            Spacer(modifier = Modifier.width(2.dp))

                            if (availableReasoningEfforts.isNotEmpty()) {
                                ThinkingEffortChip(
                                    effort = reasoningEffort,
                                    options = availableReasoningEfforts,
                                    enabled = !isStreaming,
                                    onEffortChange = onReasoningEffortChange,
                                )
                            }

                            Spacer(modifier = Modifier.width(2.dp))

                            AssistantPickerButton(
                                enabled = !isStreaming,
                                onClick = onOpenAssistantPicker,
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (shouldShowLiveContextUsage(showContextUsage, contextSendBlocked, liveUsage)) {
                            AgentContextUsageButton(
                                usage = liveUsage,
                                sendBlocked = contextSendBlocked,
                            )

                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        AgentModelPickerButton(
                            state = modelPickerState,
                            isStreaming = isStreaming,
                            popupAnchorTopPx = inputContainerTopPx,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            onModelSelected = onModelSelected,
                        )

                        val sendMode = resolveChatComposerSendMode(
                            isStreaming = isStreaming,
                            isPaused = isPaused,
                            hasSteerText = textFieldState.text.isNotBlank(),
                            canStartNewSend = canSend,
                        )
                        IconButton(
                            onClick = {
                                when (sendMode) {
                                    "stop" -> onStop()
                                    "continue" -> onContinue()
                                    "send" -> {
                                        val submittedText = textFieldState.text.toString()
                                        textFieldState.clearText()
                                        onSubmit(submittedText)
                                    }
                                }
                            },
                            enabled = sendMode != "idle",
                            minWidth = ChatInputActionSize,
                            minHeight = ChatInputActionSize,
                        ) {
                            val sendButtonColor by animateColorAsState(
                                targetValue = when (sendMode) {
                                    "stop" -> MiuixTheme.colorScheme.onSurface
                                    "continue", "send" -> MiuixTheme.colorScheme.primary
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
                                            "continue" -> stringResource(R.string.chat_continue)
                                            else -> stringResource(R.string.chat_send)
                                        },
                                        modifier = Modifier.size(
                                            if (mode == "stop") StopIconSize else SendIconSize
                                        ),
                                        tint = when (mode) {
                                            "stop" -> MiuixTheme.colorScheme.surface
                                            "continue", "send" -> MiuixTheme.colorScheme.onPrimary
                                            else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                                        },
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
    }

}

/** 思考强度选择保持为单一图标，当前状态仅通过图标颜色表达。 */
@Composable
private fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    enabled: Boolean,
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val menuState = rememberEtaMenuState()
    val active = effort != ReasoningEffort.OFF
    val menuEnabled = enabled && options.isNotEmpty()
    LaunchedEffect(menuEnabled) {
        if (!menuEnabled) menuState.dismiss()
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
    Box(modifier = modifier) {
        ChatInputNonFocusableIconButton(
            onClick = { if (menuEnabled) menuState.onAnchorClick() },
            contentDescription = stringResource(R.string.chat_reasoning_effort, effort.displayName),
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_atom),
                contentDescription = null,
                modifier = Modifier.size(ThinkingIconSize),
                tint = if (menuEnabled) contentColor else contentColor.copy(alpha = 0.38f),
            )
        }
        EtaDropdownMenu(
            expanded = menuState.expanded && menuEnabled,
            onDismissRequest = menuState::dismiss,
            preferAbove = true,
            minWidth = 0.dp,
            focusable = false,
        ) {
            options.forEach { option ->
                ThinkingEffortMenuRow(
                    option = option,
                    selected = option == effort,
                    onClick = {
                        menuState.dismiss()
                        onEffortChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ThinkingEffortMenuRow(
    option: ReasoningEffort,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .squircleSurface(
                color = if (selected) {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
                cornerRadius = 12.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.size(18.dp))
        Text(
            text = option.displayName,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        } else {
            Spacer(modifier = Modifier.size(18.dp))
        }
    }
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
                rememberDataUrlBitmap(image.dataUrl)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profiles by AssistantRepository.profiles.collectAsState()
    val activeId by AssistantRepository.activeId.collectAsState()
    val assistant = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    ChatInputNonFocusableIconButton(
        onClick = { if (enabled) onClick() },
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
}

internal fun resolveChatComposerSendMode(
    isStreaming: Boolean,
    isPaused: Boolean,
    hasSteerText: Boolean,
    canStartNewSend: Boolean,
): String = when {
    (isStreaming || isPaused) && hasSteerText -> "send"
    isPaused -> "continue"
    isStreaming -> "stop"
    canStartNewSend -> "send"
    else -> "idle"
}
