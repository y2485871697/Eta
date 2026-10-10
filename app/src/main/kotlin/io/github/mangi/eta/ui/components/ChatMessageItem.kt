package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.markdown.LocalPreparedMarkdownBlock
import io.github.mangi.eta.ui.markdown.PreparedMarkdownBlock
import io.github.mangi.eta.ui.markdown.PreparedMarkdownSpec
import io.github.mangi.eta.ui.markdown.ChatSelectableText
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.mangi.eta.ui.haptics.HapticSelectionContainer
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserSessionSnapshot
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.ui.markdown.NumericCitationMarkup
import io.github.mangi.eta.ui.markdown.markdownRenderCacheKey
import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import io.github.mangi.eta.ui.markdown.StreamingGfmSnapshot
import io.github.mangi.eta.ui.markdown.nextStreamingSnapshot
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isVideoAt
import io.github.mangi.eta.ui.model.durationMsAt
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.ui.model.fullImageSourceAt
import io.github.mangi.eta.ui.model.visibleFileReferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CHECK_BOX
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 返回 true 前先让出一帧。用于点击展开：点击那一帧只重组标题并启动展开动画，
 * 正文的组合与文字测量放到下一帧。[deferOneFrame] 只在进入组合时读取一次。
 */
@Composable
internal fun rememberDeferredBody(deferOneFrame: Boolean): Boolean {
    var ready by remember { mutableStateOf(!deferOneFrame) }
    if (!ready) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            ready = true
        }
    }
    return ready
}

/**
 * 预览图解码不在组合里做：滚动预取和展开时同步解码会直接占用那一帧。
 * 命中缓存时立即返回，否则先返回 null（调用方占位尺寸固定），后台解码后再刷新。
 */
@Composable
internal fun rememberDataUrlBitmap(
    dataUrl: String,
    fallback: String? = null,
): ImageBitmap? {
    val context = LocalContext.current
    val cached = remember(dataUrl, fallback) {
        ChatPreviewBitmapCache.get(dataUrl) ?: fallback?.let(ChatPreviewBitmapCache::get)
    }
    val loaded = produceState(initialValue = cached, dataUrl, fallback, context) {
        if (cached != null) {
            value = cached
            return@produceState
        }
        if (dataUrl.isBlank() && fallback.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bitmap = decodeDataUrlBitmap(dataUrl)
                ?: fallback?.let(::decodeDataUrlBitmap)
                ?: loadPreviewBitmap(context, dataUrl)
                ?: fallback?.takeIf { it != dataUrl }?.let { loadPreviewBitmap(context, it) }
            bitmap?.also { ChatPreviewBitmapCache.put(dataUrl, it) }
        }
    }
    return loaded.value ?: cached
}

private fun loadPreviewBitmap(context: android.content.Context, source: String): ImageBitmap? {
    if (source.isBlank()) return null
    return ChatImageBytes.load(context, source)?.bitmap
        ?: decodeDataUrlBitmap(source)
        ?: AgentVideoCodec.fileFromSource(source)?.let { file ->
            ChatImageBytes.load(context, AgentVideoCodec.previewThumbnail(file, source).reference)?.bitmap
        }
}

private fun decodeDataUrlBitmap(dataUrl: String): ImageBitmap? {
    if (!dataUrl.startsWith("data:image/", ignoreCase = true)) return null
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/**
 * 等待首个文本片段时的反馈。圆底变形指示器，对齐 RikkaHub 的 ContainedLoadingIndicator。
 */
@Composable
fun AITypingIndicator(modifier: Modifier = Modifier) {
    if (LocalAppearanceSettings.current.morphLoadingIndicator) {
        ContainedMorphLoadingIndicator(modifier = modifier)
    }
}

/**
 * 只有正在执行的状态才持有无限动画。历史思考和工具条目保持静态，避免长会话里
 * 每个已完成节点都持续产生帧时钟与状态更新。
 */
@Composable
private fun rememberActivePulse(
    active: Boolean,
    label: String,
): () -> Float {
    if (!active) return StaticPulseAlpha
    val transition = rememberInfiniteTransition(label = label)
    val alpha = transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}_alpha",
    )
    // 只把读取交给 graphicsLayer 的绘制块：每帧的透明度变化只重画图标，
    // 不再让整行思考、工具或工作过程标题跟着重组。
    return remember(alpha) { { alpha.value } }
}

private val StaticPulseAlpha: () -> Float = { 1f }

@Stable
internal class ChatMessageActions {
    var onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit by mutableStateOf({ _, _, _ -> })
    var onSubmitQuestionAnswer: (String, String) -> Unit by mutableStateOf({ _, _ -> })
    var onSuggestionClick: (String) -> Unit by mutableStateOf<(String) -> Unit>({})
    var onRunTraceClick: () -> Unit by mutableStateOf<() -> Unit>({})
    var onOpenBrowser: () -> Unit by mutableStateOf<() -> Unit>({})
    var onEditMessage: (String) -> Unit by mutableStateOf<(String) -> Unit>({})
    var onDeleteMessage: (String) -> Unit by mutableStateOf<(String) -> Unit>({})
    var onRegenerateMessage: (String) -> Unit by mutableStateOf<(String) -> Unit>({})
    var onBranchMessage: (String) -> Unit by mutableStateOf<(String) -> Unit>({})
}

/**
 * 点开时这一行的下沿会不会被钉住：跟底输出时尾部停在静止线，或内容不满一屏贴底。
 * 钉住时展开从下沿长出、收起收向下沿，内容在屏幕上不动，只有标签移动；
 * 没钉住时标签不动，内容向下长。只在点击回调里调用，不参与组合。
 */
internal val LocalExpansionHoldsBottom = staticCompositionLocalOf<() -> Boolean> { { false } }


@Composable
internal fun ChatMessageItem(
    message: AgentChatMessageUi,
    actions: ChatMessageActions,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    retainedStreamingState: StreamingMarkdownState? = null,
    showCopyAction: Boolean = true,
    showMessageActions: Boolean = false,
    messageActionsEnabled: Boolean = true,
    branchEnabled: Boolean = true,
    isEditing: Boolean = false,
    isPaused: Boolean = false,
    enableLivePreview: Boolean = true,
    speechPreface: String = "",
    onThinkingToggle: ((String, Boolean) -> Unit)? = null,
) {
    when (message) {
        is io.github.mangi.eta.ui.model.AgentQuestionMessageUi -> AgentQuestionCard(message, modifier,
            onDraftChanged = { draft -> actions.onQuestionDraftChanged(message.request.conversationId, message.request.questionId, draft) },
            onSubmit = { actions.onSubmitQuestionAnswer(message.request.conversationId, message.request.questionId) })
        is UserMessageUi -> UserMessageBubble(
            message = message,
            actionsEnabled = messageActionsEnabled,
            branchEnabled = branchEnabled,
            isEditing = isEditing,
            onEdit = { actions.onEditMessage(message.id) },
            onDelete = { actions.onDeleteMessage(message.id) },
            onBranch = { actions.onBranchMessage(message.id) },
            modifier = modifier,
        )
        is AgentMessageUi -> AgentMessageBlock(
            message = message,
            allowSpeech = true,
            speechPreface = speechPreface,
            retainedStreamingState = retainedStreamingState,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            messageActionsEnabled = messageActionsEnabled,
            branchEnabled = branchEnabled,
            isPaused = isPaused,
            onDelete = { actions.onDeleteMessage(message.id) },
            onRegenerate = { actions.onRegenerateMessage(message.id) },
            onBranch = { actions.onBranchMessage(message.id) },
            modifier = modifier,
        )
        is ErrorReconnectMessageUi -> ErrorReconnectDivider(message, modifier)
        is SystemNoticeMessageUi -> if (message.code == SystemNoticeCode.RuntimeFailed) {
            ErrorReconnectDivider(
                ErrorReconnectMessageUi(
                    id = message.id, runId = "", reconnectId = "legacy:${message.id}", round = 0,
                    status = ErrorReconnectStatus.Failed, reasonDetail = message.detail.orEmpty(), isReconnect = false,
                ), modifier,
            )
        } else if (message.code == SystemNoticeCode.Completed) {
            TaskCompletedDivider(modifier)
        } else AgentMessageBlock(
            message = AgentMessageUi(
                id = message.id,
                content = buildString {
                    append(
                        stringResource(
                            when (message.code) {
                                SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                                SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                                SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                                SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                                SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                                SystemNoticeCode.Completed -> R.string.system_notice_completed
                            },
                        ),
                    )
                    message.detail?.takeIf(String::isNotBlank)?.let { detail ->
                        append("\n\n")
                        append(detail)
                    }
                },
                renderMarkdown = false,
            ),
            retainedStreamingState = null,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            messageActionsEnabled = messageActionsEnabled,
            branchEnabled = branchEnabled,
            isPaused = isPaused,
            onDelete = { actions.onDeleteMessage(message.id) },
            onRegenerate = { actions.onRegenerateMessage(message.id) },
            onBranch = { actions.onBranchMessage(message.id) },
            modifier = modifier,
        )
        is ThinkingMessageUi -> ThinkingRow(
            message = message,
            retainedStreamingState = retainedStreamingState,
            modifier = modifier,
            compact = compact,
            isPaused = isPaused,
            onToggle = onThinkingToggle,
        )
        is RunTraceMessageUi -> RunTraceRow(message = message, onClick = actions.onRunTraceClick, modifier = modifier)
        is ToolActivityMessageUi -> ToolActivityInline(
            message = message,
            onOpenBrowser = actions.onOpenBrowser,
            showBrowserShortcut = showBrowserShortcut,
            enableLivePreview = enableLivePreview,
            modifier = modifier,
            compact = compact,
        )
        is ToolSummaryMessageUi -> ToolSummaryInline(message = message, modifier = modifier, compact = compact)
        is ContextCompactedMessageUi -> ContextCompactedDivider(message = message, modifier = modifier)
        is SuggestionChipsMessageUi -> SuggestionChipsRow(message = message, onSuggestionClick = actions.onSuggestionClick, modifier = modifier)
    }
}

/**
 * 把连续的思考与工具调用收束为一个可展开的工作过程，避免 Agent 事件退化为聊天气泡噪音。
 */
@Composable
internal fun AgentWorkProcessHeader(
    messages: List<AgentChatMessageUi>,
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
    expanded: Boolean,
    hasVisibleSteps: Boolean = expanded,
    onToggle: () -> Unit,
) {
    val running = messages.any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    val toolCount = messages.count { it is ToolActivityMessageUi }
    val runningTool = messages.lastOrNull { message ->
        message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running
    } as? ToolActivityMessageUi
    val runningToolTitle = runningTool?.argumentsSummary?.takeIf { it.isNotBlank() }
        ?: runningTool?.let { toolDisplayName(it.toolName) }
    val view = LocalView.current
    SideEffect {
        if (isPaused) return@SideEffect
        messages.forEach { message ->
            val liveId = when {
                message is ToolActivityMessageUi &&
                    message.status == ToolActivityStatusUi.Running -> message.id
                message is ThinkingMessageUi && message.isStreaming -> message.id
                else -> null
            }
            if (liveId != null) {
                TouchHaptics.onLiveToolActivity(view, liveId)
            }
        }
    }

    val pulseAlpha = rememberActivePulse(active = running && !isPaused, label = "work_pulse")

    WorkProcessCardSlice(
        part = if (hasVisibleSteps && messages.isNotEmpty()) WorkProcessCardPart.First else WorkProcessCardPart.Whole,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null, onClick = onToggle)
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when {
                        runningTool != null -> iconForTool(runningTool.toolName)
                        running -> ImageVector.vectorResource(R.drawable.ic_atom)
                        else -> Icons.Rounded.Build
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer { alpha = if (running && !isPaused) pulseAlpha() else 1f },
                    tint = if (running && !isPaused) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        running && toolCount > 0 -> pluralStringResource(
                            R.plurals.work_processing_step,
                            toolCount,
                            toolCount,
                        ) + (runningToolTitle?.let { " · $it" } ?: "")
                        running -> stringResource(R.string.work_analyzing)
                        toolCount > 0 -> pluralStringResource(
                            R.plurals.work_completed_steps,
                            toolCount,
                            toolCount,
                        )
                        else -> stringResource(R.string.work_completed)
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = if (running && !isPaused) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandMore
                        else Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(
                        if (expanded) R.string.work_collapse else R.string.work_expand,
                    ),
                    modifier = Modifier.size(14.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                )
            }

            if (hasVisibleSteps && messages.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp)
                        .height(0.5.dp)
                        .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                )
            }
        }
    }
}


