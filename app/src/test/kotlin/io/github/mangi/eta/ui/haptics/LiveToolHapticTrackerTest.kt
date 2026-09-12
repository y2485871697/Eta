package io.github.mangi.eta.ui.haptics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveToolHapticTrackerTest {
    @Test
    fun marksEachToolIdOnce() {
        val tracker = LiveToolHapticTracker()
        assertTrue(tracker.markIfNew("run-1-tool-1-call-a"))
        assertFalse(tracker.markIfNew("run-1-tool-1-call-a"))
        assertTrue(tracker.markIfNew("run-1-tool-1-call-b"))
    }

    @Test
    fun ignoresBlankIds() {
        val tracker = LiveToolHapticTracker()
        assertFalse(tracker.markIfNew(""))
        assertFalse(tracker.markIfNew("   "))
    }

    @Test
    fun evictsOldestIdsWhenOverCapacity() {
        val tracker = LiveToolHapticTracker(maxIds = 2)
        assertTrue(tracker.markIfNew("a"))
        assertTrue(tracker.markIfNew("b"))
        assertTrue(tracker.markIfNew("c"))
        assertTrue(tracker.markIfNew("a"))
        assertFalse(tracker.markIfNew("c"))
    }
}
