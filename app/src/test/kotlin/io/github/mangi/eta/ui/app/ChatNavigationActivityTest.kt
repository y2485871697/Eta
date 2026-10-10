package io.github.mangi.eta.ui.app

import android.view.Choreographer
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNavigationActivityTest {
    @Test
    fun freshDrawStaysActiveAndStaleDrawDoesNot() {
        assertTrue(navigationActivityIsCurrent(flag = true, stampNs = 150, nowNs = 200, staleNs = 100))
        assertFalse(navigationActivityIsCurrent(flag = true, stampNs = 0, nowNs = 200, staleNs = 100))
        assertFalse(navigationActivityIsCurrent(flag = false, stampNs = 200, nowNs = 200, staleNs = 100))
    }

    @Test
    fun missingDrawAfterMotionPublishesIdle() {
        val events = mutableListOf<Boolean>()
        var now = 0L
        val posted = ArrayDeque<Choreographer.FrameCallback>()
        val tracker = ChatNavigationActivityTracker(
            onChanged = { events += it },
            staleNs = 100,
            nowNs = { now },
            poster = { posted.addLast(it) },
            remover = { posted.remove(it) },
        )
        tracker.observeFrame(true)
        now = 10
        posted.remove().doFrame(0)
        now = 1_000
        posted.remove().doFrame(0)
        assertEquals(listOf(true, false), events)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun idleDrawDoesNotStartAFrameLoop() {
        val posted = ArrayDeque<Choreographer.FrameCallback>()
        val tracker = ChatNavigationActivityTracker(
            onChanged = {},
            nowNs = { 0L },
            poster = { posted.addLast(it) },
            remover = { posted.remove(it) },
        )
        tracker.observeFrame(false)
        assertTrue(posted.isEmpty())
    }
}
