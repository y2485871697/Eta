"""Source integration guards, not a substitute for compiled Kotlin tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class MainMergeIntegrationTest(unittest.TestCase):
    def test_material_recovery_retains_both_preview_capabilities(self):
        page = (ROOT / 'ui/VirtualDisplayRecoveryScreen.kt').read_text()
        for text in ('Scaffold(', 'VirtualDisplayWebPreview.open(context)', 'VirtualDisplayWebPreview.openWithManualClose(context)', 'onDispose { VirtualDisplayWebPreview.stop() }'):
            self.assertIn(text, page)
        self.assertEqual(2, page.count('check(stillInstalled)'))
        self.assertNotIn('TextButton(text =', page)

    def test_confirmed_empty_result_and_trailing_notice_paths_are_wired(self):
        live = (ROOT / 'ui/app/AgentAppState.kt').read_text()
        self.assertIn('result.ok && (result.content.isNotBlank() || VirtualCompletionNotice.confirmed(result))', live)
        recovery = (ROOT / 'ui/app/AgentPendingResultRecovery.kt').read_text()
        self.assertLess(recovery.index('VirtualCompletionNotice.confirmed(result) && partial != null'), recovery.index('result.ok -> SystemNoticeMessageUi('))
        body = (ROOT / 'ui/components/AgentChatBody.kt').read_text()
        self.assertIn('hasPendingAssistantReveal(currentVisibleMessages.value)', body)
        self.assertIn('retained != null && retained.revealedContent != message.content', body)

if __name__ == '__main__':
    unittest.main()
