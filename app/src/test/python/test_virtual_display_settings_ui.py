import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
UI = ROOT / "src/main/kotlin/io/github/mangi/eta/ui"

class VirtualDisplaySettingsUiTest(unittest.TestCase):
    def test_recovery_is_only_nested_under_task_preferences(self):
        settings = (UI / "SettingsScreen.kt").read_text()
        task = (UI / "AgentTaskPreferenceScreen.kt").read_text()
        self.assertNotIn("VirtualDisplayRecoveryPreference", settings)
        self.assertNotIn("vd_recovery_title", settings)
        self.assertIn("if (taskBackendInstalled == true)", settings)
        self.assertIn("onClick = onOpenRecovery", task)
        self.assertIn("if (moduleInstalled != true)", task)

    def test_recovery_is_material_page_not_dialog(self):
        page = (UI / "VirtualDisplayRecoveryScreen.kt").read_text()
        self.assertIn("import androidx.compose.material3.*", page)
        self.assertIn("Scaffold(", page)
        self.assertIn("TopAppBar(", page)
        self.assertNotIn("WindowDialog", page)
        self.assertNotIn("AlertDialog", page)
        self.assertIn("LaunchedEffect(Unit) { refresh() }", page)
        self.assertIn('snapshot.optBoolean("recoverable")', page)
        self.assertIn("VirtualDisplaySession::recoverAndFinishManually", page)
        self.assertIn("VirtualDisplayWebPreview.stop()", page)
        root = (UI / "app/AgentAppRoot.kt").read_text()
        self.assertIn("entry<AppRoute.VirtualDisplayRecovery>", root)
        self.assertIn("VirtualDisplayRecoveryScreen(onBack = ::popRoute)", root)

    def test_installation_is_rechecked_on_resume_not_live_backend_status(self):
        source = (UI / "TaskBackendInstallation.kt").read_text()
        self.assertIn("Lifecycle.State.RESUMED", source)
        self.assertIn("value = null", source)
        self.assertIn("AgentTaskSurface.moduleInstalled()", source)
        self.assertNotIn("inspect_virtual_backend", source)
        self.assertIn("awaitCancellation()", source)

if __name__ == "__main__":
    unittest.main()
