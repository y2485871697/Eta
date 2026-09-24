package io.github.mangi.eta.ui.components

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmoothTextRevealCoordinatorTest {
    private val textMeasurer by lazy {
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(RuntimeEnvironment.getApplication()),
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    @Test
    fun feedbackFollowsFramesButNotRestoreOrDetachedHistory() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        var pulses = 0
        coordinator.setOnRevealAdvanced { if (it > 0f) pulses++ }
        val key = RevealBlockKey(0)
        val node = attach(coordinator, key, "正在显示的新文字")
        assertEquals(0, pulses)
        val clock = TestFrameClock()
        val job = launch(clock, start = CoroutineStart.UNDISPATCHED) { coordinator.runFrameClock() }
        try {
            clock.send(0L)
            clock.send(50_000_000L)
            yield()
            assertTrue(pulses > 0)
            val beforeRestore = pulses
            coordinator.restoreHistoryThrough(100)
            coordinator.resumeAnimationsAfterCatchUp()
            coordinator.detach(key, node)
            assertEquals(beforeRestore, pulses)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun detachedEarlierBlockCompletesAndLaterBlockKeepsRevealing() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val earlierKey = RevealBlockKey(0)
        val laterKey = RevealBlockKey(100)
        val earlierNode = attach(coordinator, earlierKey, "早先的思考正文")
        attach(coordinator, laterKey, "随后到达的工具输出内容")

        coordinator.detach(earlierKey, earlierNode)

        assertEquals(7f, coordinator.drawSnapshot(earlierKey)!!.progress, 0f)
        assertTrue(earlierKey in coordinator.started.value)
        assertFalse(coordinator.drained.value)
        val clock = TestFrameClock()
        val frameJob = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            coordinator.runFrameClock()
        }
        try {
            clock.send(0L)
            clock.send(50_000_000L)
            yield()

            assertTrue(coordinator.drawSnapshot(laterKey)!!.progress > 0f)
        } finally {
            frameJob.cancelAndJoin()
        }
    }

    @Test
    fun reattachedBlockKeepsCompletedPrefixAndAnimatesOnlyNewText() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val oldNode = attach(coordinator, key, "已有文字")

        coordinator.detach(key, oldNode)
        assertTrue(coordinator.drained.value)
        attach(coordinator, key, "已有文字和新增文字")

        val snapshot = coordinator.drawSnapshot(key)!!
        assertEquals(4f, snapshot.progress, 0f)
        assertEquals(9, snapshot.boundaries.lastIndex)
        assertFalse(coordinator.drained.value)
    }

    @Test
    fun restoredLayoutWithoutMountedNodeIsImmediatelyReadableOnLaterAttach() {
        val coordinator = SmoothTextRevealCoordinator()
        coordinator.restoreHistoryThrough(100)
        val key = RevealBlockKey(0)
        val text = "尚未挂载时收到的历史内容"
        val state = SmoothTextRevealState(key, coordinator)
        state.onTextLayout(text, layout(text))

        assertTrue(coordinator.drained.value)
        assertEquals(text.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
        state.attach(SmoothTextRevealNode(state))

        assertTrue(coordinator.drained.value)
        assertEquals(text.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
    }

    @Test
    fun staleDetachDoesNotCompleteTheReplacementNode() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val oldNode = attach(coordinator, key, "原始内容")
        val replacementNode = attach(coordinator, key, "替换后的完整内容")

        coordinator.detach(key, oldNode)

        assertEquals(0f, coordinator.drawSnapshot(key)!!.progress, 0f)
        assertFalse(coordinator.drained.value)
        coordinator.detach(key, replacementNode)
        assertTrue(coordinator.drained.value)
    }

    @Test fun liveLayoutBeforeAttachRetainsTheTypewriter() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val state = SmoothTextRevealState(key, coordinator)
        val text = "刚到的首段文字"
        state.onTextLayout(text, layout(text))
        assertEquals(0f, coordinator.drawSnapshot(key)!!.progress, 0f)
        assertFalse(coordinator.drained.value)
        state.attach(SmoothTextRevealNode(state))
        assertEquals(0f, coordinator.drawSnapshot(key)!!.progress, 0f)
    }

    @Test fun rapidOutputDoesNotGrowBySeveralLinesInOneFrame() {
        val text = "甲\n乙\n丙\n丁\n戊\n己\n庚"
        val result = layout(text)
        val boundaries = graphemeBoundaries(text)
        val proposed = advanceSmoothReveal(0f, boundaries.lastIndex.toFloat(), 0.05f, 1_000f)
        val limited = limitRevealToNextLine(0f, proposed, boundaries, result)
        assertTrue(limited < proposed)
        val visibleIndex = boundaries[kotlin.math.ceil(limited).toInt()]
        assertEquals(0, result.getLineForOffset(visibleIndex - 1))
        val second = limitRevealToNextLine(limited, proposed, boundaries, result)
        assertEquals(1, result.getLineForOffset(boundaries[kotlin.math.ceil(second).toInt()] - 1))
    }

    @Test fun unseenFutureAstBlockKeepsMessageUndrained() {
        val coordinator = SmoothTextRevealCoordinator()
        val first = RevealBlockKey(0)
        val future = RevealBlockKey(100)
        coordinator.retainBlocks(setOf(first, future))
        val firstNode = attach(coordinator, first, "首段")
        assertFalse(coordinator.drained.value)
        coordinator.detach(first, firstNode)
        assertTrue(first in coordinator.completed.value)
        assertFalse(coordinator.drained.value) // future block has no node yet
        val futureNode = attach(coordinator, future, "后一段")
        coordinator.detach(future, futureNode)
        assertTrue(coordinator.drained.value)
        coordinator.retainBlocks(setOf(future))
        assertFalse(first in coordinator.completed.value)
    }

    @Test fun restoredLateLayoutDoesNotReplayButSubsequentNetworkTextStillAnimates() {
        val coordinator = SmoothTextRevealCoordinator()
        coordinator.restoreHistoryThrough(100)
        // Parent restore finishes before this old Markdown node attaches.
        coordinator.resumeAnimationsAfterCatchUp()
        val key = RevealBlockKey(20)
        val node = attach(coordinator, key, "已有文字")
        assertEquals(4f, coordinator.drawSnapshot(key)!!.progress, 0f)
        coordinator.updateLayout(key, node, "已有文字和新增文字", layout("已有文字和新增文字"))
        assertEquals(4f, coordinator.drawSnapshot(key)!!.progress, 0f)
        assertFalse(coordinator.drained.value)
        val newKey = RevealBlockKey(110)
        attach(coordinator, newKey, "新段落")
        assertEquals(0f, coordinator.drawSnapshot(newKey)!!.progress, 0f)
    }

    @Test fun scrollPauseThenResumeKeepsOldTextAndAnimatesNextDelta() = runBlocking {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val node = attach(coordinator, key, "已有文字")
        coordinator.pauseAnimationsAndCatchUp()
        val duringScroll = "已有文字滑动期间新增"
        coordinator.updateLayout(key, node, duringScroll, layout(duringScroll))
        assertEquals(duringScroll.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
        coordinator.resumeAnimationsWithoutCatchingUp()
        val afterScroll = duringScroll + "恢复后的新文字"
        coordinator.updateLayout(key, node, afterScroll, layout(afterScroll))
        assertEquals(duringScroll.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
        assertFalse(coordinator.drained.value)
        val clock = TestFrameClock()
        val job = launch(clock, start = CoroutineStart.UNDISPATCHED) { coordinator.runFrameClock() }
        try {
            clock.send(0L)
            clock.send(50_000_000L)
            yield()
            assertTrue(coordinator.drawSnapshot(key)!!.progress > duringScroll.length)
        } finally { job.cancelAndJoin() }
    }

    @Test fun scrollReattachDoesNotResetAlreadyVisibleText() {
        val coordinator = SmoothTextRevealCoordinator()
        val key = RevealBlockKey(0)
        val node = attach(coordinator, key, "已有文字")
        coordinator.pauseAnimationsAndCatchUp()
        coordinator.detach(key, node)
        val text = "已有文字以及滑动期间收到的内容"
        attach(coordinator, key, text)
        coordinator.resumeAnimationsWithoutCatchingUp()
        assertEquals(text.length.toFloat(), coordinator.drawSnapshot(key)!!.progress, 0f)
        assertTrue(coordinator.drained.value)
    }

    private fun attach(
        coordinator: SmoothTextRevealCoordinator,
        key: RevealBlockKey,
        text: String,
    ): SmoothTextRevealNode {
        val state = SmoothTextRevealState(key, coordinator)
        val node = SmoothTextRevealNode(state)
        state.attach(node)
        state.onTextLayout(text, layout(text))
        return node
    }

    private fun layout(text: String): TextLayoutResult = textMeasurer.measure(
        text = text,
        style = TextStyle(fontSize = 16.sp),
        constraints = Constraints(maxWidth = 320),
    )

    private class TestFrameClock : MonotonicFrameClock {
        private val frames = Channel<Long>(Channel.UNLIMITED)

        fun send(timeNanos: Long) {
            check(frames.trySend(timeNanos).isSuccess)
        }

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
    }
}
