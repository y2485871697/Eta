package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import io.github.mangi.eta.ui.markdown.StreamingGfmSnapshot
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Differential rendering: identical library host and legacy frozen boundary,
 * with only the candidate's production caller input helper added. This is not
 * a device jank test or a pixel screenshot test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrozenMarkdownRenderingTest {
    @get:Rule val compose = createComposeRule()

    private data class Frame(val snapshot: StreamingGfmSnapshot, val freeze: Boolean)
    private data class TextResult(
        val text: String,
        val spans: List<AnnotatedString.Range<androidx.compose.ui.text.SpanStyle>>,
        val paragraphs: List<AnnotatedString.Range<androidx.compose.ui.text.ParagraphStyle>>,
        val links: List<Triple<Int, Int, String>>,
    )

    @Composable
    private fun LegacyBoundary(node: ASTNode, content: String, components: MarkdownComponents, freeze: Boolean) {
        Box {
            if (freeze) {
                val frozenNode = remember { node }
                val frozenContent = remember { content }
                MarkdownElement(node = frozenNode, components = components, content = frozenContent, includeSpacer = false)
            } else {
                MarkdownElement(node = node, components = components, content = content, includeSpacer = false)
            }
        }
    }

    @Composable
    private fun Document(frame: Frame, candidate: Boolean, tag: String, width: androidx.compose.ui.unit.Dp) {
        val components = remember { markdownComponents() }
        val inputState = frame.snapshot.state
        val hostState = remember(inputState) {
            // Direct State.Success hosts bypass MarkdownState's definition lookup.
            // Initialize the CURRENT full source equally for both renderers, while
            // retaining the actual streaming AST/content and shared link handler.
            val initialized = parseMarkdown(
                content = inputState.content,
                lookupLinks = true,
                referenceLinkHandler = inputState.referenceLinkHandler,
            ) as State.Success
            inputState.copy(linksLookedUp = initialized.linksLookedUp)
        }
        Markdown(
            state = hostState,
            modifier = Modifier.width(width).testTag(tag),
            animations = markdownAnimations(animateTextSize = { this }),
            components = components,
            imageTransformer = if (candidate) rememberStreamingMarkdownImageTransformer(frame.snapshot) else NoOpImageTransformerImpl(),
            success = { state, components, modifier ->
                Column(modifier) {
                    val node = topLevelMarkdownBlocks(state.node).first()
                    key(node.startOffset, node.type.name) {
                        val renderNode = if (candidate) rememberFrozenMarkdownInput(node, frame.freeze) else node
                        val renderContent = if (candidate) rememberFrozenMarkdownInput(state.content, frame.freeze) else state.content
                        LegacyBoundary(renderNode, renderContent, components, frame.freeze)
                    }
                }
            },
        )
    }

    private fun texts(tag: String): List<TextResult> =
        compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text ->
                    TextResult(
                        text.text, text.spanStyles, text.paragraphStyles,
                        text.getLinkAnnotations(0, text.length).map { link ->
                            val value = when (val item = link.item) {
                                is LinkAnnotation.Url -> item.url
                                is LinkAnnotation.Clickable -> item.tag
                                else -> error("Unrecognized link annotation: $item")
                            }
                            Triple(link.start, link.end, value)
                        },
                    )
                }
            }

    private fun assertSameRendering(label: String) {
        val old = texts("legacy")
        val candidate = texts("candidate")
        assertTrue("real Markdown text must exist: $label", old.isNotEmpty())
        assertEquals("text, inline styles and links: $label", old, candidate)
        val a = compose.onNodeWithTag("legacy").getUnclippedBoundsInRoot()
        val b = compose.onNodeWithTag("candidate").getUnclippedBoundsInRoot()
        assertEquals("width $label", a.right.value - a.left.value, b.right.value - b.left.value, 0.01f)
        assertEquals("height $label", a.bottom.value - a.top.value, b.bottom.value - b.top.value, 0.01f)
    }

    @Test fun realRendererMatchesAcrossFreezeFinalAndTypographyUpdates() {
        val parser = StreamingGfmParserSession()
        fun frame(source: String, freeze: Boolean, complete: Boolean = true) =
            Frame(parser.parse(source, isComplete = complete), freeze)
        val frames = listOf(
            frame("First **bold** and *emphasis* with `code`.", false),
            frame("Second **bold** and [inline guide](https://example.test/inline).\n\nTail", false),
            frame("Freeze-entry **bold** and [reference guide][guide].\n\nTail\n\n[guide]: https://example.test/one", true),
            frame("Correction which must preserve the legacy frozen input.\n\nTail grows\n\n[guide]: https://example.test/two", true),
            frame("Unfrozen **current** and [inline guide](https://example.test/new).\n\nTail", false),
            frame("An unfinished *emphasis", false, complete = false),
            frame("An unfinished *emphasis", false, complete = true),
            frame("A completed paragraph with **styles**, `inline code`, and a [link](https://example.test/last).\n\nTail", true),
            frame("A [late reference][late].\n\nTail", false),
            frame("A [late reference][late].\n\nTail", true),
            frame("A [late reference][late].\n\nTail grows\n\n[late]: https://example.test/late", true),
            // Restore the original long geometry fixture after the late-reference case.
            frame("A completed paragraph with **styles**, `inline code`, and a [link](https://example.test/last).\n\nTail", false),
            frame("A completed paragraph with **styles**, `inline code`, and a [link](https://example.test/last).\n\nTail", true),
        )
        val current = mutableStateOf(frames.first())
        val width = mutableStateOf(320.dp)
        val scale = mutableStateOf(1f)
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Column {
                        key("legacy") { Document(current.value, false, "legacy", width.value) }
                        key("candidate") { Document(current.value, true, "candidate", width.value) }
                    }
                }
            }
        }
        frames.forEachIndexed { index, frame ->
            compose.runOnIdle { current.value = frame }
            compose.waitForIdle()
            assertSameRendering("frame $index")
            if (index in 8..10) {
                listOf("legacy", "candidate").forEach { tag ->
                    assertTrue("late reference fixture must be displayed: $tag", texts(tag).any { "late reference" in it.text })
                }
            }
            if (index == 2) {
                for (tag in listOf("legacy", "candidate")) {
                    assertTrue("reference annotation must actually exist at freeze entry: $tag",
                        texts(tag).any { text -> text.links.any { it.third == "https://example.test/one" } })
                }
            }
            if (index == 3) {
                for (tag in listOf("legacy", "candidate")) {
                    assertTrue("recognized reference uses the current definition: $tag",
                        texts(tag).any { text -> text.links.any { it.third == "https://example.test/two" } })
                }
            }
            if (index == 5 || index == 6) {
                assertEquals("tail must use the current projected source", frame.snapshot.renderedSource, frame.snapshot.state.content)
                assertTrue("final/streaming tail text must actually render",
                    texts("candidate").any { it.text.contains("An unfinished") })
                assertEquals(index == 6, frame.snapshot.isComplete)
            }
            if (index == 1 || index == 4) {
                val expectedUrl = if (index == 1) "https://example.test/inline" else "https://example.test/new"
                assertTrue("real link annotations must be rendered in frame $index",
                    texts("candidate").any { text -> text.links.any { it.third == expectedUrl } })
            }
        }
        val before = compose.onNodeWithTag("candidate").getUnclippedBoundsInRoot()
        compose.runOnIdle { width.value = 180.dp }
        compose.waitForIdle()
        assertSameRendering("narrow width while frozen")
        val narrow = compose.onNodeWithTag("candidate").getUnclippedBoundsInRoot()
        assertTrue("frozen text must still reflow: $before -> $narrow", narrow.bottom - narrow.top > before.bottom - before.top)
        compose.runOnIdle { scale.value = 1.3f; dark.value = true }
        compose.waitForIdle()
        assertSameRendering("font scale and theme while frozen")
        val scaled = compose.onNodeWithTag("candidate").getUnclippedBoundsInRoot()
        assertTrue("frozen text must still follow font scale: $narrow -> $scaled", scaled.bottom - scaled.top > narrow.bottom - narrow.top)
    }

    @Composable
    private fun CountedElement(
        node: ASTNode, content: String, components: MarkdownComponents,
        freeze: Boolean, calls: MutableMap<Int, Int>,
    ) {
        SideEffect { calls[node.startOffset] = (calls[node.startOffset] ?: 0) + 1 }
        LegacyBoundary(node, content, components, freeze)
    }

    @Composable
    private fun CountDocument(
        snapshot: StreamingGfmSnapshot, candidate: Boolean, calls: MutableMap<Int, Int>,
    ) {
        val components = remember { markdownComponents() }
        val transformer = if (candidate) {
            rememberStreamingMarkdownImageTransformer(snapshot)
        } else {
            NoOpImageTransformerImpl()
        }
        Markdown(
            state = snapshot.state,
            components = components,
            imageTransformer = transformer,
            animations = markdownAnimations(animateTextSize = { this }),
            success = { state, c, modifier ->
                Column(modifier) {
                    val blocks = topLevelMarkdownBlocks(state.node)
                    val tail = blocks.last().startOffset
                    check(blocks.size == 4)
                    check(blocks.count {
                        shouldFreezeStreamingMarkdownBlock(it.startOffset, tail)
                    } == 3)
                    blocks.forEach { node ->
                        key(node.startOffset, node.type.name) {
                            val freeze = shouldFreezeStreamingMarkdownBlock(node.startOffset, tail)
                            val n = rememberFrozenMarkdownInput(node, freeze)
                            val text = rememberFrozenMarkdownInput(state.content, freeze)
                            CountedElement(n, text, c, freeze, calls)
                        }
                    }
                }
            },
        )
    }

    @Test fun realProviderSkipsFrozenPlainBlocksButUpdatesTheTail() {
        val parser = StreamingGfmParserSession()
        val prefix = "Frozen one.\n\nFrozen two.\n\nFrozen three.\n\n"
        fun next(i: Int) = parser.parse(prefix + "Tail $i", isComplete = false)
        val current = mutableStateOf(next(0))
        val old = linkedMapOf<Int, Int>()
        val fixed = linkedMapOf<Int, Int>()
        compose.setContent {
            MaterialTheme {
                Column {
                    key("fresh") { CountDocument(current.value, false, old) }
                    key("retained") { CountDocument(current.value, true, fixed) }
                }
            }
        }
        compose.waitForIdle()
        val offsets = topLevelMarkdownBlocks(current.value.state.node).map { it.startOffset }
        val baseline = fixed.toMap()
        var previousOld = old.toMap()
        var previousTail = fixed.getValue(offsets.last())
        repeat(8) { i ->
            compose.runOnIdle {
                val snapshot = next(i + 1)
                assertSame(current.value.state.referenceLinkHandler, snapshot.state.referenceLinkHandler)
                current.value = snapshot
            }
            compose.waitForIdle()
            compose.runOnIdle {
                offsets.dropLast(1).forEach { offset ->
                    assertEquals("frozen plain block must skip", baseline[offset], fixed[offset])
                    assertTrue("fresh control must recompose", old.getValue(offset) > previousOld.getValue(offset))
                }
                assertTrue("active tail must update", fixed.getValue(offsets.last()) > previousTail)
                previousOld = old.toMap()
                previousTail = fixed.getValue(offsets.last())
            }
        }
    }

    @Test fun compositionReentryReusesBracketSnapshotButNewPublicationRefreshes() {
        val parser = StreamingGfmParserSession()
        val current = mutableStateOf(parser.parse("[link](https://example.test)", true))
        val navigationTick = mutableStateOf(0)
        val observed = mutableListOf<com.mikepenz.markdown.model.ImageTransformer>()
        compose.setContent {
            val tick = navigationTick.value
            val transformer = rememberStreamingMarkdownImageTransformer(current.value)
            SideEffect {
                check(tick >= 0)
                observed.add(transformer)
            }
        }
        compose.waitForIdle()
        val first = observed.last()
        val initialCommits = observed.size
        repeat(3) {
            compose.runOnIdle { navigationTick.value++ }
            compose.waitForIdle()
            compose.runOnIdle { assertSame(first, observed.last()) }
        }
        assertTrue("fixture must re-enter composition", observed.size > initialCommits)
        compose.runOnIdle {
            current.value = parser.parse("[link](https://example.test)\n\nTail", true)
        }
        compose.waitForIdle()
        val changed = observed.last()
        assertNotSame(first, changed)
        compose.runOnIdle { navigationTick.value++ }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(changed, observed.last()) }
    }

    @Test fun optimizedPlainRendererPreservesGeometryAndCurrentTypography() {
        val parser = StreamingGfmParserSession()
        val current = mutableStateOf(Frame(parser.parse("Pure **bold** and *emphasis* with `code`.\n\nTail", true), true))
        val width = mutableStateOf(320.dp)
        val scale = mutableStateOf(1f)
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Column {
                        key("legacy") { Document(current.value, false, "legacy", width.value) }
                        key("candidate") { Document(current.value, true, "candidate", width.value) }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertSameRendering("plain entry")
        compose.runOnIdle { current.value = Frame(parser.parse("Pure **bold** and *emphasis* with `code`.\n\nTail grows", true), true) }
        compose.waitForIdle()
        assertSameRendering("plain frozen append")
        compose.runOnIdle { width.value = 180.dp }
        compose.waitForIdle()
        assertSameRendering("plain width")
        compose.runOnIdle { scale.value = 1.3f }
        compose.waitForIdle()
        assertSameRendering("plain font scale")
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        assertSameRendering("plain theme")
    }

}
