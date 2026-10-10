package io.github.mangi.eta.ui.markdown

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Publication/target latch tests; real reveal/layout coverage lives in StreamingRowPinningTest. */
class DocumentStreamingStateTest {
    private suspend fun awaitSnapshot(state: DocumentStreamingState, source: String,
                                      previous: DocumentSnapshot? = null): DocumentSnapshot =
        withTimeout(5_000) {
            while (true) {
                val value = state.snapshot
                if (value != null && value !== previous && value.originalSource == source) return@withTimeout value
                delay(1)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    @Test fun oldCompletionCannotReleaseReopenedSameSourceOrCorrection() = runBlocking {
        val state = DocumentStreamingState()
        val style = MarkdownInlineStyle.Default
        val source = "[a]: https://example.com"
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            state.submit(source, false, style)
            val first = awaitSnapshot(state, source)
            assertTrue(first.document.revealKeys.isEmpty())
            state.acknowledgeLayout(first)
            state.markComplete(first)
            assertEquals(source, state.completedSourceFor(source, false, style))
            state.submit(source, true, style)
            assertNull(state.completedSourceFor(source, false, style))
            val streaming = awaitSnapshot(state, source, first)
            state.submit(source, false, style)
            state.markComplete(first)
            assertNull(state.completedSourceFor(source, false, style))
            val final = awaitSnapshot(state, source, streaming)
            state.markComplete(final)
            assertNull("No completion before this exact publication layout", state.completedRevealSource)
            state.acknowledgeLayout(first)
            assertNull(state.laidOutSnapshot)
            state.acknowledgeLayout(final)
            state.markComplete(final)
            assertEquals(source, state.completedSourceFor(source, false, style))
            state.submit("[b]: https://example.org", false, style)
            assertNull(state.completedSourceFor(source, false, style))
            state.submit(source, false, style)
            assertNull("Returning to an old source is not returning to its completed publication",
                state.completedSourceFor(source, false, style))
        } finally { worker.cancelAndJoin() }
    }

    @Test fun themeChangeCannotCompleteUsingOldPreparedDocument() = runBlocking {
        val state = DocumentStreamingState()
        val source = "[a]: https://example.com"
        val original = MarkdownInlineStyle.Default
        val next = original.copy(linkColor = androidx.compose.ui.graphics.Color.Red)
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            state.submit(source, false, original)
            val old = awaitSnapshot(state, source)
            state.acknowledgeLayout(old)
            state.markComplete(old)
            state.submit(source, false, next)
            state.markComplete(old)
            assertNull(state.completedSourceFor(source, false, next))
            val themed = awaitSnapshot(state, source, old)
            assertEquals(next, themed.document.inlineStyle)
            state.acknowledgeLayout(themed)
            state.markComplete(themed)
            assertEquals(source, state.completedSourceFor(source, false, next))
        } finally { worker.cancelAndJoin() }
    }

    @Test fun imagesRetainPreviewButAreNotPendingRevealRecords() {
        val document = DocumentGfmParserSession().parse("- ![a](https://example.com/a.png)", true).document
        val item = (document.blocks.single() as MarkdownList).items.single()
        assertTrue(item.containsImage)
        assertNull(item.markerRevealKey)
        assertTrue(document.revealKeys.isEmpty())
        assertTrue(listMarkerVisible(true, item.markerRevealKey, emptySet(), item.containsImage))
        assertFalse(listMarkerVisible(true, null, emptySet(), false))
        val mixed = DocumentGfmParserSession().parse(
            "- ![a](https://example.com/a.png)\n\n  Later caption", true).document
        val mixedItem = (mixed.blocks.single() as MarkdownList).items.single()
        assertTrue(mixedItem.containsImage)
        assertNull(mixedItem.markerRevealKey)
        assertTrue(listMarkerVisible(true, mixedItem.markerRevealKey, emptySet(), mixedItem.containsImage))
    }
}
