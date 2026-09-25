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

class VirtualDisplayLaunchFlagContractTest(unittest.TestCase):
    def test_owner_launches_new_task_plus_multiple_task_without_new_document(self):
        app=Path(__file__).resolve().parents[3]
        owner=(app/"src/main/java/vd/runtime/VirtualDisplayOwner.java").read_text()
        policy=(app/"src/main/java/vd/runtime/LaunchPolicy.java").read_text()
        # The NEW_DOCUMENT bit is never set anywhere in the owner's launch path.
        self.assertNotIn("0x00080000",owner)
        # Exactly NEW_TASK | MULTIPLE_TASK; MULTIPLE_TASK is what skips the existing-task search.
        self.assertIn("static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;",policy)
        self.assertIn("static final int FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000;",policy)
        self.assertIn("FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK",policy)
        # NEW_DOCUMENT exists only as a named, never-set constant so a caller asking for it fails.
        self.assertIn("static final int FLAG_ACTIVITY_NEW_DOCUMENT = 0x00080000;",policy)
        # Explicit caller flags with any unsupported bit (incl. NEW_DOCUMENT) fail closed.
        self.assertIn("if ((callerFlags & ~LAUNCH_FLAGS) != 0)",policy)
        self.assertIn('throw new IllegalArgumentException("unsupported launch flags")',policy)
        # Random eta-vd://session/<uuid> marker is still appended as the final -d argument.
        self.assertIn('MARKER_PREFIX = "eta-vd://session/"',policy)
        self.assertIn("UUID.randomUUID()",policy)
        self.assertIn('argv[base.length] = "-d";',policy)
        self.assertIn('argv[base.length + 1] = marker;',policy)
        # A missing marker is refused; provenance is exact data URI + target package.
        self.assertIn("if (marker == null || marker.isEmpty())",policy)
        self.assertIn("marker.equals(baseDataString)",policy)
        self.assertIn("targetPackage.equals(baseComponentPackage)",policy)
        # The owner still requires an explicit component (action-only stays rejected) and uses the
        # marker/packet proof rather than same-package or task-number inference.
        self.assertIn("EXPLICIT_COMPONENT_REQUIRED",owner)
        self.assertIn("LaunchPolicy.provenanceMatches(marker,targetPackage,baseData,basePackage)",owner)
