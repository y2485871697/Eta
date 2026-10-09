"""Pin only existing frozen renderer inputs; do not alter rendering or reveal policy."""
from pathlib import Path
import unittest
from test_agent_chat_viewport_contract import balanced_end

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class FrozenMarkdownInputContract(unittest.TestCase):
    def test_pinning_is_inside_the_existing_block_key_with_one_renderer_call(self):
        text = (ROOT / 'ui/components/ChatMessageItem.kt').read_text()
        start = text.index('key(node.startOffset, node.type.name) {')
        opening = text.index('{', start)
        end = balanced_end(text, opening, '{', '}')
        body = text[opening:end]
        for token in (
            'val freeze = revealCoordinator != null &&',
            'shouldFreezeStreamingMarkdownBlock(node.startOffset, lastVisibleStartOffset)',
            'rememberFrozenMarkdownInput(node, freeze)',
            'rememberFrozenMarkdownInput(content, freeze)',
            'node = renderNode', 'content = renderContent',
            'components = components', 'freeze = freeze',
        ):
            self.assertIn(token, body)
        self.assertEqual(1, body.count('FrozenMarkdownElement('))
        self.assertNotIn('rememberFrozenMarkdownInput(components', text)
        self.assertNotIn('substring', body)

    def test_capture_is_conditional_and_released_on_unfreeze(self):
        helper = (ROOT / 'ui/components/FrozenMarkdownInput.kt').read_text()
        self.assertIn('if (freeze) {\n        remember { value }\n    } else {\n        value', helper)
        for token in ('LaunchedEffect', 'remember(value', 'mutableStateOf', 'rememberSaveable'):
            self.assertNotIn(token, helper)

    def test_only_streaming_host_supplies_the_conservative_transformer(self):
        text = (ROOT / 'ui/components/ChatMessageItem.kt').read_text()
        self.assertEqual(1, text.count('rememberStreamingMarkdownImageTransformer(parsed)'))
        start = text.index('private fun StreamingMarkdown(')
        end = text.index('private fun StreamingGfmSuccess(', start)
        body = text[start:end]
        self.assertIn('val imageTransformer = rememberStreamingMarkdownImageTransformer(parsed)', body)
        self.assertIn('imageTransformer = imageTransformer,', body)
        helper = (ROOT / 'ui/components/StreamingMarkdownImageTransformer.kt').read_text()
        for token in ('private var bracketSyntaxSeen = false', "'[' in snapshot.state.content", 'if (bracketSyntaxSeen) NoOpImageTransformerImpl() else retained', 'remember { StreamingMarkdownImageTransformerPolicy() }'):
            self.assertIn(token, helper)
        self.assertIn('if (lastSnapshot === snapshot) return lastTransformer', helper)
        self.assertIn('return policy.forSnapshot(snapshot)', helper)
        self.assertNotIn('remember(snapshot', helper)
        for token in ('ReferenceLinkHandlerImpl', 'parseMarkdown(', 'LocalMarkdown', 'LaunchedEffect', 'mutableStateOf'):
            self.assertNotIn(token, helper)

    def test_frozen_renderer_keeps_a_single_markdown_element_call_site(self):
        text = (ROOT / 'ui/components/ChatMessageItem.kt').read_text()
        start = text.index('private fun FrozenMarkdownElement')
        end = text.index('internal fun shouldFreezeStreamingMarkdownBlock', start)
        body = text[start:end]
        renderer = body[body.index('val pinned'):]
        self.assertEqual(1, renderer.count('MarkdownElement('))
        self.assertIn('if (freeze) remember(blockSource, preparedBlock?.spec) { Triple(node, content, preparedBlock) } else null', renderer)
        self.assertNotIn('remember(preparedBlock)', body)
        self.assertIn('pinned?.third?.takeIf { it.node === frozenNode && it.source === frozenContent }', renderer)
        self.assertEqual(1, renderer.count('CompositionLocalProvider(LocalPreparedMarkdownBlock provides providedBlock)'))
        self.assertNotIn('if (freeze) {\n            val frozenNode', text)

    def test_freeze_entry_completes_in_block_reveal_without_a_second_renderer(self):
        text = (ROOT / 'ui/components/ChatMessageItem.kt').read_text()
        start = text.index('key(node.startOffset, node.type.name) {')
        opening = text.index('{', start)
        from test_agent_chat_viewport_contract import balanced_end
        end = balanced_end(text, opening, '{', '}')
        body = text[opening:end]
        self.assertIn('remember(freeze)', body)
        self.assertIn('revealCoordinator?.completeAttachedRecordsIn(node.startOffset, node.endOffset)', body)
        self.assertEqual(1, body.count('FrozenMarkdownElement('))
