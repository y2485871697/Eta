package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.mangi.eta.ui.components.KeepActiveStreamingRow
import io.github.mangi.eta.ui.components.shouldKeepStreamingRow
import io.github.mangi.eta.ui.components.StreamingMarkdownRestoreState
import io.github.mangi.eta.ui.components.LocalChatRouteCovered
import io.github.mangi.eta.ui.components.LocalChatTransitionActive
import io.github.mangi.eta.ui.components.isStreamingMarkdownTargetComplete
import io.github.mangi.eta.ui.haptics.StreamingHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser

/** Upstream 9de3a3f document pipeline: every annotated leaf is prepared off-main. */
internal class DocumentGfmParserSession {
    private val parser = MarkdownParser(GFMFlavourDescriptor())
    private val builder = MarkdownDocumentBuilder()
    fun parse(source: String, isComplete: Boolean,
              style: MarkdownInlineStyle = MarkdownInlineStyle.Default): DocumentSnapshot {
        val clean = NumericCitationMarkup.strip(source)
        val rendered = StreamingGfmProjection.project(TexMathDelimiters.normalize(clean), isComplete)
        val root = parser.buildMarkdownTreeFromString(rendered)
        val links = if (isComplete) LinkMap.buildLinkMap(root, rendered) else null
        return DocumentSnapshot(clean, rendered, isComplete, builder.build(root, rendered, links, style))
    }
}

internal class DocumentSnapshot(val originalSource: String, val renderedSource: String,
    val isComplete: Boolean, val document: MarkdownDocument)

@Stable
internal class DocumentStreamingState {
    private val parser = DocumentGfmParserSession()
    private val targets = Channel<DocumentTarget>(Channel.CONFLATED)
    val revealCoordinator = SmoothTextRevealCoordinator().apply { pauseAnimationsAndCatchUp() }
    val restoreState = StreamingMarkdownRestoreState()
    var snapshot by mutableStateOf<DocumentSnapshot?>(null)
        private set
    var completedRevealSource by mutableStateOf<String?>(null)
        private set
    private var completedSnapshot: DocumentSnapshot? = null
    private var submittedTarget: DocumentTarget? = null
    fun completedSourceFor(content: String, streaming: Boolean, style: MarkdownInlineStyle): String? =
        completedRevealSource.takeIf {
            !streaming && submittedTarget == DocumentTarget(content, streaming, style) &&
                completedSnapshot != null && completedSnapshot === snapshot
        }
    fun markComplete(value: DocumentSnapshot): Boolean {
        if (snapshot !== value || !value.isComplete ||
            submittedTarget?.content != value.originalSource || submittedTarget?.streaming != false ||
            submittedTarget?.style != value.document.inlineStyle || laidOutSnapshot !== value) return false
        completedSnapshot = value
        completedRevealSource = value.originalSource
        return true
    }
    private fun invalidateCompletion() {
        completedSnapshot = null
        completedRevealSource = null
    }
    var composedSource by mutableStateOf<String?>(null)
        private set
    var laidOutSnapshot by mutableStateOf<DocumentSnapshot?>(null)
        private set
    fun submit(content: String, streaming: Boolean, style: MarkdownInlineStyle) {
        val target = DocumentTarget(content, streaming, style)
        if (submittedTarget != target) invalidateCompletion()
        submittedTarget = target
        targets.trySend(target)
    }
    fun acknowledgeLayout(value: DocumentSnapshot) {
        if (snapshot !== value) return
        if (laidOutSnapshot !== value) laidOutSnapshot = value
        if (composedSource != value.originalSource) composedSource = value.originalSource
    }
    suspend fun parseUpdates() {
        var target = targets.receive()
        while (true) {
            while (true) { target = targets.tryReceive().getOrNull() ?: break }
            val parsed = withContext(Dispatchers.Default) {
                parser.parse(target.content, !target.streaming, target.style)
            }
            val newer = targets.tryReceive().getOrNull()
            // Follow upstream: a newer append does not invalidate this usable prefix.
            if (newer == null || (newer.content.startsWith(parsed.originalSource) &&
                    (!parsed.isComplete || newer == target))) {
                invalidateCompletion()
                composedSource = null
                laidOutSnapshot = null
                snapshot = parsed
            }
            target = newer ?: targets.receive()
        }
    }
}
private data class DocumentTarget(val content: String, val streaming: Boolean, val style: MarkdownInlineStyle)

