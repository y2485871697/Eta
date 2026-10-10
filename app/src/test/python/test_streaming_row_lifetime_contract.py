import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'main/kotlin/io/github/mangi/eta/ui/components'


class StreamingRowLifetimeContract(unittest.TestCase):
    def test_live_row_pin_is_in_production_renderer_and_uses_exact_terminal_revision(self):
        body = (ROOT / 'ChatMessageItem.kt').read_text()
        renderer = body[body.index('private fun StreamingMarkdown('):body.index('private fun StreamingGfmSuccess(')]
        self.assertIn('KeepActiveStreamingRow(', renderer)
        self.assertIn('completedRevealSource = state.completedRevealSource', renderer)
        self.assertLess(renderer.index('revealCoordinator.drained.filter'),
                        renderer.index('state.completedRevealSource = currentContent'))
        helper = (ROOT / 'StreamingRowLifetime.kt').read_text()
        self.assertIn('container?.pin()', helper)
        self.assertIn('onDispose { handle?.release() }', helper)
        self.assertNotIn('scrollTo', helper)
        self.assertNotIn('invalidateMeasurement', helper)
        self.assertNotIn('scrollBy', helper)

    def test_composed_progress_is_owned_outside_the_disposable_document(self):
        body = (ROOT / 'ChatMessageItem.kt').read_text()
        self.assertIn('val compositionProgress = ProgressiveMarkdownCompositionState()', body)
        self.assertIn('state.compositionProgress.acceptSource(published.originalSource)', body)
        self.assertEqual(body.count('compositionProgress = compositionProgress,'), 2)
        self.assertIn('compositionProgress?.initialLimit(lengths, firstBudget, streamingReveal)', body)
        self.assertIn('compositionProgress?.recordLimit(limit, lengths.size, compositionSource, compositionPublication)', body)
        self.assertIn('snapshotFlow { compositionProgress.composedSource }', body)
        helper = (ROOT / 'StreamingRowLifetime.kt').read_text()
        self.assertIn('composedSource = null', helper)
        self.assertNotIn('if (next != source) composedSource', helper)

    def test_draw_cache_never_reuses_another_layout_or_state(self):
        body = (ROOT / 'SmoothTextReveal.kt').read_text()
        self.assertIn('cachedSettledLayout === layout', body)
        update = body[body.index('fun updateState('):body.index('fun onRevealDataChanged(')]
        self.assertIn('releaseSettledLayer()', update)
        self.assertNotIn('glyphLine', body)
