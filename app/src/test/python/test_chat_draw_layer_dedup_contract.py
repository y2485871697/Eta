"""Source wiring guards; runtime layer/height behavior is covered by RetainedCardDrawLayerTest.

These do not measure RenderThread/GPU frame times or replace on-device visual/selection checks.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/components'


class ChatDrawLayerDedupContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.chat = (SOURCE / 'ChatMessageItem.kt').read_text()
        cls.layer = cls.chat.split(
            'internal fun AnimatedVisibilityScope.retainDrawLayerWhenIdle(', 1
        )[1].split('internal fun tailDetailsExit(', 1)[0]
        cls.tool = cls.chat.split('private fun ToolActivityInline(', 1)[1].split(
            'private fun BrowserPagePreview(', 1
        )[0]

    def test_thinking_selection_uses_one_jointly_gated_layer(self):
        self.assertIn(
            'HapticSelectionContainer(\n'
            '                modifier = retainDrawLayerWhenIdle(enabled = streamingState == null)\n'
            '                    .toggleProbe(toggleProbeRef, "content"),',
            self.chat,
        )
        self.assertNotIn('completedContentDrawLayer(retainDrawLayerWhenIdle(', self.chat)
        self.assertIn('enabled: Boolean = true', self.layer)
        self.assertIn('transition.currentState == EnterExitState.Visible', self.layer)
        self.assertIn('transition.targetState == EnterExitState.Visible', self.layer)
        self.assertIn(
            'val cache = enabled && settled && heightPx in 1..MAX_RETAINED_LAYER_HEIGHT_PX',
            self.layer,
        )

    def test_single_layer_and_height_observer_are_unconditional(self):
        self.assertIn('private const val MAX_RETAINED_LAYER_HEIGHT_PX = 8192', self.chat)
        self.assertEqual(1, self.layer.count('.onSizeChanged { heightPx = it.height }'))
        self.assertEqual(1, self.layer.count('.graphicsLayer('))
        self.assertIn('return Modifier\n        .onSizeChanged', self.layer)
        self.assertIn('CompositingStrategy.Offscreen', self.layer)
        self.assertIn('CompositingStrategy.Auto', self.layer)
        self.assertNotIn('return if', self.layer)
        self.assertNotIn('.then(', self.layer)
        self.assertNotIn('graphicsLayer {', self.layer)  # keep the stable parameter-form element

    def test_tool_details_keep_squircle_but_not_an_outer_retention_layer(self):
        details = self.tool.split('visible = isExpanded && hasDetails,', 1)[1]
        self.assertNotIn('retainDrawLayerWhenIdle(', details)
        self.assertNotIn('completedContentDrawLayer(', details)
        self.assertIn(
            'modifier = Modifier\n'
            '                    .toggleProbe(toggleProbeRef, "content")\n'
            '                    .fillMaxWidth()\n'
            '                    .padding(start = 27.dp, top = 2.dp, bottom = 6.dp)\n'
            '                    .squircleSurface(\n'
            '                        color = MiuixTheme.colorScheme.surfaceContainer,\n'
            '                        cornerRadius = 10.dp,\n'
            '                    )\n'
            '                    .padding(horizontal = 12.dp, vertical = 10.dp),',
            details,
        )
        self.assertIn('HapticSelectionContainer {', details)
        self.assertIn('enter = tailDetailsEnter(anchorBottom)', self.tool)
        self.assertIn('exit = tailDetailsExit(anchorBottom)', self.tool)


if __name__ == '__main__':
    unittest.main()
