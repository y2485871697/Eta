package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Exercise the shared card modifier inside the real enter/exit transition.
 *
 * The structural assertions deliberately inspect its parameter-form layer element, not
 * frame timings: adding/removing that element at a transition boundary is the regression.
 * Native screenshots additionally check invalidation and the >8192px scrollable fallback.
 * This is not a substitute for a RenderThread/GPU trace on the affected device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetainedCardDrawLayerTest {
    @get:Rule val compose = createComposeRule()

    private val visibility = MutableTransitionState(false)
    private val contentHeight = mutableIntStateOf(80)
    private val bodyColor = mutableStateOf(Color.Red)
    private val retentionEnabled = mutableStateOf(true)
    private var bodyIdentity: Any? = null
    private var observation: LayerObservation? = null
    private lateinit var scrollState: ScrollState
    private lateinit var scope: CoroutineScope
    private val bottomLayouts = listOf(VIEWPORT_TAG, LABEL_TAG, CONTAINER_TAG, CONTENT_TAG)
        .associateWith { BottomLayoutProbe() }

    @Test fun layerElementRemainsPresentAcrossEntryExitAndReversal() {
        setUpCard()
        compose.runOnIdle { visibility.targetState = true }
        advance(32)
        val entering = assertLayer(CompositingStrategy.Auto)
        assertEquals(EnterExitState.PreEnter, entering.current)
        assertEquals(EnterExitState.Visible, entering.target)
        val elementTypes = entering.elements.map { it.javaClass }

        awaitVisibilitySettled(visible = true)
        val idle = assertLayer(CompositingStrategy.Offscreen)
        assertEquals(EnterExitState.Visible, idle.current)
        assertEquals(EnterExitState.Visible, idle.target)
        assertEquals(elementTypes, idle.elements.map { it.javaClass })

        compose.runOnIdle { visibility.targetState = false }
        advance(32)
        val exiting = assertLayer(CompositingStrategy.Auto)
        assertEquals(EnterExitState.Visible, exiting.current)
        assertEquals(EnterExitState.PostExit, exiting.target)
        assertEquals(elementTypes, exiting.elements.map { it.javaClass })

        // Reverse before removal: changing the strategy must not change the node chain.
        compose.runOnIdle { visibility.targetState = true }
        awaitVisibilitySettled(visible = true)
        val reversed = assertLayer(CompositingStrategy.Offscreen)
        assertEquals(elementTypes, reversed.elements.map { it.javaClass })
        assertEquals(EnterExitState.Visible, reversed.current)
        assertEquals(EnterExitState.Visible, reversed.target)

        compose.runOnIdle { visibility.targetState = false }
        awaitVisibilitySettled(visible = false)
        compose.onNodeWithTag(CONTENT_TAG).assertDoesNotExist()

        // A new composition after full collapse still starts with an Auto layer.
        compose.runOnIdle { visibility.targetState = true }
        advance(32)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Auto).elements.map { it.javaClass })
        awaitVisibilitySettled(visible = true)
        assertLayer(CompositingStrategy.Offscreen)
    }

    @Test fun completionAndVisibilityShareOneLayerWithoutRemountingContent() {
        retentionEnabled.value = false
        setUpCard()
        compose.runOnIdle { visibility.targetState = true }
        advance(32)
        val elementTypes = assertLayer(CompositingStrategy.Auto).elements.map { it.javaClass }
        val identity = checkNotNull(bodyIdentity)

        // Completion alone cannot retain while the appearance animation is still running.
        compose.runOnIdle { retentionEnabled.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Auto).elements.map { it.javaClass })
        assertSame(identity, bodyIdentity)

        awaitVisibilitySettled(visible = true)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Offscreen).elements.map { it.javaClass })
        assertSame(identity, bodyIdentity)

        // A visible but still-streaming body must not acquire a second completed-content layer.
        compose.runOnIdle { retentionEnabled.value = false }
        advance(32)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Auto).elements.map { it.javaClass })
        assertSame(identity, bodyIdentity)
        compose.runOnIdle { retentionEnabled.value = true }
        advance(32)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Offscreen).elements.map { it.javaClass })
        assertSame(identity, bodyIdentity)
    }

    @Test fun zeroHeightUsesAutoAndOnePixelHeightIsRetainedWithoutChangingTheChain() {
        contentHeight.intValue = 0
        setUpCard()
        compose.runOnIdle { visibility.targetState = true }
        awaitVisibilitySettled(visible = true)
        val elementTypes = assertLayer(CompositingStrategy.Auto).elements.map { it.javaClass }

        compose.runOnIdle { contentHeight.intValue = 1 }
        advance(256)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Offscreen).elements.map { it.javaClass })
        compose.runOnIdle { contentHeight.intValue = 0 }
        advance(256)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Auto).elements.map { it.javaClass })
    }

    @Test fun heightLimitChangesStrategyWithoutRemovingLayerOrClippingTheTail() {
        setUpCard()
        compose.runOnIdle { visibility.targetState = true }
        advance(256)
        val elementTypes = assertLayer(CompositingStrategy.Offscreen).elements.map { it.javaClass }

        compose.runOnIdle { contentHeight.intValue = 8192 }
        advance(256)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Offscreen).elements.map { it.javaClass })

        compose.runOnIdle { contentHeight.intValue = 8193 }
        advance(256)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Auto).elements.map { it.javaClass })
        compose.runOnIdle {
            assertTrue("Tall content must remain fully scrollable", scrollState.maxValue > 8000)
            scope.launch { scrollState.scrollTo(scrollState.maxValue) }
        }
        advance(32)
        val tail = compose.onNodeWithTag(VIEWPORT_TAG).captureToImage().asAndroidBitmap()
        assertEquals("The final eight pixels must not be clipped by a retained texture",
            Color.Green.toArgb(), tail.getPixel(24, 156))

        // Returning below the limit must re-enable retention without changing the chain.
        compose.runOnIdle { contentHeight.intValue = 80 }
        advance(256)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Offscreen).elements.map { it.javaClass })
    }

    @Test fun retainedLayerRepaintsWhenVisibleContentChanges() {
        setUpCard()
        compose.runOnIdle { visibility.targetState = true }
        advance(256)
        assertLayer(CompositingStrategy.Offscreen)
        val before = compose.onNodeWithTag(VIEWPORT_TAG).captureToImage().asAndroidBitmap()
        assertEquals(Color.Red.toArgb(), before.getPixel(24, 24))

        // Read in the draw phase, like selection/streaming invalidation, not a new card.
        compose.runOnIdle { bodyColor.value = Color.Blue }
        advance(32)
        assertLayer(CompositingStrategy.Offscreen)
        val after = compose.onNodeWithTag(VIEWPORT_TAG).captureToImage().asAndroidBitmap()
        assertEquals(Color.Blue.toArgb(), after.getPixel(24, 24))
    }

    @Test fun bottomEntryAndCollapseKeepTheTailAnchoredEveryFrame() {
        setUpCard(fromBottom = true)
        val collapsedLabel = bottomBounds(LABEL_TAG)
        compose.runOnIdle { visibility.targetState = true }
        val entering = bottomFramesUntil { visibility.isIdle && visibility.currentState }
        assertBottomDirection(entering, expanding = true)
        assertLayer(CompositingStrategy.Offscreen)
        assertEquals(80f, entering.last().container.heightDp.value, 0.5f)
        assertEquals("The label must move up by the expanded content height",
            collapsedLabel.top.value - 80f, entering.last().label.top.value, 0.5f)

        compose.runOnIdle { visibility.targetState = false }
        val exiting = bottomFramesUntil { visibility.isIdle && !visibility.currentState }
        assertBottomDirection(exiting, expanding = false)
        (entering + exiting).forEach { assertEquals(entering.first().elementTypes, it.elementTypes) }
        compose.onNodeWithTag(CONTENT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CONTAINER_TAG).assertDoesNotExist()
        assertEquals(collapsedLabel.top.value, bottomBounds(LABEL_TAG).top.value, 0.5f)
        assertTrue("Frame sampling must leave the clock manual", !compose.mainClock.autoAdvance)
    }

    @Test fun bottomExitReversalPreservesLayerTypesAndVisibleTailEveryFrame() {
        setUpCard(fromBottom = true)
        compose.runOnIdle { visibility.targetState = true }
        val entering = bottomFramesUntil { visibility.isIdle && visibility.currentState }
        val expanded = entering.last()
        assertLayer(CompositingStrategy.Offscreen)
        compose.runOnIdle { visibility.targetState = false }
        val exiting = bottomFramesUntil { frame ->
            frame != null && frame.container.heightDp.value in 20f..60f
        }
        val midpoint = exiting.last()
        assertTrue("Reverse during exit, not after disposal", !visibility.isIdle)
        assertTrue(midpoint.label.top > expanded.label.top)
        assertLayer(CompositingStrategy.Auto)

        compose.runOnIdle { visibility.targetState = true }
        val reversed = bottomFramesUntil(requireContent = true) {
            visibility.isIdle && visibility.currentState
        }
        (entering + exiting + reversed).forEach {
            assertEquals(expanded.elementTypes, it.elementTypes)
        }
        assertTrue("Reversal must include intermediate expansion frames",
            reversed.any { it.container.heightDp.value > midpoint.container.heightDp.value + 1f &&
                it.container.heightDp.value < 79f })
        assertTrue("Reversal must include a visible tail pixel check",
            reversed.any { it.tailPixelChecked })
        assertEquals(expanded.container.heightDp.value, reversed.last().container.heightDp.value, 0.5f)
        assertEquals(expanded.label.top.value, reversed.last().label.top.value, 0.5f)
        assertLayer(CompositingStrategy.Offscreen)
        assertTrue("Frame sampling must leave the clock manual", !compose.mainClock.autoAdvance)
    }

    private fun setUpCard(fromBottom: Boolean = false) {
        if (fromBottom) {
            setUpBottomAnchoredCard()
            return
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                scrollState = rememberScrollState()
                scope = rememberCoroutineScope()
                Column(
                    Modifier.size(80.dp, 160.dp)
                        .background(Color.Black)
                        .testTag(VIEWPORT_TAG)
                        .verticalScroll(scrollState),
                ) {
                    AnimatedVisibility(
                        visibleState = visibility,
                        enter = tailDetailsEnter(fromBottom = false),
                        exit = tailDetailsExit(toBottom = false),
                    ) {
                        val retained = retainDrawLayerWhenIdle(enabled = retentionEnabled.value)
                        val identity = remember { Any() }
                        val current = transition.currentState
                        val target = transition.targetState
                        val elements = retained.foldIn(emptyList<Modifier.Element>()) { list, element ->
                            list + element
                        }
                        SideEffect {
                            observation = LayerObservation(current, target, elements)
                            bodyIdentity = identity
                        }
                        Box(
                            retained.width(48.dp)
                                .height(contentHeight.intValue.dp)
                                .testTag(CONTENT_TAG)
                                .drawBehind {
                                    drawRect(bodyColor.value)
                                    drawRect(
                                        color = Color.Green,
                                        topLeft = Offset(0f, size.height - 8f),
                                        size = Size(size.width, 8f),
                                    )
                                },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun setUpBottomAnchoredCard() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                // A real bottom anchor, independent of any production follow-tail controller.
                Column(
                    Modifier.size(80.dp, 160.dp).background(Color.Black).testTag(VIEWPORT_TAG)
                        .observeBottomLayout(VIEWPORT_TAG),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(Modifier.size(48.dp, 16.dp).background(Color.Yellow).testTag(LABEL_TAG)
                        .observeBottomLayout(LABEL_TAG)) {
                        BasicText("Tail")
                    }
                    AnimatedVisibility(
                        visibleState = visibility,
                        modifier = Modifier.testTag(CONTAINER_TAG).observeBottomLayout(CONTAINER_TAG),
                        enter = tailDetailsEnter(fromBottom = true),
                        exit = tailDetailsExit(toBottom = true),
                    ) {
                        val retained = retainDrawLayerWhenIdle()
                        val current = transition.currentState
                        val target = transition.targetState
                        val elements = retained.foldIn(emptyList<Modifier.Element>()) { list, element ->
                            list + element
                        }
                        SideEffect {
                            observation = LayerObservation(current, target, elements)
                        }
                        Box(Modifier.size(80.dp, 80.dp)) {
                            Box(retained.size(48.dp, 80.dp).testTag(CONTENT_TAG)
                                .observeBottomLayout(CONTENT_TAG).drawBehind {
                                    drawRect(Color.Red)
                                    drawRect(Color.Green, Offset(0f, size.height - 8f), Size(size.width, 8f))
                                })
                            // This unretained swatch shares the production visibility fade.
                            // It supplies a rendered alpha reference, independent of internal
                            // Transition animation registries and deferred-animation labels.
                            Box(Modifier.align(Alignment.CenterEnd).size(8.dp, 80.dp)
                                .background(Color.White))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun Modifier.observeBottomLayout(tag: String): Modifier {
        val probe = bottomLayouts.getValue(tag)
        return layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            probe.measuredSize = IntSize(placeable.width, placeable.height)
            // A new measurement is not evidence that this node was also placed.
            probe.placed = false
            layout(placeable.width, placeable.height) {
                placeable.place(0, 0)
                probe.placed = true
            }
        }.onPlaced { probe.coordinates = it }
    }

    private fun bottomBounds(tag: String): DpRect =
        checkNotNull(bottomLayouts.getValue(tag).boundsOrNull()) {
            "Expected measured, placed bounds for $tag: ${bottomLayouts.getValue(tag)}"
        }

    private fun bottomFramesUntil(
        requireContent: Boolean = false,
        done: (BottomFrame?) -> Boolean,
    ): List<BottomFrame> {
        val frames = mutableListOf<BottomFrame>()
        repeat(120) {
            assertTrue(!compose.mainClock.autoAdvance)
            // No advance() feedback frame or auto-advancing settle helper: inspect every tick.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val time = compose.mainClock.currentTime
            // Presence is only a disposal check, never proof of valid layout coordinates.
            val containerPresent = compose.onAllNodesWithTag(CONTAINER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
            val contentPresent = compose.onAllNodesWithTag(CONTENT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
            if (requireContent) {
                assertTrue("Reversal must not dispose the container or content, even on an unplaced tick",
                    containerPresent && contentPresent)
            }
            val bounds = if (containerPresent && contentPresent) {
                bottomLayouts.mapValues { (_, probe) -> probe.boundsOrNull() }
            } else emptyMap()
            val frame = if (bounds.isEmpty() || bounds.values.any { it == null }) {
                // Zero-height, not-yet-placed and detached frames have no usable geometry.
                // Still check done below on this very tick, including final exit disposal.
                assertTrue("A settled visible card must have measured, placed bounds: $bottomLayouts",
                    !visibility.isIdle || !visibility.currentState)
                null
            } else {
                val container = checkNotNull(bounds[CONTAINER_TAG])
                val label = checkNotNull(bounds[LABEL_TAG])
                val content = checkNotNull(bounds[CONTENT_TAG])
                assertEquals("Animated container bottom must stay fixed",
                    checkNotNull(bounds[VIEWPORT_TAG]).bottom.value, container.bottom.value, 0.5f)
                assertEquals("Label must make room above the animated container",
                    container.top.value, label.bottom.value, 0.5f)
                assertEquals("Full content must remain bottom aligned, not top revealed",
                    container.bottom.value, content.bottom.value, 0.5f)
                assertEquals(80f, content.heightDp.value, 0.5f)
                if (container.heightDp.value > 0f && container.heightDp.value < 80f) {
                    assertLayer(CompositingStrategy.Auto)
                }
                // Compare two pixels under the same real fade, including legitimate
                // fully transparent exit frames; do not infer alpha from height or time.
                var tailPixelChecked = false
                if (container.heightDp.value >= 40f) {
                    val image = compose.onNodeWithTag(VIEWPORT_TAG).captureToImage().asAndroidBitmap()
                    val reference = android.graphics.Color.green(image.getPixel(76, 156))
                    val pixel = image.getPixel(24, 156)
                    assertEquals("Tail must share the unretained reference's fade",
                        reference.toFloat(), android.graphics.Color.green(pixel).toFloat(), 6f)
                    assertTrue("Tail must stay green rather than exposing the red body",
                        android.graphics.Color.red(pixel) <= 6 && android.graphics.Color.blue(pixel) <= 6)
                    tailPixelChecked = reference >= 12
                }
                BottomFrame(container, label, content,
                    checkNotNull(observation).elements.map { it.javaClass }, tailPixelChecked)
                    .also { frames += it }
            }
            assertEquals("Coordinate reads and screenshots must not skip animation frames",
                time, compose.mainClock.currentTime)
            if (done(frame)) return frames
        }
        throw AssertionError("Bottom transition did not reach the requested state in 120 frames: $bottomLayouts")
    }

    private fun assertBottomDirection(frames: List<BottomFrame>, expanding: Boolean) {
        assertTrue("Must observe multiple intermediate frames, not only settled endpoints",
            frames.count { it.container.heightDp.value in 1f..79f } >= 2)
        assertTrue("Each direction must include a visible tail pixel check",
            frames.any { it.tailPixelChecked })
        frames.zipWithNext().forEach { (before, after) ->
            val sign = if (expanding) 1f else -1f
            assertTrue("Container must reveal upward and collapse downward",
                sign * (after.container.heightDp.value - before.container.heightDp.value) >= -0.5f)
            assertTrue("Label must track the moving top, never the fixed bottom",
                sign * (before.label.top.value - after.label.top.value) >= -0.5f)
            assertEquals(before.content.bottom.value, after.content.bottom.value, 0.5f)
        }
    }

    private val DpRect.heightDp: Dp get() = bottom - top

    private class BottomLayoutProbe {
        var measuredSize = IntSize.Zero
        var placed = false
        var coordinates: LayoutCoordinates? = null

        fun boundsOrNull(): DpRect? {
            if (!placed || measuredSize.width <= 0 || measuredSize.height <= 0) return null
            val attachedCoordinates = coordinates?.takeIf { it.isAttached } ?: return null
            val origin = attachedCoordinates.positionInRoot()
            assertTrue("Placed coordinates must be finite", origin.x.isFinite() && origin.y.isFinite())
            // The bottom fixture explicitly uses Density(1f). Never read clipped semantics bounds.
            return DpRect(origin.x.dp, origin.y.dp,
                (origin.x + measuredSize.width).dp, (origin.y + measuredSize.height).dp)
        }

        override fun toString(): String =
            "size=$measuredSize, placed=$placed, attached=${coordinates?.isAttached == true}"
    }

    private data class BottomFrame(
        val container: DpRect,
        val label: DpRect,
        val content: DpRect,
        val elementTypes: List<Class<*>>,
        val tailPixelChecked: Boolean,
    )

    private fun awaitVisibilitySettled(visible: Boolean) {
        // Completion also needs Android layout/draw and disposal work. Unlike
        // advanceTimeUntil's clock-only loop, waitUntil yields to the host between
        // frames. Auto-advance only while settling; intermediate-state assertions
        // must retain manual clock control after this helper returns.
        val previousAutoAdvance = compose.mainClock.autoAdvance
        try {
            compose.mainClock.autoAdvance = true
            // Drain Android measurement/drawing before checking transition completion.
            compose.waitForIdle()
            compose.waitUntil(5_000L) {
                visibility.isIdle && visibility.currentState == visible &&
                    visibility.targetState == visible
            }
            compose.waitForIdle()
        } finally {
            compose.mainClock.autoAdvance = previousAutoAdvance
        }
    }

    private fun advance(milliseconds: Long) {
        // Let composition and the Android measure pass establish the animation's size.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
        // onSizeChanged publishes the measured height; give its composition a frame too.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun assertLayer(strategy: CompositingStrategy): LayerObservation {
        var result: LayerObservation? = null
        compose.runOnIdle {
            val actual = checkNotNull(observation)
            val expected = checkNotNull(
                Modifier.graphicsLayer(compositingStrategy = strategy)
                    .foldIn<Modifier.Element?>(null) { _, element -> element },
            )
            // Use the public factory's own element rather than an internal class name.
            val layers = actual.elements.filter { it.javaClass == expected.javaClass }
            assertEquals("One stable parameter-form graphics layer is required in every phase",
                listOf(expected), layers)
            result = actual
        }
        return checkNotNull(result)
    }

    private data class LayerObservation(
        val current: EnterExitState,
        val target: EnterExitState,
        val elements: List<Modifier.Element>,
    )

    private companion object {
        const val VIEWPORT_TAG = "retained-card-viewport"
        const val CONTENT_TAG = "retained-card-content"
        const val CONTAINER_TAG = "retained-card-animated-container"
        const val LABEL_TAG = "retained-card-label"
    }
}
