"""Selective upstream mechanism port: unchanged UI semantics and off-main preparation."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class PreparedMarkdownContract(unittest.TestCase):
    def test_default_worker_uses_captured_spec_and_original_publish_pacing(self):
        text = (ROOT / 'ui/components/ChatMessageItem.kt').read_text()
        self.assertIn('LaunchedEffect(content, parseAsStreaming, renderSpec)', text)
        worker = text[text.index('val parsed = withContext(Dispatchers.Default)'):text.index('val newerTarget = parseTargets.tryReceive().getOrNull()', text.index('val parsed = withContext(Dispatchers.Default)'))]
        self.assertIn('renderSpec = target.renderSpec', worker)
        for token in ('STREAMING_PARSE_PUBLISH_INTERVAL_MS = 90L', 'delay(STREAMING_PARSE_PUBLISH_INTERVAL_MS)', 'parseAsStreaming = isStreaming || isPaused', 'StreamingHaptics.onVisibleAdvance(view)', 'completeLayout(', 'onRevealCompleteChange'):
            self.assertIn(token, text)

    def test_prepared_inputs_are_safe_and_original_renderer_remains_fallback(self):
        helper = (ROOT / 'ui/markdown/PreparedMarkdownRender.kt').read_text()
        for token in ('markdownRenderCacheKey(source, node)', 'it.cacheKey == key && it.spec == spec', "'[' in source.substring", 'Collections.unmodifiableMap(output)', 'maxRetainedSourceChars: Int = 262144', 'DefaultAnnotatorSettings(spec.links, spec.inlineCode, spec.annotator, null, spec.listener)'):
            self.assertIn(token, helper)
        for forbidden in ('@Stable', '@Immutable', 'TextLayoutResult', 'Usage', 'LocalContext', 'LaunchedEffect'):
            self.assertNotIn(forbidden, helper)
        ui = (ROOT / 'ui/components/ChatMessageItem.kt').read_text()
        for token in ('preparedBlocks = parsed.preparedBlocks.takeIf { parsed.renderSpec == renderSpec }', 'remember(blockSource, preparedBlock?.spec) { Triple(node, content, preparedBlock) }', 'it.node === frozenNode && it.source === frozenContent', 'MarkdownElement(', 'ChatSelectableText(text = text, style = style, modifier = modifier)', 'rememberStreamingMarkdownImageTransformer(parsed)'):
            self.assertIn(token, ui)
        self.assertEqual(3, ui.count('val text = prepared ?: remember('))

    def test_correction_and_terminal_reset_do_not_mutate_published_blocks(self):
        parser = (ROOT / 'ui/markdown/StreamingGfmParser.kt').read_text()
        self.assertIn('if (!source.startsWith(acceptedSource))', parser)
        self.assertIn('if (previousComplete != isComplete) preparedSession.clear()', parser)
        self.assertIn('preparedBlocks = preparedSession.prepare(root, renderedSource, renderSpec)', parser)
        helper = (ROOT / 'ui/markdown/PreparedMarkdownRender.kt').read_text()
        self.assertIn('previous = next', helper)
        self.assertNotIn('previous.clear()', helper)
        diag = (ROOT / 'ui/components/BoundedStreamDiagnostics.kt').read_text()
        self.assertIn('"markdown.prepared.annotated"', diag)
        self.assertIn('"markdown.prepared.raw"', diag)
