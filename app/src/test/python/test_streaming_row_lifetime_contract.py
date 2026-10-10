import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'main/kotlin/io/github/mangi/eta/ui/components'


class StreamingRowLifetimeContract(unittest.TestCase):
    def test_live_row_pin_is_in_production_renderer_and_uses_exact_terminal_revision(self):
        body = (ROOT / 'ChatMessageItem.kt').read_text()
        renderer = (ROOT.parent / 'markdown/DocumentStreamingMarkdown.kt').read_text()
        self.assertIn('KeepActiveStreamingRow(shouldKeepStreamingRow(', renderer)
        self.assertIn('state.completedSourceFor(targetContent, parseAsStreaming, style.inline)', renderer)
        self.assertLess(renderer.index('reveal.drained.filter'),
                        renderer.index('state.markComplete(checkNotNull(target))'))
        self.assertIn('state.snapshot === target', renderer)
        self.assertIn('state.laidOutSnapshot', renderer)
        helper = (ROOT / 'StreamingRowLifetime.kt').read_text()
        self.assertIn('container?.pin()', helper)
        self.assertIn('onDispose { handle?.release() }', helper)
        self.assertNotIn('scrollTo', helper)
        self.assertNotIn('invalidateMeasurement', helper)
        self.assertNotIn('scrollBy', helper)

    def test_document_progress_is_owned_outside_the_disposable_row(self):
        body = (ROOT / 'ChatMessageItem.kt').read_text()
        self.assertIn('val documentState = io.github.mangi.eta.ui.markdown.DocumentStreamingState()', body)
        doc = (ROOT.parent / 'markdown/DocumentStreamingMarkdown.kt').read_text()
        self.assertIn('if (snapshot !== value) return', doc)
        self.assertIn('state.acknowledgeLayout(parsed)', doc)
        self.assertIn('snapshotFlow { state.laidOutSnapshot }.first { it === target }', doc)
        self.assertIn('hasLayoutsFor(checkNotNull(target).document.revealKeys)', doc)
        self.assertNotIn('480', doc)
        self.assertNotIn('nextProgressiveBlockLimit', doc)

    def test_draw_cache_never_reuses_another_layout_or_state(self):
        body = (ROOT / 'SmoothTextReveal.kt').read_text()
        self.assertIn('cachedSettledLayout === layout', body)
        update = body[body.index('fun updateState('):body.index('fun onRevealDataChanged(')]
        self.assertIn('releaseSettledLayer()', update)
        self.assertNotIn('glyphLine', body)
