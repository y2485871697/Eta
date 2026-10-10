"""Source wiring only; runtime estimates are covered by Kotlin tests in cloud CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class WireRequestEstimateContractTest(unittest.TestCase):
    def text(self, path):
        return (ROOT / path).read_text()

    def test_each_transport_uses_final_body_before_network(self):
        for name in ('OpenAiResponsesProvider', 'OpenAiChatCompletionsProvider', 'AnthropicMessagesProvider'):
            source = self.text(f'agent/model/{name}.kt')
            complete = source.split('override fun complete(', 1)[1].split('internal fun buildRequestJson(', 1)[0]
            self.assertEqual(1, complete.count('buildRequestJson('))
            self.assertEqual(1, complete.count('requestJson.toString()'))
            self.assertIn('AgentWireRequestEstimate.publish(requestJson, capabilities.endpoint,', complete)
            self.assertLess(complete.index('AgentWireRequestEstimate.publish('), complete.index('= readStreaming'))

    def test_display_and_silent_inputs_remain_separate(self):
        loop = self.text('agent/model/AgentLoop.kt')
        self.assertIn('silentBudget.requestStarted(requestLocal)', loop)
        self.assertIn('val publishLocalEstimate = requestBudget.consumeLocalBoundary()\n', loop)
        self.assertNotIn('consumeLocalBoundary() ||', loop)
        self.assertIn('silentBudget.tokens(localRequestTokens())', loop)
        estimate = loop.split('if (providerEvent is ProviderEvent.RequestEstimate)', 1)[1].split('if (providerEvent is ProviderEvent.Usage)', 1)[0]
        self.assertIn('projected = true', estimate)
        self.assertNotIn('silentBudget.measured(', estimate)
        app = self.text('ui/app/AgentAppState.kt')
        self.assertIn('if (projected) return', app)
        bar = self.text('ui/components/AgentChatInputBar.kt')
        # 显示不再消费任何本地预览 overhead/校准样本；只有 sendBudget（静默预算）保留 requestOverheadTokens。
        self.assertNotIn('previewRequestOverheadTokens', bar)
        self.assertNotIn('overheadCalibrationTokens', bar)
        self.assertIn('val contextSendBlocked = contextSendBlocked(measuredContextTokens, autoCompressEnabled)', bar)
        gate = self.text('ui/components/ContextSendBudgetGate.kt')
        self.assertIn('measuredContextTokens != null && !autoCompressEnabled', gate)
        send = bar.split('val sendBudget = remember(', 1)[1].split('val compressionSendBlocked', 1)[0]
        self.assertIn('localHistoryTokenCount = localHistoryTokenCount', send)
        self.assertIn('requestOverheadTokens = requestOverheadTokens', send)
        preview = self.text('ui/model/AgentModelPickerUiState.kt').split('internal fun compressionContextUsage(', 1)[1]
        silent = preview.split('private fun draftContextTokens(', 1)[0]
        self.assertNotIn('liveContextUsage(', silent)
        self.assertNotIn('projectedContextTokens?.', silent)
        self.assertIn('requestOverheadTokens.coerceAtLeast(0) + draft', silent)
        # Display-only calibration must never reach the silent send/compaction budget.
        self.assertNotIn('overheadCalibrationTokens', preview.split('\n}\n', 1)[0])

    def test_ring_consumes_only_the_cloud_receipt_and_screens_forward_the_measurement(self):
        bar = self.text('ui/components/AgentChatInputBar.kt')
        ring = bar.split('AgentContextUsageButton(', 1)[1].split(')', 1)[0]
        self.assertIn('usage = liveUsage,', ring)
        self.assertNotIn('else sendBudget', ring)
        for path in ('ui/screens/chat/AgentChatScreen.kt', 'ui/screens/home/AgentHomeScreen.kt'):
            screen = self.text(path)
            self.assertIn('measuredContextTokens: () -> Int? = { null },', screen)
            self.assertIn('measuredContextTokens = measuredContextTokens(),', screen)
            self.assertNotIn('overheadCalibrationTokens', screen)
            self.assertNotIn('previewRequestOverheadTokens', screen)

    def test_diagnostic_wire_bytes_reuse_serialized_request_without_payload_logging(self):
        for name in ('OpenAiResponsesProvider', 'OpenAiChatCompletionsProvider', 'AnthropicMessagesProvider'):
            code = self.text(f'agent/model/{name}.kt')
            publish = next(line for line in code.splitlines() if 'AgentWireRequestEstimate.publish(' in line)
            body_var = 'body' if name == 'OpenAiResponsesProvider' else 'requestBody'
            self.assertIn(f'{body_var}.contentLength()', publish)
            self.assertIn('val serializedBody = requestJson.toString()', code)
            self.assertIn(f'val {body_var} = serializedBody', code)
            self.assertIn('serializedBody)', publish)
            self.assertIn(f'.post({body_var})', code)

    def test_no_ciphertext_tokenizer_or_payload_logging(self):
        estimator = self.text('agent/model/AgentWireRequestEstimate.kt')
        self.assertIn('encrypted += item.optString("encrypted_content").length', estimator)
        self.assertNotIn('countTokens(body.toString())', estimator)
        self.assertNotIn('countTokens(item.optString("encrypted_content"))', estimator)
        self.assertNotIn('put("payload"', estimator)
        self.assertIn('AgentRequestContextDiagnostics.wireBodyFields(shape)', estimator)

if __name__ == '__main__':
    unittest.main()
