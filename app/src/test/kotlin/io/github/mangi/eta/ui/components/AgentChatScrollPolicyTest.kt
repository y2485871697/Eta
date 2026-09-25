package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatScrollPolicyTest {
    @Test
    fun networkCompletionKeepsFollowingUntilRenderedTailSettles() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun draggingInterruptsCompletionFollowing() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = true,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completionDoesNotPullReaderBackFromHistory() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = false,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completedContentExpansionDoesNotFollowBottom() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun streamingTailGrowthFollowsBottom() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun completedConversationDoesNotUseStreamingInitialJump() {
        assertFalse(
            shouldRequestInitialBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun completedConversationSnapsToBottomOnOpen() {
        assertTrue(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = true,
            )
        )
    }

    @Test
    fun emptyConversationDoesNotSnapToBottom() {
        assertFalse(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = false,
            )
        )
    }

    @Test
    fun scrollToMessageSkipsConversationBottomSnap() {
        assertFalse(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = true,
                scrollToMessageId = "msg-1",
            )
        )
    }

    @Test
    fun conversationBottomSnapAlignsLastItemWithoutAnimation() {
        assertEquals(
            BottomFollowDecision(scrollByPx = -120),
            resolveConversationBottomSnap(
                bottomItemIndex = 8,
                lastVisibleIndex = 8,
                lastVisibleBottom = 880,
                viewportEnd = 1000,
            ),
        )
    }

    @Test
    fun conversationBottomSnapRequestsLastItemWhenNotVisible() {
        assertEquals(
            BottomFollowDecision(requestIndex = 8),
            resolveConversationBottomSnap(
                bottomItemIndex = 8,
                lastVisibleIndex = 2,
                lastVisibleBottom = 400,
                viewportEnd = 1000,
            ),
        )
    }

    @Test
    fun streamingConversationRequestsInitialBottom() {
        assertTrue(
            shouldRequestInitialBottom(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun contentGrowthDoesNotDisableBottomFollowing() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = false,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun draggingAwayFromBottomDisablesFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = true,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun draggingWhileAtBottomDisablesFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = true,
                isAtBottom = true,
            )
        )
    }

    @Test
    fun tinyDragAtBottomDoesNotResumeFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
                hasLeftBottom = false,
            )
        )
    }

    @Test
    fun landingOnBottomAfterUserScrollEnablesFollowing() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
                hasLeftBottom = true,
            )
        )
    }

    @Test
    fun reachingBottomEnablesFollowingAgain() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
                hasLeftBottom = true,
            )
        )
    }

    @Test
    fun streamingScrollableListDoesNotPinFromBottom() {
        assertFalse(shouldPinConversationToBottom(isStreaming = true, isScrollable = true))
        assertTrue(shouldPinConversationToBottom(isStreaming = true, isScrollable = false))
        assertTrue(shouldPinConversationToBottom(isStreaming = false, isScrollable = true))
    }

    @Test
    fun growingTailOnlyScrollsByTheOverflowDistance() {
        assertEquals(
            BottomFollowDecision(scrollByPx = 24),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = 1024,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }

    @Test
    fun missingBottomSentinelUsesBoundedSmoothFollow() {
        assertEquals(
            BottomFollowDecision(scrollByPx = 1000),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = null,
                viewportEnd = 1000,
                lastVisibleIndex = 6,
            ),
        )
    }

    @Test
    fun disabledFollowingNeverMovesTheList() {
        assertEquals(
            BottomFollowDecision(),
            resolveBottomFollowDecision(
                enabled = false,
                bottomItemIndex = 8,
                sentinelBottom = 1100,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }

    @Test fun hiddenSentinelUsesViewportBoundAndNeverRequestsAnIndex() {
        val decision = resolveBottomFollowDecision(true, 20, null, 600, 4, viewportSizePx = 800)
        assertEquals(BottomFollowDecision(scrollByPx = 800), decision)
        assertEquals(null, decision.requestIndex)
    }

    @Test fun reachedPhysicalEndStopsEvenWithStaleOverflow() {
        assertEquals(BottomFollowDecision(), resolveBottomFollowDecision(true, 20, 900, 600, 20, canScrollForward = false))
    }

    @Test fun emptyListDoesNotStartFollowing() {
        assertEquals(BottomFollowDecision(), resolveBottomFollowDecision(true, 0, null, 600, null))
    }

    @Test fun initialPositionWaitsForContentButAbandonsOnUserNavigation() {
        assertFalse(InitialBottomPosition(0, true, true, false).ready)
        assertFalse(InitialBottomPosition(8, false, true, false).ready)
        assertTrue(InitialBottomPosition(8, true, true, false).shouldPosition)
        assertTrue(InitialBottomPosition(0, false, true, true).ready)
        assertFalse(InitialBottomPosition(8, true, true, true).shouldPosition)
        assertFalse(InitialBottomPosition(8, true, false, false).shouldPosition)
    }

    @Test fun tallSingleItemKeepsPublishingDistanceAfterEachViewport() {
        // Emulate snapshotFlow's equality gate while a 4-screen item stays at
        // the same index. No new network text arrives during the entire drain.
        val motion = BottomFollowMotion()
        var position = 0f
        val end = 3200f
        var pending = 0f
        var previous: BottomFollowLayout? = null
        var requests = 0
        for (frame in 0..1800) {
            val remaining = end - position
            val layout = BottomFollowLayout(
                enabled = true, bottomItemIndex = 8,
                sentinelBottom = if (remaining <= 800f) (800f + remaining).toInt() else null,
                viewportEnd = 800, lastVisibleIndex = if (remaining <= 800f) 8 else 7,
                viewportSizePx = 800, canScrollForward = remaining > 0f,
                lastVisibleOffset = -position.toInt(),
            )
            if (layout != previous) {
                previous = layout
                val decision = resolveBottomFollowDecision(layout.enabled, layout.bottomItemIndex,
                    layout.sentinelBottom, layout.viewportEnd, layout.lastVisibleIndex,
                    layout.viewportSizePx, layout.canScrollForward)
                assertEquals(null, decision.requestIndex)
                pending = decision.scrollByPx.toFloat()
                requests++
            }
            val moved = motion.step(pending, frame * 16_666_667L, 1f)
            position += moved
            pending = (pending - moved).coerceAtLeast(0f)
        }
        assertTrue("fallback must remain live beyond the first viewport", position >= end - 1.1f)
        assertTrue(requests > 10)
    }
}
