from pathlib import Path
import unittest

class VirtualDisplayGuardContractTest(unittest.TestCase):
    def test_geometry_is_read_live_and_rechecked_before_input(self):
        app=Path(__file__).resolve().parents[3]
        owner=(app/"src/main/java/vd/runtime/VirtualDisplayOwner.java").read_text()
        self.assertIn('out.put("width", geometry[0])',owner)
        self.assertIn('OwnerHandoff.number(info, "logicalWidth")',owner)
        self.assertIn('OwnerHandoff.number(info, "logicalHeight")',owner)
        self.assertIn('geometry[0] != created.width || geometry[1] != created.height',owner)
        session=(app/"src/main/kotlin/io/github/mangi/eta/agent/device/VirtualDisplaySession.kt").read_text()
        self.assertIn('flags(body(state)) ?: return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")',session)
        self.assertIn('setOf("RECOVERY_UNCERTAIN", "OWNER_STATE_UNKNOWN")',session)
