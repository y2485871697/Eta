package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingRowLifetimeTest {
    @Test fun liveAndUndrainedRevisionsStayPinnedButPauseAndExactCompletionRelease() {
        assertTrue(shouldKeepStreamingRow("text", true, false, "text"))
        assertTrue(shouldKeepStreamingRow("text", false, false, null))
        assertTrue(shouldKeepStreamingRow("text grows", false, false, "text"))
        assertFalse(shouldKeepStreamingRow("text", false, false, "text"))
        assertFalse(shouldKeepStreamingRow("text", true, true, null))
    }

    @Test fun reentryAndAppendRestoreComposedBlocksWithoutStoringAnyHeight() {
        val owner = ProgressiveMarkdownCompositionState()
        owner.acceptSource("prefix")
        val lengths = List(20) { 300 }
        assertEquals(1, owner.initialLimit(lengths, 480, true))
        owner.recordLimit(12)
        assertEquals(12, owner.initialLimit(lengths, 480, true))
        owner.acceptSource("prefix appended")
        owner.recordLimit(2) // A late old composition cannot rewind the owner.
        assertEquals(12, owner.initialLimit(lengths, 480, true))
        assertEquals(0, owner.generation)
    }

    @Test fun correctedSourceStartsANewGenerationAndBlockMergeClampsToCurrentDocument() {
        val owner = ProgressiveMarkdownCompositionState()
        owner.acceptSource("before")
        owner.recordLimit(12)
        assertEquals(3, owner.initialLimit(List(3) { 300 }, 480, true))
        owner.acceptSource("correction")
        assertEquals(1, owner.generation)
        assertEquals(1, owner.initialLimit(List(20) { 300 }, 480, true))
    }
    @Test fun terminalFrontierRejectsOldRevisionsAndRequiresEveryBlock() {
        val owner = ProgressiveMarkdownCompositionState()
        owner.acceptSource("first")
        owner.recordLimit(1, 20, "first")
        assertNull(owner.composedSource)
        owner.recordLimit(20, 20, "first")
        assertEquals("first", owner.composedSource)
        owner.acceptSource("first appended")
        assertNull(owner.composedSource)
        owner.recordLimit(20, 20, "first")
        assertNull(owner.composedSource)
        owner.recordLimit(21, 21, "first appended")
        assertEquals("first appended", owner.composedSource)
    }

    @Test fun sameSourceTerminalReparseMustConfirmItsLargerBlockFrontier() {
        val owner = ProgressiveMarkdownCompositionState()
        owner.acceptSource("same source")
        owner.recordLimit(1, 1, "same source")
        assertEquals("same source", owner.composedSource)
        val oldPublication = owner.publication
        owner.acceptSource("same source")
        assertNull(owner.composedSource)
        owner.recordLimit(1, 1, "same source", oldPublication)
        assertNull(owner.composedSource)
        owner.recordLimit(1, 18, "same source")
        assertNull(owner.composedSource)
        owner.recordLimit(18, 18, "same source")
        assertEquals("same source", owner.composedSource)
        assertEquals(0, owner.generation)
    }

}
