"""Source wiring checks; runtime behavior is covered by AgentRecoveryContextTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / "src/main/kotlin/io/github/mangi/eta"

class RecoveryDispatchScopeContractTest(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text()

    def test_new_user_run_normalizes_only_historical_recovery(self):
        builder = self.read("agent/model/AgentPromptBuilder.kt")
        self.assertIn("?.let(AgentRecoveryContext::forNewRun)?.let(messages::put)", builder)
        self.assertLess(builder.index("AgentRecoveryContext::forNewRun"),
                        builder.index("messages.put(AgentConversationCodec.userMessage(prompt, images))"))
        for path in ("agent/model/AgentLoop.kt", "agent/model/AgentModelRetry.kt"):
            self.assertNotIn("forNewRun", self.read(path))

    def test_active_run_keeps_transport_restriction_and_replay_guard(self):
        loop = self.read("agent/model/AgentLoop.kt")
        self.assertIn("private var localOnlyRecovery = AgentRecoveryContext.isActive(messages)", loop)
        self.assertIn("validateResponse = toolReplayGuard::validate", loop)
        self.assertIn("localOnlyRecovery = localOnlyRecovery || completedRound.reconnectLocalToolsOnly", loop)
        self.assertIn("if (localOnlyRecovery) next.copy(hostedWebSearchEnabled = false)", loop)
        payload = self.read("agent/model/AgentProvider.kt")
        self.assertIn('"previous_response_id", "conversation"', payload)
        self.assertIn('functions.put(JSONObject(item.toString()))', payload)

    def test_task_ownership_and_replacement_routing_are_unchanged(self):
        groups = self.read("agent/runtime/AgentChildTaskGroups.kt")
        self.assertIn('if (wantsReplacement) return replace(candidates, current, call, args)', groups)
        self.assertIn('val group = if (id.isNotBlank()) candidates.firstOrNull { owns(it, id) } else current', groups)
        self.assertIn('currentRunId == null || it.runId == currentRunId', groups)

if __name__ == "__main__":
    unittest.main()
