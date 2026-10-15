"""Service wiring guards; worker and persistence races are exercised by Kotlin tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent/runtime"


class RuntimeResultDispatchContract(unittest.TestCase):
    def test_drain_and_ack_leave_the_main_handler(self):
        service = (SRC / "AgentRuntimeService.kt").read_text()
        ack = service.split("AgentRuntimeWire.MSG_ACK_RESULT -> {", 1)[1].split(
            "AgentRuntimeWire.MSG_DRAIN_RESULTS ->", 1)[0]
        self.assertLess(ack.index("resultWorker.submit {"), ack.index("AgentRuntimeResultStore.remove("))
        self.assertLess(ack.index("AgentRuntimeResultStore.remove("), ack.index("releaseResultTranscript("))
        drain = service.split("private fun sendDrainedResults(", 1)[1].split("private fun sendActiveRun(", 1)[0]
        self.assertLess(drain.index("if (replyTo == null) return"), drain.index("resultWorker.submit {"))
        self.assertLess(drain.index("resultWorker.submit {"), drain.index("AgentRuntimeResultStore.list("))
        self.assertLess(drain.index("AgentRuntimeResultStore.list("), drain.index("completedRunsToBundle("))
        self.assertIn("resultWorker.close()", service.split("override fun onDestroy()", 1)[1])

    def test_worker_retains_accepted_ack_on_service_destruction(self):
        worker = (SRC / "AgentRuntimeResultWorker.kt").read_text()
        self.assertIn("Executors.newSingleThreadExecutor", worker)
        self.assertIn("executor.shutdown()", worker)
        self.assertNotIn("shutdownNow", worker)
        store = (SRC / "AgentRuntimeResultStore.kt").read_text()
        self.assertIn("if (pruned.size != stored.size) dao.replaceRuntimeResults(pruned)", store)


if __name__ == "__main__":
    unittest.main()
