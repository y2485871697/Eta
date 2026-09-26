"""Source-order guard only; runtime race coverage remains in Kotlin tests."""
import pathlib
import unittest

APP = pathlib.Path(__file__).resolve().parents[3]

class RuntimeAckSourceContractTest(unittest.TestCase):
    def test_ack_is_checked_before_encoding_and_again_before_upsert(self):
        source = (APP / 'src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeResultStore.kt').read_text()
        body = source[source.index('fun add('):source.index('fun list(')]
        self.assertIn('completedRun.result.runId.ifBlank { completedRun.handoff.id }', body)
        early = body.index('recentlyAcknowledgedRunIds.containsKey(stableRunId)')
        encode = body.index('completedRun.toEntity()')
        late = body.index('recentlyAcknowledgedRunIds.containsKey(entity.runId)')
        write = body.index('dao.upsertRuntimeResult(entity)')
        self.assertLess(early, encode)
        self.assertLess(encode, late)
        self.assertLess(late, write)
        self.assertEqual(2, body.count('synchronized(deliveryLock)'))

if __name__ == '__main__':
    unittest.main()