// ── 用户消息：轻盈美观气泡 ──────────────────────────────────────────────

@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    actionsEnabled: Boolean,
    branchEnabled: Boolean,
    isEditing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBranch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }
    val diagnosticRow = LocalStreamDiagnosticRow.current
    SideEffect {
        StreamPerformanceDiagnostics.record("render.userBubble.compose")
    }
    val visiblePrompt = remember(message.content) {
        StreamPerformanceDiagnostics.measure("render.userPrompt.parse") {
            AgentFileReferencePromptCodec.parse(message.content)
        }
    }
    val visibleFiles = remember(visiblePrompt.references, message.images, message.imageSources) {
        message.visibleFileReferences(visiblePrompt.references)
    }
    val copyText = visiblePrompt.request.ifBlank {
        visiblePrompt.conversations.joinToString(" ") { "@${it.title}" }
    }
    val view = LocalView.current

    Column(
        modifier = modifier
            .streamDiagnosticMeasure("render.userBubble.measure", diagnosticRow)
            .streamDiagnosticDraw("render.userBubble.draw", diagnosticRow)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomEnd = 6.dp,
                    bottomStart = 20.dp,
                )
                .then(
                    if (isEditing) {
                        Modifier.squircleBorder(
                            width = 1.dp,
                            color = MiuixTheme.colorScheme.primary,
                            cornerRadius = 20.dp,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            if (message.images.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    message.images.forEachIndexed { index, dataUrl ->
                        key(index, dataUrl, message.fullImageSourceAt(index)) {
                            val bitmap = rememberDataUrlBitmap(
                                dataUrl.ifBlank { message.fullImageSourceAt(index) },
                                fallback = message.fullImageSourceAt(index),
                            )
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.surfaceContainer),
                            ) {
                                if (bitmap != null) {
                                    ChatClickableImage(
                                        source = message.fullImageSourceAt(index),
                                        bitmap = bitmap,
                                        contentDescription = stringResource(
                                            if (message.isVideoAt(index)) {
                                                R.string.chat_video_preview
                                            } else {
                                                R.string.chat_image_preview
                                            },
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                if (message.isVideoAt(index)) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(28.dp),
                                    )
                                    Text(
                                        text = AgentVideoCodec.formatDuration(message.durationMsAt(index) ?: 0L),
                                        style = MiuixTheme.textStyles.body2,
                                        color = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            visiblePrompt.conversations.forEach { mention ->
                Text(
                    text = "@${mention.title}" + if (mention.transcript.contains("[已截取：")) " · 已截取" else " · 全文",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (visibleFiles.isNotEmpty()) {
                SentFileReferenceFlow(
                    references = visibleFiles,
                    modifier = Modifier.padding(
                        bottom = if (visiblePrompt.request.isNotBlank()) 8.dp else 0.dp
                    ),
                )
            }
            if (visiblePrompt.request.isNotBlank()) {
                HapticSelectionContainer {
                    Text(
                        text = visiblePrompt.request,
                        modifier = Modifier
                            .streamDiagnosticMeasure("render.userText.measure", diagnosticRow)
                            .streamDiagnosticDraw("render.userText.draw", diagnosticRow),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            if (message.isEdited) {
                Text(
                    text = stringResource(R.string.ui_edited_c36776),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipBox(text = stringResource(R.string.ui_copy_4edd1d), enabled = true) {
                IconButton(
                    onClick = {
                        TouchHaptics.click(view)
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(copyText))
                        copied = true
                    },
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(
                            if (copied) R.string.copy_copied else R.string.ui_copy_4edd1d,
                        ),
                        modifier = Modifier.size(15.dp),
                        tint = if (copied) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
                        },
                    )
                }
            }
            TooltipBox(text = stringResource(R.string.ui_edit_a7f814), enabled = actionsEnabled) {
                IconButton(
                    onClick = {
                        TouchHaptics.click(view)
                        onEdit()
                    },
                    enabled = actionsEnabled,
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.ui_edit_a7f814),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                    )
                }
            }
            TooltipBox(text = stringResource(R.string.ui_branch_conversation), enabled = branchEnabled) {
                IconButton(
                    onClick = {
                        TouchHaptics.click(view)
                        onBranch()
                    },
                    enabled = branchEnabled,
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallSplit,
                        contentDescription = stringResource(R.string.ui_branch_conversation),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                    )
                }
            }
            TooltipBox(text = stringResource(R.string.ui_delete_3755f5), enabled = actionsEnabled) {
                IconButton(
                    onClick = {
                        TouchHaptics.click(view)
                        onDelete()
                    },
                    enabled = actionsEnabled,
                    minWidth = 30.dp,
                    minHeight = 30.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.ui_delete_3755f5),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

// ── Agent 结果 ───────────────────────────────────────────────────────

@Composable
private fun AgentMessageBlock(
    message: AgentMessageUi,
    allowSpeech: Boolean = false,
    speechPreface: String = "",
    retainedStreamingState: StreamingMarkdownState?,
    showCopyAction: Boolean,
    showMessageActions: Boolean,
    messageActionsEnabled: Boolean,
    branchEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit = {},
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
) {
    val keepStreamingMarkdown = message.isStreaming || retainedStreamingState != null
    val displayContent = remember(message.content) {
        StreamPerformanceDiagnostics.measure("markdown.citation.strip", message.content.length.toLong()) {
            NumericCitationMarkup.strip(message.content)
        }
    }
    // Completion belongs to an exact source revision. Late text must not inherit
    // the previous revision's true flag while its parser/reveal effect catches up.
    var streamingRevealComplete by remember(message.id, message.content) {
        mutableStateOf(!keepStreamingMarkdown ||
            (!message.isStreaming && retainedStreamingState?.revealedContent == message.content))
    }
    LaunchedEffect(message.isStreaming) {
        if (message.isStreaming) streamingRevealComplete = false
    }
    // 渲染会话由列表层按 message.id 持有，item 滚出视口被销毁后滑回时复用同一
    // 会话；没有外部持有者时（如嵌套条目）退回组合内 remember，行为与之前一致。
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    LaunchedEffect(retainedStreamingState, streamingRevealComplete, message.content, message.isStreaming) {
        retainedStreamingState?.revealedContent = message.content.takeIf {
            streamingRevealComplete && !message.isStreaming
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        if (message.content.isBlank() && message.isStreaming) {
            AITypingIndicator(
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            HapticSelectionContainer {
                when {
                    // 流式会话一旦建立就不要切到 StableMarkdown：暂停继续和生成结束
                    // 都会让 isStreaming 翻转，整棵 Markdown 重挂会闪一帧。
                    streamingState != null -> {
                        StreamingMarkdown(
                            state = streamingState,
                            content = displayContent,
                            isStreaming = message.isStreaming,
                            animateInitialContent = !message.isStreaming &&
                                displayContent.isNotEmpty() &&
                                streamingState.documentState.snapshot == null &&
                                streamingState.revealedContent == null,
                            isPaused = isPaused,
                            onRevealCompleteChange = { streamingRevealComplete = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    message.renderMarkdown -> {
                        StableMarkdown(
                            content = displayContent,
                            modifier = completedContentDrawLayer(Modifier.fillMaxWidth(), true),
                        )
                    }
                    message.content.isNotBlank() -> {
                        Text(
                            text = message.content,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = completedContentDrawLayer(Modifier, true),
                        )
                    }
                }
            }
        }

        if (
            showCopyAction &&
            !message.isStreaming &&
            message.content.isNotBlank() &&
            (!keepStreamingMarkdown || streamingRevealComplete)
        ) {
            AgentMessageActionRow(
                messageId = message.id,
                content = message.content,
                allowSpeech = allowSpeech,
                speechPreface = speechPreface,
                generatedAtMillis = message.generatedAtMillis,
                showMessageActions = showMessageActions,
                messageActionsEnabled = messageActionsEnabled,
                branchEnabled = branchEnabled,
                onDelete = onDelete,
                onRegenerate = onRegenerate,
                onBranch = onBranch,
            )
        }
    }
}

/** 当前点开的推理把分帧组合进度和 loading 回退记进同一个点击窗口。 */
private val LocalToggleProbe = staticCompositionLocalOf<ToggleProbeRef?> { null }

/** Both ordinary answers and thinking use upstream prepared-document rendering. */
@Composable
private fun StableMarkdown(
    content: String, modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
    progressive: Boolean = false,
) {
    val bodyTraceMount = remember { nextChatBodyTraceMount() }
    SideEffect { traceChatBodyRun("md", bodyTraceMount) }
    io.github.mangi.eta.ui.markdown.DocumentStaticMarkdown(
        content, modifier,
        if (tone == ChatMarkdownTone.Answer) io.github.mangi.eta.ui.markdown.MarkdownTone.Answer
        else io.github.mangi.eta.ui.markdown.MarkdownTone.Thinking,
    )
}

@Composable
private fun StreamingMarkdown(
    state: StreamingMarkdownState, content: String, isStreaming: Boolean,
    animateInitialContent: Boolean = false, isPaused: Boolean = false,
    onRevealCompleteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
) {
    io.github.mangi.eta.ui.markdown.DocumentStreamingMarkdown(
        state = state.documentState, content = content, isStreaming = isStreaming,
        animateInitialContent = animateInitialContent, isPaused = isPaused,
        onRevealCompleteChange = onRevealCompleteChange, modifier = modifier,
        tone = if (tone == ChatMarkdownTone.Answer) io.github.mangi.eta.ui.markdown.MarkdownTone.Answer
            else io.github.mangi.eta.ui.markdown.MarkdownTone.Thinking,
    )
}

// Legacy AST renderer is retained only for isolated compatibility tests during migration.
// It is not called by AgentMessageBlock, ThinkingRow or either production wrapper above.
@Composable
private fun LegacyStableMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
    markdownState: MarkdownState = rememberCompletedMarkdownState(content),
    progressive: Boolean = false,
) {
    val bodyTraceMount = remember { nextChatBodyTraceMount() }
    SideEffect { traceChatBodyRun("md", bodyTraceMount) }
    val components = remember { chatMarkdownComponents() }
    CompletedMarkdownStateHost(markdownState) {
        Markdown(
            markdownState = markdownState,
            colors = chatMarkdownColors(tone),
            typography = chatMarkdownTypography(tone),
            padding = chatMarkdownPadding(),
            dimens = chatMarkdownDimens(),
            components = components,
            modifier = modifier,
            loading = {
                SideEffect { traceChatBodyRun("md.phase.loading", bodyTraceMount) }
                LocalToggleProbe.current?.let { ref ->
                    SideEffect { StreamPerformanceDiagnostics.probeEvent(ref.token, "markdown", "state=loading chars=${content.length}") }
                }
                // 保留与最终正文接近的高度，避免历史消息异步解析完成后越界绘制。
                Text(
                    text = content,
                    style = chatMarkdownBodyStyle(tone),
                    color = chatMarkdownTextColor(tone),
                    modifier = it,
                )
            },
            error = {
                SideEffect { traceChatBodyRun("md.phase.error", bodyTraceMount) }
                Text(
                    text = content,
                    style = chatMarkdownBodyStyle(tone),
                    color = chatMarkdownTextColor(tone),
                    modifier = it,
                )
            },
            success = { state, successComponents, successModifier ->
                SideEffect { traceChatBodyRun("md.phase.success", bodyTraceMount) }
                CacheCompletedMarkdownSuccess(content, markdownState, state)
                ChatMarkdownDocument(
                    root = state.node,
                    content = state.content,
                    components = successComponents,
                    modifier = successModifier,
                    progressive = progressive,
                )
            },
        )
    }
}

/**
 * 流式渲染会话，按 message.id 提升到 LazyColumn 外层持有。
 *
 * 流式 item 滚出视口后组合会被销毁，裸 remember 会让解析基线、打字机进度和最新
 * 快照全部丢失；滑回时整段已生成内容会重新全量解析，并从头重放显现动画。会话
 * 与组合解耦后，item 重建只是重新挂接效果，渲染进度原样保留。
 */
@Stable
internal class StreamingMarkdownState {
    val documentState = io.github.mangi.eta.ui.markdown.DocumentStreamingState()
    var revealedContent by mutableStateOf<String?>(null)
    val parserSession = StreamingGfmParserSession()
    val revealCoordinator = SmoothTextRevealCoordinator().apply { pauseAnimationsAndCatchUp() }
    val restoreState = StreamingMarkdownRestoreState()
    val parseTargets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
    val acceptedContent = arrayOf("")
    var snapshot by mutableStateOf<StreamingGfmSnapshot?>(null)
    var completedRevealSource by mutableStateOf<String?>(null)
    val compositionProgress = ProgressiveMarkdownCompositionState()
}

@Composable
private fun LegacyStreamingMarkdown(
    state: StreamingMarkdownState,
    content: String,
    isStreaming: Boolean,
    animateInitialContent: Boolean = false,
    isPaused: Boolean = false,
    onRevealCompleteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
) {
    val parserSession = state.parserSession
    val typography = chatMarkdownTypography(tone)
    val inlineCode = typography.inlineCode.copy(
        background = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    ).toSpanStyle()
    val uriHandler = LocalUriHandler.current
    val preparedListener = remember(uriHandler) {
        LinkInteractionListener { link ->
            (link as? LinkAnnotation.Url)?.url?.let { url ->
                try { uriHandler.openUri(url) } catch (_: Throwable) { /* Same non-fatal link boundary. */ }
            }
        }
    }
    val preparedAnnotator = remember { markdownAnnotator() }
    val renderSpec = remember(typography, inlineCode, preparedListener, preparedAnnotator) {
        PreparedMarkdownSpec(typography, inlineCode, typography.textLink, preparedListener, preparedAnnotator)
    }
    val revealCoordinator = state.revealCoordinator
    val compositionProgress = state.compositionProgress
    val components = remember(revealCoordinator) {
        chatMarkdownComponents(
            revealCoordinator = revealCoordinator,
            suppressEmptyListMarkers = true,
        )
    }
    val parseTargets = state.parseTargets
    val acceptedContent = state.acceptedContent
    val currentRevealCompleteCallback by rememberUpdatedState(onRevealCompleteChange)
    val snapshot = state.snapshot
    val currentContent by rememberUpdatedState(content)
    // Pausing is not a terminal parser target. Lifecycle restore and user pause
    // share a single animation gate; parent effects must not resume before layout.
    val parseAsStreaming = isStreaming || isPaused
    val currentIsStreaming by rememberUpdatedState(parseAsStreaming)
    val currentPaused by rememberUpdatedState(isPaused)
    val restoreGeneration = state.restoreState.generation
    val view = LocalView.current
    val routeCovered = LocalChatRouteCovered.current
    val transitionActive = LocalChatTransitionActive.current
    val routeCoveredNow = rememberUpdatedState(routeCovered)
    // A lazy-row disposal is not an activity pause. Keep only unfinished output
    // mounted so its parser, reveal clock and frame-coupled haptics survive a fling.
    // The pin keeps composition, not the viewport position or measured height.
    KeepActiveStreamingRow(
        shouldKeepStreamingRow(
            content = content,
            isStreaming = isStreaming,
            isPaused = isPaused,
            completedRevealSource = state.completedRevealSource,
        ),
    )

    LifecycleResumeEffect(state) {
        val animateExisting = animateInitialContent && !currentPaused && currentContent.isNotEmpty()
        if (state.restoreState.begin(
                currentContent,
                live = currentIsStreaming && !currentPaused,
                animateExisting = animateExisting,
            )
        ) {
            // A new message may already contain a whole network batch when first composed,
            // including a batch whose block has already ended. Do not classify that first
            // batch as restored history and reveal it all at once.
            revealCoordinator.resumeAnimationsWithoutCatchingUp()
        } else {
            revealCoordinator.restoreHistoryThrough(currentContent.length)
        }
        onPauseOrDispose {
            // 回调不在组合里，读进入回调前记住的最新值。半遮住时导航把聊天降到
            // STARTED，页面还在组合里，继续逐字打。真正离开前台才追平，避免回来补播。
            if (routeCoveredNow.value) {
                state.restoreState.holdCovered()
            } else {
                state.restoreState.pause()
                revealCoordinator.pauseAnimationsAndCatchUp()
            }
        }
    }

    // 停稳且完全盖住才停住显现，而且不追平。横滑、松手回弹期间露出的边继续逐字打。
    val settledCover = routeCovered && !transitionActive
    val animationsAllowed = !settledCover && (
        state.restoreState.animationsAllowed(isPaused) || (routeCovered && transitionActive)
    )
    // Content is deliberately not a key. A streaming delta must not re-run the gate decision:
    // while the restore baseline is still pending, animationsAllowed is false, and every delta
    // would catch the reveal up to the newest text. That drains the pending records, the frame
    // clock parks on its wakeup channel, and the typewriter plus its haptics stop for the rest
    // of the message. Only a real gate change may move the coordinator.
    LaunchedEffect(revealCoordinator, animationsAllowed, isPaused, settledCover) {
        if (animationsAllowed) revealCoordinator.resumeAnimationsWithoutCatchingUp()
        else if (settledCover) revealCoordinator.holdAnimations()
        else if (!isPaused) revealCoordinator.pauseAnimationsAndCatchUp()
    }

    // An explicit user pause does keep following new text, because nothing will animate it later.
    // The restore baseline case must not take this path: there the reveal has to stay pending.
    LaunchedEffect(revealCoordinator, isPaused, content) {
        if (isPaused) revealCoordinator.restoreHistoryThrough(content.length)
    }

    LaunchedEffect(revealCoordinator, view) {
        // Drive feedback in the same frame as visible text, including the final drain.
        revealCoordinator.setOnRevealAdvanced {
            io.github.mangi.eta.ui.haptics.StreamingHaptics.onVisibleAdvance(view)
        }
        try {
            revealCoordinator.runFrameClock()
        } finally {
            revealCoordinator.setOnRevealAdvanced(null)
        }
    }

    LaunchedEffect(content, parseAsStreaming, renderSpec) {
        val previousContent = acceptedContent[0]
        if (!content.startsWith(previousContent)) {
            // 会话恢复或上游纠正内容时，让解析会话重新建立文档基线。
            acceptedContent[0] = ""
        }
        acceptedContent[0] = content
        StreamPerformanceDiagnostics.record("markdown.target", value = content.length.toLong())
        parseTargets.trySend(
            StreamingMarkdownTarget(
                content = content,
                isStreaming = parseAsStreaming,
                renderSpec = renderSpec,
            )
        )
        if (parseAsStreaming) {
            currentRevealCompleteCallback(false)
        }
    }

    LaunchedEffect(parserSession, parseTargets) {
        var target = parseTargets.receive()
        while (true) {
            while (true) {
                val newerTarget = parseTargets.tryReceive().getOrNull() ?: break
                StreamPerformanceDiagnostics.record("markdown.coalesced")
                target = newerTarget
            }

            val parsed = withContext(Dispatchers.Default) {
                // Restore only this synchronous parse slice, never across suspension.
                StreamPerformanceDiagnostics.withAttribution(target.diagnosticAttribution) {
                    StreamPerformanceDiagnostics.record("markdown.queueWait", System.nanoTime() - target.queuedAtNs)
                    StreamPerformanceDiagnostics.measure("markdown.parse", target.content.length.toLong()) {
                        parserSession.parse(
                            source = target.content,
                            isComplete = !target.isStreaming,
                            renderSpec = target.renderSpec,
                        )
                    }
                }
            }

            val newerTarget = parseTargets.tryReceive().getOrNull()
            if (newerTarget != null) {
                StreamPerformanceDiagnostics.record("markdown.superseded")
                target = newerTarget
                continue
            }

            val publishTarget = target
            StreamPerformanceDiagnostics.withAttribution(publishTarget.diagnosticAttribution) {
                StreamPerformanceDiagnostics.measure("markdown.publishBlock", publishTarget.content.length.toLong()) {
                    nextStreamingSnapshot(state.snapshot, parsed)?.let { published ->
                        StreamPerformanceDiagnostics.record("markdown.targetToPublish", System.nanoTime() - publishTarget.queuedAtNs)
                        StreamPerformanceDiagnostics.record("markdown.publish", value = published.originalSource.length.toLong())
                        state.compositionProgress.acceptSource(published.originalSource)
                        state.snapshot = published
                    }
                }
            }
            if (target.isStreaming) {
                delay(STREAMING_PARSE_PUBLISH_INTERVAL_MS)
            }
            target = parseTargets.receive()
        }
    }

    LaunchedEffect(content, parseAsStreaming, snapshot?.originalSource, snapshot?.isComplete, revealCoordinator, compositionProgress.publication) {
        val currentSnapshot = snapshot
        if (!isStreamingMarkdownTargetComplete(
                content = content,
                isStreaming = parseAsStreaming,
                snapshotContent = currentSnapshot?.originalSource,
                snapshotComplete = currentSnapshot?.isComplete == true,
            )
        ) {
            currentRevealCompleteCallback(false)
            return@LaunchedEffect
        }

        // Parsing alone is not composition. A terminal long document may still
        // be admitted over several frames; no pin release before that frontier.
        snapshotFlow { compositionProgress.composedSource }
            .first { it == currentSnapshot?.originalSource }
        // Drain can be momentarily true between two successive block layouts.
        // Require another layout frame after each drain before declaring completion.
        do {
            withFrameNanos { }
            if (!revealCoordinator.drained.value) {
                revealCoordinator.drained.filter { it }.first()
            }
            withFrameNanos { }
        } while (!revealCoordinator.drained.value)
        if (isStreamingMarkdownTargetComplete(
                content = currentContent,
                isStreaming = currentIsStreaming,
                snapshotContent = currentSnapshot?.originalSource,
                snapshotComplete = currentSnapshot?.isComplete == true,
            )
        ) {
            // Release the lazy pin only after this exact terminal revision has
            // parsed, laid out for a frame and drained. A transient drained=true
            // before onTextLayout must never release a still-pending row.
            state.completedRevealSource = currentContent
            currentRevealCompleteCallback(true)
        }
    }

    snapshot?.let { parsed ->
        val imageTransformer = rememberStreamingMarkdownImageTransformer(parsed)
        Markdown(
            state = parsed.state,
            annotator = preparedAnnotator,
            colors = chatMarkdownColors(tone),
            typography = chatMarkdownTypography(tone),
            padding = chatMarkdownPadding(),
            dimens = chatMarkdownDimens(),
            components = components,
            imageTransformer = imageTransformer,
            animations = markdownAnimations(animateTextSize = { this }),
            modifier = modifier.onGloballyPositioned {
                StreamPerformanceDiagnostics.record("markdown.layout", value = it.size.height.toLong())
                // 恢复基线对应的 AST 真正排版后才开放增量动画，解析耗时不受帧数限制。
                if (state.restoreState.completeLayout(
                        generation = restoreGeneration,
                        renderedContent = parsed.originalSource,
                        currentContent = currentContent,
                    )
                ) {
                    if (state.restoreState.animationsAllowed(currentPaused)) {
                        revealCoordinator.resumeAnimationsAfterCatchUp()
                    }
                }
            },
            success = { state, successComponents, successModifier ->
                StreamingGfmSuccess(
                    state = state,
                    preparedBlocks = parsed.preparedBlocks.takeIf { parsed.renderSpec == renderSpec },
                    components = successComponents,
                    revealCoordinator = revealCoordinator,
                    compositionProgress = compositionProgress,
                    compositionSource = parsed.originalSource,
                    compositionPublication = compositionProgress.publication,
                    modifier = successModifier,
                )
            },
        )
    }
}

/**
 * 顶层节点以源码位置和语法类型作为稳定身份。完整重解析只替换真正发生类型变化的
 * 当前块，前面已经稳定的段落、表格和代码块不会因新 chunk 到达而重新挂载。
 */
@Composable
private fun StreamingGfmSuccess(
    state: State.Success,
    preparedBlocks: List<PreparedMarkdownBlock>? = null,
    components: MarkdownComponents,
    revealCoordinator: SmoothTextRevealCoordinator,
    compositionProgress: ProgressiveMarkdownCompositionState,
    compositionSource: String,
    compositionPublication: Int,
    modifier: Modifier = Modifier,
) {
    val activeRevealBlocks = remember(state.node) {
        state.revealBlockKeys()
    }
    SideEffect {
        revealCoordinator.retainBlocks(activeRevealBlocks)
    }

    ChatMarkdownDocument(
        root = state.node,
        content = state.content,
        preparedBlocks = preparedBlocks,
        components = components,
        revealCoordinator = revealCoordinator,
        compositionProgress = compositionProgress,
        compositionSource = compositionSource,
        compositionPublication = compositionPublication,
        modifier = modifier,
    )
}

/**
 * 空行只负责切分 Markdown 块，不直接占据布局高度；可见块之间按语义分配留白，
 * 避免统一 block padding 让标题、正文、列表和表格失去层级。
 */
@Composable
private fun ChatMarkdownDocument(
    root: ASTNode,
    content: String,
    components: MarkdownComponents,
    modifier: Modifier = Modifier,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
    progressive: Boolean = false,
    preparedBlocks: List<PreparedMarkdownBlock>? = null,
    compositionProgress: ProgressiveMarkdownCompositionState? = null,
    compositionSource: String? = null,
    compositionPublication: Int = 0,
) {
    val bodyTraceMount = remember { nextChatBodyTraceMount() }
    val diagnosticRow = LocalStreamDiagnosticRow.current
    SideEffect { traceChatBodyRun("md.doc", bodyTraceMount) }
    val blocks = remember(root, preparedBlocks) {
        preparedBlocks?.map { it.node } ?: topLevelMarkdownBlocks(root)
    }
    // 用户点击展开长文档时，把整篇的组合与文字测量分摊到连续几帧，避免首帧一次性
    // 构建全部 AnnotatedString 并测量全文。只在进入组合时决定一次，历史滚入可视区
    // 的已展开内容仍一次到位，不会在滚动途中改变高度。
    // Streaming also spreads a long first composition. The reveal clock still
    // shows only the current tail, so later blocks stay unmeasured until then.
    val progressiveAtEntry = remember { progressive || revealCoordinator != null }
    val composedBlockLimit = if (progressiveAtEntry) {
        val streamingReveal = revealCoordinator != null
        val firstBudget = if (streamingReveal) STREAMING_PROGRESSIVE_FIRST_FRAME_CHARS else PROGRESSIVE_FIRST_FRAME_CHARS
        val frameBudget = if (streamingReveal) STREAMING_PROGRESSIVE_FRAME_CHARS else PROGRESSIVE_FRAME_CHARS
        val lengths = remember(blocks) { blocks.map { (it.endOffset - it.startOffset).coerceAtLeast(0) } }
        // 不用 blocks 当 key。流式增量每次都是新列表，重新记住会把已显示的块清掉，看起来整页闪。
        // 上限只增不减；新块到了，由下面的长度变化继续往前排。
        // The owner survives lazy-row disposal. Restoring already composed blocks
        // prevents a long history row shrinking to the first 480 chars on reentry.
        // Only the block count is retained; width/font changes still remeasure.
        var limit by remember(compositionProgress, compositionProgress?.generation) {
            mutableIntStateOf(
                compositionProgress?.initialLimit(lengths, firstBudget, streamingReveal)
                    ?: nextProgressiveBlockLimit(lengths, 0, firstBudget, mustAdvance = streamingReveal),
            )
        }
        SideEffect { compositionProgress?.recordLimit(limit, lengths.size, compositionSource, compositionPublication) }
        val probeRef = LocalToggleProbe.current
        LaunchedEffect(lengths.size, compositionProgress?.generation) {
            probeRef?.let { ref ->
                StreamPerformanceDiagnostics.probeEvent(
                    ref.token, "progressive", "blocks=$limit/${lengths.size} chars=${lengths.sum()}",
                )
            }
            while (limit < lengths.size) {
                withFrameNanos { }
                limit = nextProgressiveBlockLimit(lengths, limit, frameBudget)
                probeRef?.let { ref ->
                    StreamPerformanceDiagnostics.probeEvent(ref.token, "progressive", "blocks=$limit/${lengths.size}")
                }
            }
        }
        limit
    } else {
        Int.MAX_VALUE
    }
    val startedRevealKeys = rememberStartedRevealKeys(revealCoordinator)
    val nextRevealKey = remember(blocks, startedRevealKeys) {
        blocks.mapNotNull { it.firstRevealBlockKey() }
            .firstOrNull { it !in startedRevealKeys }
    }
    val lastVisibleStartOffset = remember(blocks, startedRevealKeys, nextRevealKey, revealCoordinator) {
        blocks.lastOrNull { node ->
            streamingMarkdownBlockVisible(
                coordinatorActive = revealCoordinator != null,
                firstRevealKey = node.firstRevealBlockKey(),
                startedRevealKeys = startedRevealKeys,
                nextRevealKey = nextRevealKey,
            )
        }?.startOffset
    }
    val density = LocalDensity.current
    var previousVisibleType: IElementType? = null
    // Inclusive document-container range; never add it to the inner block spans.
    Column(
        modifier
            .streamDiagnosticMeasure("render.measure", diagnosticRow)
            .streamDiagnosticDraw("render.draw", diagnosticRow),
    ) {
        blocks.forEachIndexed { index, node ->
            if (index >= composedBlockLimit) return@forEachIndexed
            val revealKey = node.firstRevealBlockKey()
            val visible = streamingMarkdownBlockVisible(
                coordinatorActive = revealCoordinator != null,
                firstRevealKey = revealKey,
                startedRevealKeys = startedRevealKeys,
                nextRevealKey = nextRevealKey,
            )
            if (!visible) return@forEachIndexed
            val gap = with(density) {
                markdownBlockSpacing(previousVisibleType, node.type).toDp()
            }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            previousVisibleType = node.type
            key(node.startOffset, node.type.name) {
                val preparedBlock = preparedBlocks?.getOrNull(index)
                val content = preparedBlock?.source ?: content
                val freeze = revealCoordinator != null &&
                    shouldFreezeStreamingMarkdownBlock(node.startOffset, lastVisibleStartOffset) &&
                    (preparedBlock == null || preparedBlock.cacheKey.referenceSource == null)
                // Freeze used to remount MarkdownElement and complete in-block reveal
                // via onDetach. Keep that catch-up without discarding the renderer.
                remember(freeze) {
                    if (freeze) {
                        revealCoordinator?.completeAttachedRecordsIn(node.startOffset, node.endOffset)
                    }
                }
                // preparedBlock.source is the whole document. Key the capture on this
                // block's own text so a longer tail does not recapture finished blocks.
                // Spec stays in the key so a theme change still refreshes them.
                val blockSource = preparedBlock?.cacheKey?.source
                val pinned = if (freeze && blockSource != null) {
                    remember(blockSource, preparedBlock.spec) { Triple(node, content, preparedBlock) }
                } else {
                    null
                }
                val renderNode = pinned?.first
                    ?: if (preparedBlock != null) node else rememberFrozenMarkdownInput(node, freeze)
                val renderContent = pinned?.second
                    ?: if (preparedBlock != null) content else rememberFrozenMarkdownInput(content, freeze)
                FrozenMarkdownElement(
                    node = renderNode,
                    components = components,
                    content = renderContent,
                    freeze = freeze,
                    preparedBlock = pinned?.third ?: preparedBlock,
                    diagnosticAttribution = remember(diagnosticRow, index, node.startOffset, node.type.name) {
                        StreamPerformanceDiagnostics.blockAttribution(
                            diagnosticRow, index, node.type.name, node.endOffset - node.startOffset)
                    },
                )
            }
        }
    }
}


@Composable
private fun FrozenMarkdownElement(
    node: ASTNode,
    components: MarkdownComponents,
    content: String,
    freeze: Boolean,
    diagnosticAttribution: StreamDiagnosticAttribution? = null,
    preparedBlock: PreparedMarkdownBlock? = null,
) {
    // A plain graphics layer still redraws when its parent records. Completed
    // blocks keep one layer and switch to an offscreen texture once they stop
    // changing, so the tail does not re-issue their text commands. The layer
    // stays mounted: inserting it only after freeze would remount the block.
    // Content above the retained-texture limit stays a plain layer; clipping
    // it would change the visible text. The tail is never cached.
    var retainedHeightPx by remember { mutableIntStateOf(0) }
    val retainTexture = freeze && retainedHeightPx in 1..MAX_RETAINED_LAYER_HEIGHT_PX
    Box(Modifier.graphicsLayer(
            compositingStrategy = if (retainTexture) {
                CompositingStrategy.Offscreen
            } else {
                CompositingStrategy.Auto
            },
        )
        .onSizeChanged { retainedHeightPx = it.height }
        .streamDiagnosticMeasure(if (freeze) "markdown.stable.measure" else "markdown.tail.measure", diagnosticAttribution)
        .drawWithContent {
            StreamPerformanceDiagnostics.withRenderAttribution(diagnosticAttribution) {
                StreamPerformanceDiagnostics.measure("markdown.blockDraw") {
                    if (StreamPerformanceDiagnostics.enabled) {
                        StreamPerformanceDiagnostics.measure(
                            if (freeze) "markdown.stable.draw" else "markdown.tail.draw",
                        ) { drawContent() }
                    } else {
                        drawContent()
                    }
                }
            }
        }) {
        // Keep one MarkdownElement call site. Freeze still pins the same node/content
        // the old branch remembered, so completed blocks do not remount (onForgotten)
        // while tail, typography and theme continue to follow live inputs.
        // Block text, not the whole document. A longer tail keeps this capture;
        // a real correction or theme change refreshes it.
        val blockSource = preparedBlock?.cacheKey?.source ?: content
        val pinned = if (freeze) remember(blockSource, preparedBlock?.spec) { Triple(node, content, preparedBlock) } else null
        val frozenNode = pinned?.first ?: node
        val frozenContent = pinned?.second ?: content
        val providedBlock = if (freeze) {
            pinned?.third?.takeIf { it.node === frozenNode && it.source === frozenContent }
        } else {
            preparedBlock
        }
        CompositionLocalProvider(LocalPreparedMarkdownBlock provides providedBlock) {
            MarkdownElement(
                node = frozenNode,
                components = components,
                content = frozenContent,
                includeSpacer = false,
            )
        }
    }
}

internal fun shouldFreezeStreamingMarkdownBlock(
    blockStartOffset: Int,
    tailStartOffset: Int?,
): Boolean = tailStartOffset != null && blockStartOffset != tailStartOffset

private const val STREAMING_PARSE_PUBLISH_INTERVAL_MS = 90L
private const val PROGRESSIVE_FIRST_FRAME_CHARS = 240
private const val PROGRESSIVE_FRAME_CHARS = 400
private const val STREAMING_PROGRESSIVE_FIRST_FRAME_CHARS = 480
private const val STREAMING_PROGRESSIVE_FRAME_CHARS = 900

/**
 * 从 [current] 开始按字符预算继续纳入顶层块；返回值不超过块数。
 * [mustAdvance] 为 true 时每次至少前进一块，保证单个超长块也能在有限帧内完成；
 * 为 false 时（点击那一帧）超预算的首块留到下一帧。
 */
internal fun nextProgressiveBlockLimit(
    blockLengths: List<Int>,
    current: Int,
    charBudget: Int,
    mustAdvance: Boolean = true,
): Int {
    var limit = current.coerceIn(0, blockLengths.size)
    var used = 0
    while (limit < blockLengths.size) {
        val length = blockLengths[limit]
        if (used + length > charBudget && (used > 0 || !mustAdvance)) break
        used += length
        limit++
    }
    return limit
}

internal fun streamingMarkdownBlockVisible(
    coordinatorActive: Boolean,
    firstRevealKey: RevealBlockKey?,
    startedRevealKeys: Set<RevealBlockKey>,
    nextRevealKey: RevealBlockKey?,
): Boolean {
    if (!coordinatorActive) return true
    if (firstRevealKey == null) return true
    if (firstRevealKey in startedRevealKeys) return true
    return firstRevealKey == nextRevealKey
}

internal fun topLevelMarkdownBlocks(root: ASTNode): List<ASTNode> =
    root.children.filterNot { node -> node.type == MarkdownTokenTypes.EOL }

internal fun markdownBlockSpacing(previous: IElementType?, current: IElementType): TextUnit {
    if (previous == null) return 0.sp
    if (previous.isMarkdownHeading() && current.isMarkdownHeading()) return 12.sp
    if (current.isMarkdownHeading()) {
        return if (current == MarkdownElementTypes.ATX_1 ||
            current == MarkdownElementTypes.SETEXT_1 ||
            current == MarkdownElementTypes.ATX_2 ||
            current == MarkdownElementTypes.SETEXT_2
        ) {
            24.sp
        } else {
            20.sp
        }
    }
    if (previous.isMarkdownHeading()) return 10.sp
    if (previous.isMarkdownParagraph() && current.isMarkdownParagraph()) return 16.sp
    if (previous.isMarkdownStructuredBlock() || current.isMarkdownStructuredBlock()) return 16.sp
    return 14.sp
}

private fun IElementType.isMarkdownHeading(): Boolean = when (this) {
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> true

    else -> false
}

private fun IElementType.isMarkdownParagraph(): Boolean =
    this == MarkdownElementTypes.PARAGRAPH || this == MarkdownTokenTypes.TEXT

private fun IElementType.isMarkdownStructuredBlock(): Boolean = when (this) {
    MarkdownElementTypes.ORDERED_LIST,
    MarkdownElementTypes.UNORDERED_LIST,
    MarkdownElementTypes.BLOCK_QUOTE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.IMAGE,
    MarkdownTokenTypes.HORIZONTAL_RULE,
    TABLE,
    -> true

    else -> false
}

internal data class StreamingMarkdownTarget(
    val content: String,
    val isStreaming: Boolean,
    val renderSpec: PreparedMarkdownSpec? = null,
    val queuedAtNs: Long = System.nanoTime(),
    // Captured at enqueue; null stays unknown when no reliable run mapping exists.
    val diagnosticAttribution: StreamDiagnosticAttribution? = StreamPerformanceDiagnostics.captureAttribution(),
)

internal fun streamingMarkdownBatchSize(backlogChars: Int): Int = when {
    backlogChars >= 384 -> 96
    backlogChars >= 160 -> 64
    backlogChars >= 64 -> 40
    else -> 24
}

internal fun streamingMarkdownBatchEnd(
    content: String,
    start: Int,
    maxGraphemes: Int,
): Int {
    return AppendOnlyGraphemeIndex().apply { update(content) }.endAfter(start, maxGraphemes)
}

// ── Markdown 样式：克制的聊天排版，标题只作强调不作页面标题 ─────────────

private enum class ChatMarkdownTone {
    Answer,
    Thinking,
}

@Composable
private fun chatMarkdownTypography(tone: ChatMarkdownTone) = markdownTypography(
    h1 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 21.sp else 17.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 29.sp else 25.sp,
        fontWeight = FontWeight.Bold,
    ),
    h2 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 19.sp else 16.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 27.sp else 24.sp,
        fontWeight = FontWeight.Bold,
    ),
    h3 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 18.sp else 15.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 26.sp else 23.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h4 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 17.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 25.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h5 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 16.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 24.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h6 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 15.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 23.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    text = chatMarkdownBodyStyle(tone),
    paragraph = chatMarkdownBodyStyle(tone),
    ordered = chatMarkdownBodyStyle(tone),
    bullet = chatMarkdownBodyStyle(tone),
    list = chatMarkdownBodyStyle(tone),
    quote = MiuixTheme.textStyles.body2.copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 15.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 24.sp else 22.sp,
        color = chatMarkdownTextColor(ChatMarkdownTone.Thinking),
    ),
    code = TextStyle(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontFamily = FontFamily.Monospace,
        color = chatMarkdownTextColor(tone),
    ),
    inlineCode = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 14.sp else 13.sp,
        fontFamily = FontFamily.Monospace,
    ),
    table = MiuixTheme.textStyles.body2.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = chatMarkdownTextColor(tone),
    ),
    textLink = TextLinkStyles(
        style = SpanStyle(
            color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        ),
    ),
)

@Composable
private fun chatMarkdownBodyStyle(tone: ChatMarkdownTone) =
    if (tone == ChatMarkdownTone.Answer) {
        MiuixTheme.textStyles.body1.copy(
            fontSize = 16.sp,
            lineHeight = 26.sp,
            color = chatMarkdownTextColor(tone),
        )
    } else {
        MiuixTheme.textStyles.body2.copy(
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = chatMarkdownTextColor(tone),
        )
    }

@Composable
private fun chatMarkdownTextColor(tone: ChatMarkdownTone): Color =
    if (tone == ChatMarkdownTone.Answer) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

@Composable
private fun chatMarkdownColors(tone: ChatMarkdownTone) = markdownColor(
    text = chatMarkdownTextColor(tone),
    // 代码块与表格的底色、描边由自定义组件绘制，这里只保留行内代码底色与分隔线。
    codeBackground = MiuixTheme.colorScheme.surface,
    inlineCodeBackground = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    dividerColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
    tableBackground = Color.Transparent,
)

@Composable
private fun chatMarkdownDimens() = markdownDimens(
    dividerThickness = 0.5.dp,
    codeBackgroundCornerSize = 10.dp,
    blockQuoteThickness = 3.dp,
)

@Composable
private fun chatMarkdownPadding() = markdownPadding(
    // 顶层块由 ChatMarkdownDocument 按语义分配留白，库的统一前置间距保持关闭。
    block = 0.dp,
    list = 3.dp,
    listItemTop = 3.dp,
    listItemBottom = 3.dp,
    listIndent = 14.dp,
    codeBlock = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
    blockQuote = PaddingValues(horizontal = 12.dp),
    blockQuoteText = PaddingValues(vertical = 3.dp),
    blockQuoteBar = PaddingValues.Absolute(left = 2.dp, top = 3.dp, right = 0.dp, bottom = 3.dp),
)

private fun chatMarkdownComponents(
    revealCoordinator: SmoothTextRevealCoordinator? = null,
    suppressEmptyListMarkers: Boolean = false,
) = markdownComponents(
    text = { model ->
        if (revealCoordinator == null) {
            MarkdownText(
                content = model.node.getUnescapedTextInNode(model.content),
                node = model.node,
                style = model.typography.text,
            )
        } else {
            ChatRevealRawText(model, revealCoordinator)
        }
    },
    paragraph = { model ->
        if (model.node.containsMarkdownImage()) {
            ChatMarkdownImageParagraph(
                content = model.content,
                node = model.node,
                style = model.typography.paragraph,
            )
        } else {
            ChatRevealMarkdownText(
                model = model,
                style = model.typography.paragraph,
                revealCoordinator = revealCoordinator,
            )
        }
    },
    image = { model ->
        ChatMarkdownImage(
            content = model.content,
            node = model.node,
        )
    },
    inlineImage = { model ->
        ChatMarkdownImage(
            content = model.content,
            node = model.node,
            sourceOverride = model.content,
            fillPlaceholder = true,
        )
    },
    orderedList = { model ->
        ChatMarkdownList(
            model = model,
            ordered = true,
            revealCoordinator = revealCoordinator,
            suppressEmptyMarker = suppressEmptyListMarkers,
        )
    },
    unorderedList = { model ->
        ChatMarkdownList(
            model = model,
            ordered = false,
            revealCoordinator = revealCoordinator,
            suppressEmptyMarker = suppressEmptyListMarkers,
        )
    },
    heading1 = { ChatHeadingBlock(it, it.typography.h1, revealCoordinator = revealCoordinator) },
    heading2 = { ChatHeadingBlock(it, it.typography.h2, revealCoordinator = revealCoordinator) },
    heading3 = { ChatHeadingBlock(it, it.typography.h3, revealCoordinator = revealCoordinator) },
    heading4 = { ChatHeadingBlock(it, it.typography.h4, revealCoordinator = revealCoordinator) },
    heading5 = { ChatHeadingBlock(it, it.typography.h5, revealCoordinator = revealCoordinator) },
    heading6 = { ChatHeadingBlock(it, it.typography.h6, revealCoordinator = revealCoordinator) },
    setextHeading1 = {
        ChatHeadingBlock(
            it,
            it.typography.h1,
            setext = true,
            revealCoordinator = revealCoordinator,
        )
    },
    setextHeading2 = {
        ChatHeadingBlock(
            it,
            it.typography.h2,
            setext = true,
            revealCoordinator = revealCoordinator,
        )
    },
    codeFence = { model ->
        val revealState = if (revealCoordinator != null) {
            rememberSmoothTextRevealState(
                key = RevealBlockKey(model.node.startOffset),
                coordinator = revealCoordinator,
            )
        } else {
            null
        }
        val startedKeys = rememberStartedRevealKeys(revealCoordinator)
        MarkdownCodeFence(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(
                code = code,
                language = language,
                style = style,
                revealState = revealState,
                showChrome = revealCoordinator == null ||
                    RevealBlockKey(model.node.startOffset) in startedKeys,
            )
        }
    },
    codeBlock = { model ->
        val revealState = if (revealCoordinator != null) {
            rememberSmoothTextRevealState(
                key = RevealBlockKey(model.node.startOffset),
                coordinator = revealCoordinator,
            )
        } else {
            null
        }
        val startedKeys = rememberStartedRevealKeys(revealCoordinator)
        MarkdownCodeBlock(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(
                code = code,
                language = language,
                style = style,
                revealState = revealState,
                showChrome = revealCoordinator == null ||
                    RevealBlockKey(model.node.startOffset) in startedKeys,
            )
        }
    },
    table = { model ->
        ChatMarkdownTable(
            content = model.content,
            node = model.node,
            style = model.typography.table,
            revealCoordinator = revealCoordinator,
        )
    },
    blockQuote = { model ->
        ChatBlockQuote(model)
    },
)

/**
 * 流式列表不能直接使用库的默认实现：默认实现会立即绘制 marker，而正文还在显现动画中。
 * 这里把每一项作为稳定的组合单元，并让 marker 与该项首个正文块共享开始时机。
 */
@Composable
private fun ChatMarkdownList(
    model: MarkdownComponentModel,
    ordered: Boolean,
    revealCoordinator: SmoothTextRevealCoordinator?,
    suppressEmptyMarker: Boolean,
    depth: Int = model.listDepth,
) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    val items = remember(model.node) {
        model.node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    }
    if (items.isEmpty()) return

    val startedRevealKeys = rememberStartedRevealKeys(revealCoordinator)
    val initialListNumber = items.first()
        .getUnescapedTextInNode(model.content)
        .takeWhile(Char::isDigit)
        .toIntOrNull()
        ?: 1

    Column(
        modifier = Modifier.padding(
            start = padding.listIndent * depth,
            top = padding.list,
            bottom = padding.list,
        ),
    ) {
        items.forEachIndexed { index, item ->
            key(item.startOffset, item.type.name) {
                val firstRevealKey = remember(item) { item.firstRevealBlockKey() }
                val checkboxNode = remember(item) {
                    item.children.firstOrNull { child -> child.type == CHECK_BOX }
                }
                val markerVisible = streamingListMarkerVisible(
                    coordinatorActive = suppressEmptyMarker,
                    firstRevealKey = firstRevealKey,
                    startedRevealKeys = startedRevealKeys,
                    containsImage = item.containsMarkdownImage(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .streamingListItemLayout(visible = firstRevealKey == null || markerVisible)
                        .semantics { isTraversalGroup = true }
                        .padding(
                            top = padding.listItemTop,
                            bottom = padding.listItemBottom,
                        ),
                ) {
                    Box(
                        modifier = Modifier.graphicsLayer(
                            // 隐藏 marker 但保留它的测量宽度，避免正文横向跳动。
                            alpha = if (markerVisible) 1f else 0f,
                        ),
                    ) {
                        if (checkboxNode != null) {
                            components.checkbox(
                                MarkdownComponentModel(
                                    content = model.content,
                                    node = checkboxNode,
                                    typography = model.typography,
                                ),
                            )
                        } else if (ordered) {
                            Text(
                                text = "${initialListNumber + index}.",
                                style = model.typography.ordered.copy(
                                    color = MiuixTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        } else {
                            // Compose 单行 Text 在默认 Trim.Both 下忽略 lineHeight，行框即字体自然行高；
                            // marker 必须与正文同 fontSize/lineHeight 才能共享度规对齐，
                            // 层级差异只通过字形与颜色表达。
                            val bulletDepth = depth % 3
                            Text(
                                text = when (bulletDepth) {
                                    0 -> "•"
                                    1 -> "◦"
                                    else -> "▪"
                                },
                                style = model.typography.bullet.copy(
                                    color = if (bulletDepth == 2) {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    } else {
                                        MiuixTheme.colorScheme.primary
                                    },
                                ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column {
                        item.children.forEach { child ->
                            when (child.type) {
                                MarkdownElementTypes.ORDERED_LIST -> {
                                    ChatMarkdownList(
                                        model = MarkdownComponentModel(
                                            content = model.content,
                                            node = child,
                                            typography = model.typography,
                                        ),
                                        ordered = true,
                                        revealCoordinator = revealCoordinator,
                                        suppressEmptyMarker = suppressEmptyMarker,
                                        depth = depth + 1,
                                    )
                                }

                                MarkdownElementTypes.UNORDERED_LIST -> {
                                    ChatMarkdownList(
                                        model = MarkdownComponentModel(
                                            content = model.content,
                                            node = child,
                                            typography = model.typography,
                                        ),
                                        ordered = false,
                                        revealCoordinator = revealCoordinator,
                                        suppressEmptyMarker = suppressEmptyMarker,
                                        depth = depth + 1,
                                    )
                                }

                                else -> MarkdownElement(
                                    node = child,
                                    components = components,
                                    content = model.content,
                                    includeSpacer = false,
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
private fun rememberStartedRevealKeys(
    coordinator: SmoothTextRevealCoordinator?,
): Set<RevealBlockKey> = if (coordinator == null) {
    emptySet()
} else {
    coordinator.started.collectAsState().value
}

internal fun streamingListMarkerVisible(
    coordinatorActive: Boolean,
    firstRevealKey: RevealBlockKey?,
    startedRevealKeys: Set<RevealBlockKey>,
    containsImage: Boolean,
): Boolean = !coordinatorActive ||
    firstRevealKey?.let(startedRevealKeys::contains) == true ||
    (firstRevealKey == null && containsImage)

@Composable
private fun ChatRevealRawText(
    model: MarkdownComponentModel,
    revealCoordinator: SmoothTextRevealCoordinator,
) {
    val prepared = LocalPreparedMarkdownBlock.current?.text(model.node, null)
    val text = prepared ?: remember(markdownRenderCacheKey(model.content, model.node)) {
        StreamPerformanceDiagnostics.measure("markdown.annotated.raw", (model.node.endOffset - model.node.startOffset).toLong()) {
            AnnotatedString(model.node.getUnescapedTextInNode(model.content))
        }
    }
    ChatRevealAnnotatedText(
        text = text,
        node = model.node,
        sourceContent = model.content,
        style = model.typography.text,
        revealCoordinator = revealCoordinator,
    )
}

@Composable
private fun ChatRevealMarkdownText(
    model: MarkdownComponentModel,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator?,
    modifier: Modifier = Modifier,
    contentChildType: IElementType? = null,
) {
    val annotatorSettings = annotatorSettings()
    val contentNode = remember(model.node, contentChildType) {
        contentChildType?.let(model.node::findChildOfType) ?: model.node
    }
    val prepared = LocalPreparedMarkdownBlock.current?.text(contentNode, style.toSpanStyle())
    val text = prepared ?: remember(markdownRenderCacheKey(model.content, contentNode), style, annotatorSettings) {
        StreamPerformanceDiagnostics.measure("markdown.annotated.build", (contentNode.endOffset - contentNode.startOffset).toLong()) {
            buildAnnotatedString {
                pushStyle(style.toSpanStyle())
                buildMarkdownAnnotatedString(
                    content = model.content,
                    node = contentNode,
                    annotatorSettings = annotatorSettings,
                )
                pop()
            }
        }
    }
    if (revealCoordinator == null) {
        ChatSelectableText(text = text, style = style, modifier = modifier)
        return
    }
    ChatRevealAnnotatedText(
        text = text,
        node = model.node,
        sourceContent = model.content,
        style = style,
        revealCoordinator = revealCoordinator,
        modifier = modifier,
    )
}

@Composable
private fun ChatRevealAnnotatedText(
    text: AnnotatedString,
    node: ASTNode,
    sourceContent: String,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator,
    modifier: Modifier = Modifier,
) {
    val revealState = rememberSmoothTextRevealState(
        key = RevealBlockKey(node.startOffset),
        coordinator = revealCoordinator,
    )
    ChatSelectableText(
        text = text,
        modifier = modifier.smoothTextReveal(revealState),
        style = style.copy(textMotion = TextMotion.Animated),
        onTextLayout = { layoutResult ->
            revealState.onTextLayout(text.text, layoutResult)
        },
    )
}

/**
 * 标题自身只负责文字样式；与相邻块的距离由文档级排版统一决定。
 */
@Composable
private fun ChatHeadingBlock(
    model: MarkdownComponentModel,
    style: TextStyle,
    setext: Boolean = false,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val contentChildType = if (setext) {
        MarkdownTokenTypes.SETEXT_CONTENT
    } else {
        MarkdownTokenTypes.ATX_CONTENT
    }
    if (model.node.containsMarkdownImage()) {
        ChatMarkdownImageParagraph(
            content = model.content,
            node = model.node.findChildOfType(contentChildType) ?: model.node,
            style = style,
            modifier = Modifier.semantics { heading() },
        )
    } else {
        ChatRevealMarkdownText(
            model = model,
            style = style,
            revealCoordinator = revealCoordinator,
            contentChildType = contentChildType,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * 代码块：顶栏显示语言标签并提供一键复制，正文等宽字体、超出横向滚动。
 */
@Composable
private fun ChatCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
    revealState: SmoothTextRevealState? = null,
    showChrome: Boolean = true,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (showChrome) {
                    Modifier
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surface)
                        .border(
                            0.5.dp,
                            MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                            RoundedCornerShape(10.dp),
                        )
                } else {
                    Modifier
                }
            ),
    ) {
        if (showChrome) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 13.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.takeIf { it.isNotBlank() } ?: "code",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check
                        else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_code,
                    ),
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        }
        val codeModifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .then(
                if (showChrome) Modifier.padding(horizontal = 13.dp, vertical = 11.dp)
                else Modifier,
            )
            .let { base ->
                if (revealState != null) base.smoothTextReveal(revealState) else base
            }
        val codeText: @Composable () -> Unit = {
            Text(
                text = code,
                style = if (revealState != null) {
                    style.copy(textMotion = TextMotion.Animated)
                } else {
                    style
                },
                color = MiuixTheme.colorScheme.onSurface,
                modifier = codeModifier,
                onTextLayout = revealState?.let { state ->
                    { layoutResult -> state.onTextLayout(code, layoutResult) }
                },
            )
        }
        // Selection registers the whole code block on every layout. A growing
        // fence does that on each publish, so it stays a plain Text until done.
        if (revealState == null) HapticSelectionContainer { codeText() } else codeText()
    }
}

private val ChatTableCellWidth = 112.dp

/**
 * 表格：细描边容器 + 表头浅底加粗 + 行间发丝分隔线；列宽不足时整体横向滚动。
 */
@Composable
private fun ChatMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val headerCells = remember(node) {
        node.findChildOfType(HEADER)?.children?.filter { it.type == CELL }.orEmpty()
    }
    val bodyRows = remember(node) {
        node.children.filter { it.type == ROW }
            .map { row -> row.children.filter { it.type == CELL } }
    }
    if (headerCells.isEmpty()) return

    val borderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        val tableWidth = ChatTableCellWidth * headerCells.size
        val scrollable = maxWidth <= tableWidth
        Column(
            modifier = (if (scrollable) {
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .requiredWidth(tableWidth)
            } else {
                Modifier.fillMaxWidth()
            })
                .clip(RoundedCornerShape(10.dp))
                .border(0.5.dp, borderColor, RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
                    .height(IntrinsicSize.Max),
            ) {
                headerCells.forEach { cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        ChatMarkdownTableCell(
                            content = content,
                            cell = cell,
                            style = style.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            revealCoordinator = revealCoordinator,
                        )
                    }
                }
            }
            val tableTail = if (revealCoordinator == null) null else bodyRows.asReversed().firstOrNull { row ->
                row.any { cell -> !cell.containsMarkdownImage() }
            }
            bodyRows.forEach { rowCells ->
                val rowSettled = revealCoordinator == null || rowCells !== tableTail
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(borderColor.copy(alpha = 0.6f)),
                )
                Row(modifier = completedContentDrawLayer(Modifier.fillMaxWidth(), rowSettled)) {
                    rowCells.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            ChatMarkdownTableCell(
                                content = content,
                                cell = cell,
                                style = style,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                revealCoordinator = revealCoordinator,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMarkdownTableCell(
    content: String,
    cell: ASTNode,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    revealCoordinator: SmoothTextRevealCoordinator?,
) {
    if (revealCoordinator == null || cell.containsMarkdownImage()) {
        MarkdownTableBasicText(
            content = content,
            cell = cell,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }

    val annotatorSettings = annotatorSettings()
    val prepared = LocalPreparedMarkdownBlock.current?.text(cell, style.toSpanStyle())
    val text = prepared ?: remember(markdownRenderCacheKey(content, cell), style, annotatorSettings) {
        StreamPerformanceDiagnostics.measure("markdown.annotated.cell", (cell.endOffset - cell.startOffset).toLong()) {
            buildAnnotatedString {
                pushStyle(style.toSpanStyle())
                buildMarkdownAnnotatedString(
                    content = content,
                    node = cell,
                    annotatorSettings = annotatorSettings,
                )
                pop()
            }
        }
    }
    val revealState = rememberSmoothTextRevealState(
        key = RevealBlockKey(cell.startOffset),
        coordinator = revealCoordinator,
    )
    Text(
        text = text,
        style = style.copy(textMotion = TextMotion.Animated),
        color = MiuixTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = overflow,
        modifier = Modifier.smoothTextReveal(revealState),
        onTextLayout = { layoutResult ->
            revealState.onTextLayout(text.text, layoutResult)
        },
    )
}

/**
 * 引用块：圆角浅色竖条 + 弱化文字。
 * 库默认实现把竖条颜色绑死在 quote 文字颜色上，无法分别控制，因此竖条自绘；
 * 子节点仍交给 ambient components，流式显现与嵌套引用行为不变。
 */
@Composable
private fun ChatBlockQuote(model: MarkdownComponentModel) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    val dimens = LocalMarkdownDimens.current
    val a11yLabels = LocalMarkdownA11yLabels.current
    val barColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.4f)
    val emptyLineHeight = with(LocalDensity.current) {
        model.typography.quote.lineHeight.takeOrElse { 22.sp }.toDp()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yLabels.blockquote }
            .drawBehind {
                val thickness = dimens.blockQuoteThickness.toPx()
                val x = padding.blockQuoteBar
                    .calculateStartPadding(LayoutDirection.Ltr).toPx() + thickness / 2
                drawLine(
                    color = barColor,
                    strokeWidth = thickness,
                    start = Offset(x, padding.blockQuoteBar.calculateTopPadding().toPx()),
                    end = Offset(
                        x,
                        size.height - padding.blockQuoteBar.calculateBottomPadding().toPx(),
                    ),
                    cap = StrokeCap.Round,
                )
            }
            .padding(padding.blockQuote),
    ) {
        model.node.children.forEach { child ->
            key(child.startOffset) {
                when (child.type) {
                    MarkdownElementTypes.BLOCK_QUOTE -> ChatBlockQuote(
                        MarkdownComponentModel(
                            content = model.content,
                            node = child,
                            typography = model.typography,
                        ),
                    )

                    MarkdownTokenTypes.EOL -> Spacer(Modifier.height(emptyLineHeight))

                    else -> MarkdownElement(
                        node = child,
                        components = components,
                        content = model.content,
                        includeSpacer = false,
                    )
                }
            }
        }
    }
}

private fun ASTNode.containsMarkdownImage(): Boolean =
    type == MarkdownElementTypes.IMAGE || children.any { child -> child.containsMarkdownImage() }

/** 找到列表项中首个会被显现协调器管理的块，marker 以它作为显示时机。 */
private fun ASTNode.firstRevealBlockKey(): RevealBlockKey? = when (type) {
    MarkdownTokenTypes.TEXT -> RevealBlockKey(startOffset)

    MarkdownElementTypes.PARAGRAPH,
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> if (!containsMarkdownImage()) RevealBlockKey(startOffset) else null

    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    -> RevealBlockKey(startOffset)

    TABLE -> children.asSequence()
        .flatMap { it.depthFirstSequence() }
        .firstOrNull { it.type == CELL && !it.containsMarkdownImage() }
        ?.let { RevealBlockKey(it.startOffset) }

    MarkdownElementTypes.IMAGE,
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.HORIZONTAL_RULE,
    -> null

    else -> children.asSequence().mapNotNull(ASTNode::firstRevealBlockKey).firstOrNull()
}

private fun ASTNode.depthFirstSequence(): Sequence<ASTNode> = sequence {
    yield(this@depthFirstSequence)
    children.forEach { child -> yieldAll(child.depthFirstSequence()) }
}

private fun State.Success.revealBlockKeys(): Set<RevealBlockKey> = buildSet {
    node.children.forEach { child -> collectRevealBlockKeys(child) }
}

private fun MutableSet<RevealBlockKey>.collectRevealBlockKeys(node: ASTNode) {
    when (node.type) {
        MarkdownTokenTypes.TEXT -> add(RevealBlockKey(node.startOffset))

        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
        -> if (!node.containsMarkdownImage()) add(RevealBlockKey(node.startOffset))

        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        -> add(RevealBlockKey(node.startOffset))

        TABLE -> collectTableCellRevealKeys(node)

        MarkdownElementTypes.IMAGE,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.HORIZONTAL_RULE,
        -> Unit

        else -> node.children.forEach { child -> collectRevealBlockKeys(child) }
    }
}

private fun MutableSet<RevealBlockKey>.collectTableCellRevealKeys(node: ASTNode) {
    if (node.type == CELL) {
        if (!node.containsMarkdownImage()) add(RevealBlockKey(node.startOffset))
        return
    }
    node.children.forEach { child -> collectTableCellRevealKeys(child) }
}

// ── 思考过程 ─────────────────────────────────────────────────────────

@Composable
private fun ThinkingRow(
    message: ThinkingMessageUi,
    retainedStreamingState: StreamingMarkdownState?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    isPaused: Boolean = false,
    onToggle: ((String, Boolean) -> Unit)? = null,
) {
    val bodyTraceMount = remember { nextChatBodyTraceMount() }
    SideEffect { traceChatBodyRun("thinking", bodyTraceMount) }
    val expansionHoldsBottom = LocalExpansionHoldsBottom.current
    // 这一次展开或收起朝哪边长；点击时定，动画期间不变。
    var anchorBottom by remember(message.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(message.id) { mutableStateOf(!message.collapsed) }
    var manuallyExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    // 仅本次组合内由点击触发的展开才分帧组合正文；不跨配置变更保存。
    var expandedByTap by remember(message.id) { mutableStateOf(false) }
    val toggleProbeRef = remember(message.id) { ToggleProbeRef() }
    // 思考结束后立即切换为与完成态回答相同的稳定 Markdown。工具执行期间 App 可能
    // 处于后台，不能让旧思考保留显现债务，回来后在新回答旁边补播整段内容。
    val streamingState = if (message.isStreaming) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    LaunchedEffect(message.isStreaming) {
        if (manuallyExpanded) return@LaunchedEffect
        // 输出结束时的自动收起沿用原来的上沿方向。
        anchorBottom = false
        expanded = message.isStreaming
    }

    val pulseAlpha = rememberActivePulse(
        active = message.isStreaming && !isPaused,
        label = "thinking_pulse",
    )

    // compact 模式渲染在工作过程卡片内部，不再携带自己的卡片外壳，避免卡中卡。
    val containerModifier = if (compact) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 14.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.50f),
                cornerRadius = 14.dp,
            )
    }

    // 跟工具行一样，整块都能点：点标题展开，展开后点正文也能收起。
    // 正文里的链接和长按选择先拿到手势，不会被这里吃掉。
    Column(
        modifier = containerModifier
            .clickable(interactionSource = null, indication = null) {
                onToggle?.invoke(message.id, !expanded)
                anchorBottom = expansionHoldsBottom()
                manuallyExpanded = true
                expandedByTap = !expanded
                expanded = !expanded
                toggleProbeRef.token = StreamPerformanceDiagnostics.markToggle("thinking", expanded)
                StreamPerformanceDiagnostics.probeEvent(
                    toggleProbeRef.token,
                    "item",
                    "chars=${message.content.length} streaming=${message.isStreaming} " +
                        "anchor=${if (anchorBottom) "bottom" else "top"}",
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 4.dp else 13.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_atom),
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer { alpha = if (message.isStreaming && !isPaused) pulseAlpha() else 1f },
                tint = if (message.isStreaming && !isPaused) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (message.isStreaming) {
                    stringResource(R.string.reasoning_in_progress)
                } else {
                    message.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
                        pluralStringResource(
                            R.plurals.reasoning_completed_seconds,
                            seconds,
                            seconds,
                        )
                    } ?: stringResource(R.string.reasoning_completed)
                },
                style = MiuixTheme.textStyles.body2,
                color = if (message.isStreaming && !isPaused) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore
                    else Icons.Rounded.ChevronRight,
                contentDescription = stringResource(
                    if (expanded) R.string.reasoning_collapse else R.string.reasoning_expand,
                ),
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }

        AnimatedVisibility(
            visible = expanded && message.content.isNotBlank(),
            enter = tailDetailsEnter(anchorBottom),
            exit = tailDetailsExit(anchorBottom),
            modifier = Modifier.toggleProbe(toggleProbeRef, "visible"),
        ) {
            HapticSelectionContainer(
                modifier = completedContentDrawLayer(retainDrawLayerWhenIdle(), streamingState == null)
                    .toggleProbe(toggleProbeRef, "content"),
            ) {
                Column {
                    if (!compact) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 13.dp)
                                .height(0.5.dp)
                                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        )
                    }
                    val contentModifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = if (compact) 27.dp else 13.dp,
                            end = 13.dp,
                            top = if (compact) 2.dp else 8.dp,
                            bottom = if (compact) 8.dp else 12.dp,
                        )
                    if (streamingState != null) {
                        StreamingMarkdown(
                            state = streamingState,
                            content = message.content,
                            isStreaming = message.isStreaming,
                            isPaused = isPaused,
                            onRevealCompleteChange = {},
                            tone = ChatMarkdownTone.Thinking,
                            modifier = contentModifier,
                        )
                    } else {
                        androidx.compose.runtime.CompositionLocalProvider(LocalToggleProbe provides toggleProbeRef) {
                            StableMarkdown(
                                content = message.content,
                                tone = ChatMarkdownTone.Thinking,
                                modifier = contentModifier,
                                progressive = expandedByTap,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 工具调用：优雅极简时间线 ─────────────────────────────────────────


/**
 * 展开和收起的时长、缓动不变，只换生长方向：下沿被钉住时从下沿长出，
 * 让已经排好的内容停在原处、由标签往上让开；否则从上沿往下长。
 */
internal fun tailDetailsEnter(fromBottom: Boolean): androidx.compose.animation.EnterTransition =
    fadeIn(tween(160)) + expandVertically(
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        expandFrom = if (fromBottom) Alignment.Bottom else Alignment.Top,
    )


/**
 * 展开或收起还在进行时，内容尺寸每一帧都在变，绘制命令必须重录。
 * 停在展开之后，把内容画进一张与屏幕同分辨率的离屏纹理。
 * 之后滑动只移动这张纹理，渲染线程不再重放正文的文字命令。
 * 文字、选择和流式更新都不变；内容变化时纹理会重画。
 * 高于 [MAX_RETAINED_LAYER_HEIGHT_PX] 的内容不缓存，避免纹理被裁切。
 */
private const val MAX_RETAINED_LAYER_HEIGHT_PX = 8192

@Composable
internal fun AnimatedVisibilityScope.retainDrawLayerWhenIdle(): Modifier {
    val settled = transition.currentState == EnterExitState.Visible &&
        transition.targetState == EnterExitState.Visible
    // 带代码块的 graphicsLayer 每次重组都是新实例，Compose 会作废纹理并重录。
    // 工具行运行时整行都在重组，离屏纹理因此每帧失效。这里用稳定参数，内容不变就不重录。
    // 过高的内容超过纹理上限就会被裁切，宁可不缓存。
    var heightPx by remember { mutableIntStateOf(0) }
    val cache = settled && heightPx in 1..MAX_RETAINED_LAYER_HEIGHT_PX
    return Modifier
        .onSizeChanged { heightPx = it.height }
        // 保留同一个绘制层节点，只切换合成策略；展开终态或高度越界时不插拔正文绘制层。
        .graphicsLayer(
            compositingStrategy = if (cache) {
                CompositingStrategy.Offscreen
            } else {
                CompositingStrategy.Auto
            },
        )
}

internal fun tailDetailsExit(toBottom: Boolean): androidx.compose.animation.ExitTransition =
    shrinkVertically(
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        shrinkTowards = if (toBottom) Alignment.Bottom else Alignment.Top,
    ) + fadeOut(tween(100))

@Composable
private fun ToolActivityInline(
    message: ToolActivityMessageUi,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    enableLivePreview: Boolean = true,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val bodyTraceMount = remember { nextChatBodyTraceMount() }
    SideEffect { traceChatBodyRun("tool", bodyTraceMount) }
    val expansionHoldsBottom = LocalExpansionHoldsBottom.current
    var anchorBottom by remember(message.id) { mutableStateOf(false) }
    var isExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    // 仅本次组合内由点击触发的展开才把命令与结果推迟一帧；不跨配置变更保存。
    var expandedByTap by remember(message.id) { mutableStateOf(false) }
    val toggleProbeRef = remember(message.id) { ToggleProbeRef() }
    // 只有「当前浏览器」卡片订阅实时会话快照，避免每个工具行都跟随快照重组
    val browserSnapshot = if (showBrowserShortcut) {
        AgentBrowserSession.snapshots.collectAsState().value
    } else {
        null
    }

    val pulseAlpha = rememberActivePulse(
        active = message.status == ToolActivityStatusUi.Running,
        label = "tool_pulse",
    )

    val title = message.argumentsSummary.ifBlank { toolDisplayName(message.toolName) }
    val showCompletedPlaceholder = message.argumentsSummary.isBlank() &&
        message.command.isNullOrBlank() &&
        message.resultSummary.isNullOrBlank() &&
        !showBrowserShortcut &&
        message.status == ToolActivityStatusUi.Success
    val hasDetails = !message.command.isNullOrBlank() ||
        !message.resultSummary.isNullOrBlank() ||
        showBrowserShortcut ||
        showCompletedPlaceholder
    val browserSubtitle = browserSnapshot?.let { snapshot ->
        when {
            snapshot.isLoading ->
                stringResource(R.string.tool_browser_loading, snapshot.progress)
            snapshot.host.isNotBlank() && snapshot.title.isNotBlank() ->
                "${snapshot.host} · ${snapshot.title}"
            snapshot.host.isNotBlank() -> snapshot.host
            else -> null
        }
    }
    // 失败原因直接显示在折叠行，不必展开卡片；剥离去重「失败」前缀与日志用的 code= 尾巴
    val failureSubtitle = if (message.status == ToolActivityStatusUi.Failed) {
        message.resultSummary
            ?.lineSequence()?.firstOrNull()
            ?.removePrefix("失败 · ")
            ?.substringBefore(" · code=")
            ?.takeIf { it.isNotBlank() && it != "失败" }
    } else {
        null
    }

    val toolRowSettled = message.status != ToolActivityStatusUi.Running
    Column(
        modifier = modifier
            
            .let { completedContentDrawLayer(it, toolRowSettled) }
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (hasDetails) {
                    Modifier.clickable(interactionSource = null, indication = null) {
                        anchorBottom = expansionHoldsBottom()
                        expandedByTap = !isExpanded
                        isExpanded = !isExpanded
                        toggleProbeRef.token = StreamPerformanceDiagnostics.markToggle("tool", isExpanded)
                        StreamPerformanceDiagnostics.probeEvent(
                            toggleProbeRef.token,
                            "item",
                            "status=${message.status} commandChars=${message.command?.length ?: 0} " +
                                "resultChars=${message.resultSummary?.length ?: 0} " +
                                "browser=$showBrowserShortcut anchor=${if (anchorBottom) "bottom" else "top"}",
                        )
                    }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 5.dp),
        ) {
            // 工具图标与思考行的灯泡共用同一前导槽位，保证卡片内左边缘对齐。
            Icon(
                imageVector = iconForTool(message.toolName),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = when (message.status) {
                    ToolActivityStatusUi.Running -> MiuixTheme.colorScheme.primary
                    ToolActivityStatusUi.Failed -> StatusError
                    ToolActivityStatusUi.Unknown -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    ToolActivityStatusUi.Success ->
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                }
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    color = if (message.status == ToolActivityStatusUi.Running) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = failureSubtitle ?: browserSubtitle
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (failureSubtitle != null) {
                            StatusError
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                AnimatedContent(
                    targetState = message.status,
                    transitionSpec = {
                        (fadeIn(tween(150)) + scaleIn(tween(170), initialScale = 0.86f))
                            .togetherWith(
                                fadeOut(tween(90)) + scaleOut(tween(110), targetScale = 0.86f)
                            )
                    },
                    label = "tool_status",
                ) { status ->
                    // 成功是常态，只留低饱和度对勾；运行中与失败才占用视觉注意力
                    if (status == ToolActivityStatusUi.Success) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.tool_status_success),
                            modifier = Modifier.size(13.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.graphicsLayer {
                                alpha = if (status == ToolActivityStatusUi.Running) pulseAlpha() else 1f
                            },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(status.statusColor())
                            )
                            Text(
                                text = status.statusLabel(),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
                if (hasDetails) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.ExpandMore
                            else Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isExpanded && hasDetails,
            enter = tailDetailsEnter(anchorBottom),
            exit = tailDetailsExit(anchorBottom),
            modifier = Modifier.toggleProbe(toggleProbeRef, "visible"),
        ) {
            Column(
                modifier = retainDrawLayerWhenIdle()
                    .toggleProbe(toggleProbeRef, "content")
                    .fillMaxWidth()
                    .padding(start = 27.dp, top = 2.dp, bottom = 6.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        cornerRadius = 10.dp,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // 点击那一帧只长出卡片外壳，命令与结果的组合和文字测量放到下一帧。
                val bodyReady = rememberDeferredBody(deferOneFrame = expandedByTap)
                if (!bodyReady) return@Column
                if (!message.command.isNullOrBlank()) {
                    ToolCommandBlock(
                        command = message.command,
                        context = message.argumentsSummary,
                        modifier = Modifier.padding(
                            bottom = if (message.resultSummary.isNullOrBlank()) 0.dp else 10.dp,
                        ),
                    )
                }
                if (message.resultSummary != null && message.resultSummary.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.ui_result_0a2c91),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    HapticSelectionContainer {
                        Text(
                            text = message.resultSummary,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (showCompletedPlaceholder) {
                    Text(
                        text = stringResource(R.string.tool_status_success),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (showBrowserShortcut) {
                    browserSnapshot?.takeIf { it.available }?.let { snapshot ->
                        BrowserPagePreview(
                            snapshot = snapshot,
                            enableLivePreview = enableLivePreview,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = stringResource(R.string.ui_open_current_browser_58358e),
                            onClick = onOpenBrowser,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            minHeight = 36.dp,
                            textStyle = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 浏览器工具的实时页面预览：迷你地址条 + 当前视口截图。
 *
 * 截图只在页面加载中或内容稳定后的低频节拍刷新；组合销毁即停止，
 * 不做后台轮询。截图不可用时退化为图标占位。
 */
@Composable
private fun BrowserPagePreview(
    snapshot: BrowserSessionSnapshot,
    modifier: Modifier = Modifier,
    enableLivePreview: Boolean = true,
) {
    var preview by remember(snapshot.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(snapshot.url, snapshot.isLoading, snapshot.isUserControlling, enableLivePreview) {
        if (snapshot.isUserControlling || !enableLivePreview) return@LaunchedEffect
        while (true) {
            val image = withContext(Dispatchers.IO) {
                AgentBrowserSession.capturePreview()?.let { decodeDataUrlBitmap(it.dataUrl) }
            }
            if (image != null) preview = image
            delay(if (snapshot.isLoading) 1_200L else 4_000L)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainer,
                cornerRadius = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (snapshot.isLoading) StatusRunning else StatusSuccess),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = snapshot.host.ifBlank { snapshot.displayUrl },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val image = preview
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.tool_browser_preview),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.outline,
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (snapshot.title.isNotBlank()) {
                Text(
                    text = snapshot.title,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snapshot.displayUrl.isNotBlank()) {
                Text(
                    text = snapshot.displayUrl,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ToolCommandBlock(
    command: String,
    context: String,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(command) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 10.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 10.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.ifBlank { stringResource(R.string.shell_command) },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(command))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check
                        else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_command,
                    ),
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
        )
        HapticSelectionContainer {
            Text(
                text = command,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

// ── Run trace：轻量入口行 ─────────────────────────────────────────────

@Composable
private fun RunTraceRow(
    message: RunTraceMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ui_available_capacity_743337),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
        )
    }
}

// ── 工具摘要 ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolSummaryInline(
    message: ToolSummaryMessageUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.tools.forEach { tool ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = iconForTool(tool),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = toolDisplayName(tool),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

// ── 上下文压缩分界 ─────────────────────────────────────────────────────

@Composable
private fun ContextCompactedDivider(
    message: ContextCompactedMessageUi,
    modifier: Modifier = Modifier,
) {
    // Zero-count maintenance markers keep token accounting, but have no user-facing notice.
    // This also hides pruning notices saved by older versions.
    if (message.compactedCount <= 0) return
    val view = LocalView.current
    var showSummary by remember { mutableStateOf(false) }
    val lineColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f)
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val summaryAction = stringResource(R.string.context_compacted_summary_action)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(lineColor),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    TouchHaptics.click(view)
                    showSummary = true
                }
                .semantics {
                    contentDescription = summaryAction
                }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = labelColor,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.context_compacted_messages,
                    message.compactedCount,
                    message.compactedCount,
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(lineColor),
        )
    }
    if (showSummary) {
        ContextCompactedSummarySheet(
            summary = message.summary,
            compressorLabel = message.compressorLabel,
            onDismiss = { showSummary = false },
        )
    }
}

// ── 建议语 ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChipsRow(
    message: SuggestionChipsMessageUi,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.prompts.forEach { prompt ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .border(
                        0.5.dp,
                        MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSuggestionClick(prompt) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = prompt,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ── 辅助 ──────────────────────────────────────────────────────────────

@Composable
private fun ToolActivityStatusUi.statusColor() = when (this) {
    ToolActivityStatusUi.Running -> StatusRunning
    ToolActivityStatusUi.Success -> StatusSuccess
    ToolActivityStatusUi.Failed -> StatusError
    ToolActivityStatusUi.Unknown -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
private fun ToolActivityStatusUi.statusLabel(): String = when (this) {
    ToolActivityStatusUi.Running -> stringResource(R.string.tool_status_running)
    ToolActivityStatusUi.Success -> stringResource(R.string.tool_status_success)
    ToolActivityStatusUi.Failed -> stringResource(R.string.tool_status_failed)
    ToolActivityStatusUi.Unknown -> stringResource(R.string.tool_status_unknown)
}