@Composable
internal fun DocumentStreamingMarkdown(
    state: DocumentStreamingState, content: String, isStreaming: Boolean,
    modifier: Modifier = Modifier, tone: MarkdownTone = MarkdownTone.Answer,
    animateInitialContent: Boolean = false, isPaused: Boolean = false,
    onRevealCompleteChange: (Boolean) -> Unit = {},
) {
    val targetContent = remember(content) { NumericCitationMarkup.strip(content) }
    val style = rememberMarkdownStyle(tone)
    val handler = LocalUriHandler.current
    // Preserve the existing answer AND thinking typewriter; only the document renderer changes.
    val reveal = state.revealCoordinator
    val snapshot = state.snapshot
    val parseAsStreaming = isStreaming || isPaused
    val currentContent by rememberUpdatedState(targetContent)
    val currentStreaming by rememberUpdatedState(parseAsStreaming)
    val currentPaused by rememberUpdatedState(isPaused)
    val callback by rememberUpdatedState(onRevealCompleteChange)
    val routeCovered = LocalChatRouteCovered.current
    val transitionActive = LocalChatTransitionActive.current
    val routeCoveredNow = rememberUpdatedState(routeCovered)
    val settledCover = routeCovered && !transitionActive
    val settledCoverNow by rememberUpdatedState(settledCover)
    val generation = state.restoreState.generation
    val view = LocalView.current
    KeepActiveStreamingRow(shouldKeepStreamingRow(targetContent, isStreaming, isPaused, state.completedSourceFor(targetContent, parseAsStreaming, style.inline)))
    LifecycleResumeEffect(state) {
        val animateExisting = animateInitialContent && !currentPaused && currentContent.isNotEmpty()
        val animate = state.restoreState.begin(currentContent,
            live = currentStreaming && !currentPaused, animateExisting = animateExisting)
        if (settledCoverNow) state.revealCoordinator.holdAnimations()
        else if (animate) state.revealCoordinator.resumeAnimationsWithoutCatchingUp()
        else state.revealCoordinator.restoreHistoryThrough(currentContent.length)
        onPauseOrDispose {
            if (routeCoveredNow.value) state.restoreState.holdCovered()
            else {
                state.restoreState.pause()
                state.revealCoordinator.pauseAnimationsAndCatchUp()
            }
        }
    }
    val animationsAllowed = !settledCover &&
        (state.restoreState.animationsAllowed(isPaused) || (routeCovered && transitionActive))
    LaunchedEffect(state, animationsAllowed, isPaused, settledCover) {
        if (animationsAllowed) state.revealCoordinator.resumeAnimationsWithoutCatchingUp()
        else if (settledCover) state.revealCoordinator.holdAnimations()
        else if (!isPaused) state.revealCoordinator.pauseAnimationsAndCatchUp()
    }
    LaunchedEffect(state, isPaused, targetContent) {
        if (isPaused) state.revealCoordinator.restoreHistoryThrough(targetContent.length)
    }
    LaunchedEffect(reveal, view) {
        if (reveal != null) {
            reveal.setOnRevealAdvanced { StreamingHaptics.onVisibleAdvance(view) }
            try { reveal.runFrameClock() } finally { reveal.setOnRevealAdvanced(null) }
        }
    }
    LaunchedEffect(state) { state.parseUpdates() }
    LaunchedEffect(targetContent, parseAsStreaming, style.inline) {
        state.submit(targetContent, parseAsStreaming, style.inline)
        if (parseAsStreaming) callback(false)
    }
    LaunchedEffect(targetContent, parseAsStreaming, style.inline, snapshot, reveal) {
        val target = snapshot
        if (!isStreamingMarkdownTargetComplete(targetContent, parseAsStreaming,
                target?.originalSource, target?.isComplete == true)) {
            callback(false)
            return@LaunchedEffect
        }
        // Exact publication, not just the same source: terminal link resolution may
        // introduce leaves. No release while any reveal-bearing leaf lacks layout.
        snapshotFlow { state.laidOutSnapshot }.first { it === target }
        do {
            withFrameNanos { }
            if (reveal != null && !reveal.drained.value) reveal.drained.filter { it }.first()
            withFrameNanos { }
        } while (reveal != null && (!reveal.drained.value ||
                !reveal.hasLayoutsFor(checkNotNull(target).document.revealKeys)))
        if (state.snapshot === target && isStreamingMarkdownTargetComplete(
                currentContent, currentStreaming, target?.originalSource, target?.isComplete == true)) {
            if (state.markComplete(checkNotNull(target))) callback(true)
        }
    }
    snapshot?.let { parsed ->
        if (reveal != null) SideEffect { reveal.retainBlocks(parsed.document.revealKeys) }
        CompositionLocalProvider(LocalUriHandler provides handler) {
        MarkdownContent(parsed.document, style, reveal = reveal,
            modifier = modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                state.acknowledgeLayout(parsed)
                if (state.restoreState.completeLayout(generation, parsed.originalSource, currentContent) &&
                    !settledCoverNow && state.restoreState.animationsAllowed(currentPaused)) {
                    state.revealCoordinator.resumeAnimationsAfterCatchUp()
                }
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            })
        }
    }
}

/** Static history uses the same upstream renderer and a bounded prepared-document cache. */
@Composable
internal fun DocumentStaticMarkdown(content: String, modifier: Modifier = Modifier,
                                    tone: MarkdownTone = MarkdownTone.Answer) {
    val style = rememberMarkdownStyle(tone)
    val handler = LocalUriHandler.current
    val document by produceState(DocumentCache.get(content, style.inline), content, style.inline) {
        value = DocumentCache.get(content, style.inline) ?: withContext(Dispatchers.Default) {
            DocumentGfmParserSession().parse(content, true, style.inline).document
        }.also { DocumentCache.put(content, style.inline, it) }
    }
    val traceMount = remember { io.github.mangi.eta.ui.components.nextChatBodyTraceMount() }
    SideEffect { io.github.mangi.eta.ui.components.traceChatBodyRun(
        if (document == null) "md.phase.loading" else "md.phase.success", traceMount) }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        if (document == null) top.yukonga.miuix.kmp.basic.Text(content, style = style.body, modifier = modifier)
        else MarkdownContent(checkNotNull(document), style, modifier)
    }
}
private object DocumentCache {
    private val cache = android.util.LruCache<Pair<String, MarkdownInlineStyle>, MarkdownDocument>(96)
    fun get(content: String, style: MarkdownInlineStyle): MarkdownDocument? = cache.get(content to style)
    fun put(content: String, style: MarkdownInlineStyle, document: MarkdownDocument) {
        cache.put(content to style, document)
    }
}
