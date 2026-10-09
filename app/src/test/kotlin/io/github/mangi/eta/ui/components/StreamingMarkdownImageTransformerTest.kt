package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import io.github.mangi.eta.ui.markdown.nextStreamingSnapshot
import org.junit.Assert.*
import org.junit.Test

class StreamingMarkdownImageTransformerTest {
    private val parser = StreamingGfmParserSession()
    private fun snapshot(text: String, complete: Boolean = false) = parser.parse(text, complete)

    @Test fun plainContentReusesTheSameStatelessInstanceAcrossSnapshots() {
        val policy = StreamingMarkdownImageTransformerPolicy()
        val first = policy.forSnapshot(snapshot("Plain paragraph"))
        assertSame(first, policy.forSnapshot(snapshot("Plain paragraph\n\nTail grows")))
        assertSame(first, policy.forSnapshot(snapshot("Corrected **bold** and `code`")))
    }

    @Test fun samePublishedBracketSnapshotReusesTheInstance() {
        val policy = StreamingMarkdownImageTransformerPolicy()
        val published = snapshot("[label][ref]\n\n[ref]: https://example.test/one")
        val first = policy.forSnapshot(published)
        repeat(10) { assertSame(first, policy.forSnapshot(published)) }
    }

    @Test fun newSnapshotRefreshesIncludingStructurallyEqualCopy() {
        val policy = StreamingMarkdownImageTransformerPolicy()
        val published = snapshot("[label][ref]")
        val first = policy.forSnapshot(published)
        val copy = published.copy()
        assertEquals(published, copy)
        assertNotSame(published, copy)
        val next = policy.forSnapshot(copy)
        assertNotSame(first, next)
        assertSame(next, policy.forSnapshot(copy))
    }

    @Test fun laterReferenceDefinitionAndCorrectionKeepFreshPublicationValues() {
        val policy = StreamingMarkdownImageTransformerPolicy()
        val first = policy.forSnapshot(snapshot("[label][ref]\n\nTail"))
        val reference = policy.forSnapshot(snapshot("[label][ref]\n\nTail\n\n[ref]: https://example.test/one"))
        assertNotSame(first, reference)
        val changed = policy.forSnapshot(snapshot("[label][ref]\n\nTail\n\n[ref]: https://example.test/two"))
        assertNotSame(reference, changed)
        val removedSnapshot = snapshot("Correction without brackets")
        val removed = policy.forSnapshot(removedSnapshot)
        assertNotSame(changed, removed)
        assertSame(removed, policy.forSnapshot(removedSnapshot))
        assertNotSame(removed, policy.forSnapshot(snapshot("Correction without brackets")))
    }

    @Test fun sameTextTerminalPublicationStillRefreshes() {
        val policy = StreamingMarkdownImageTransformerPolicy()
        val text = "[label][ref]\n\n[ref]: https://example.test/one"
        val streaming = snapshot(text)
        val first = policy.forSnapshot(streaming)
        // No-spec publication can copy only the completion bit and reuse State.Success.
        val complete = nextStreamingSnapshot(streaming, snapshot(text, complete = true))!!
        assertSame(streaming.state, complete.state)
        assertEquals(streaming.state.content, complete.state.content)
        val terminal = policy.forSnapshot(complete)
        assertNotSame(first, terminal)
        assertSame(terminal, policy.forSnapshot(complete))
    }

    @Test fun eachDocumentHasItsOwnConservativeSyntaxHistory() {
        val old = StreamingMarkdownImageTransformerPolicy()
        old.forSnapshot(snapshot("![image][ref]"))
        val new = StreamingMarkdownImageTransformerPolicy()
        val first = new.forSnapshot(snapshot("No bracket in a new document"))
        assertSame(first, new.forSnapshot(snapshot("New tail")))
        assertNotSame(old.forSnapshot(snapshot("No bracket")), old.forSnapshot(snapshot("No bracket")))
    }

    @Test fun allBracketSyntaxRefreshesOnlyOnNewPublication() {
        listOf("[ref]", "[text][ref]", "[text][]", "[ref]: https://example.test",
            "![alt][ref]", "[inline](https://example.test)", "`code[index]`", "\\[escaped]")
            .forEach { text ->
                val policy = StreamingMarkdownImageTransformerPolicy()
                val published = snapshot(text, complete = true)
                val first = policy.forSnapshot(published)
                assertSame(text, first, policy.forSnapshot(published))
                assertNotSame(text, first, policy.forSnapshot(snapshot(text, complete = true)))
            }
    }
}
