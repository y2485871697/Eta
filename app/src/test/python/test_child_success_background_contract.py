import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
MAIN = ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent"
TEST = ROOT / "app/src/test/kotlin/io/github/mangi/eta/agent"


class ChildSuccessBackgroundContractTest(unittest.TestCase):
    def test_success_policy_does_not_add_freeze_or_erase_explicit_pause(self):
        policy = (MAIN / "runtime/AgentChildControlPolicy.kt").read_text()
        self.assertIn("fun shouldPauseChildren(reason: Reason): Boolean = reason != Reason.SUCCESS", policy)
        self.assertIn("run.paused = run.paused || shouldPauseChildren(reason)", policy)
        registered = policy.split("fun registered(", 1)[1].split("fun resume(", 1)[0]
        self.assertIn("if (!run.paused) return false", registered)
        self.assertNotIn("!run.paused && !run.terminated", registered)

    def test_runtime_success_only_seals_parent_and_preserves_detach_leases(self):
        control = (MAIN / "runtime/AgentChildRunControl.kt").read_text()
        terminal = control.split("fun terminate(", 1)[1].split("fun offerChildren(", 1)[0]
        self.assertRegex(terminal, re.compile(
            r"val targets = if \(AgentChildControlPolicy.shouldPauseChildren\(reason\)\) \{"
            r"\s*AgentChildTaskGroups.captureRunStopTargets\(session.runId\)"
            r"\s*\} else emptyList\(\)", re.S))
        self.assertNotIn("::resume", terminal)
        self.assertNotIn("::stop", terminal)
        executor = (MAIN / "runtime/AgentRuntimeRunExecutor.kt").read_text()
        self.assertIn("AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.SUCCESS)", executor)
        self.assertIn("registeredChildGenerations.forEach(AgentChildTaskGroups::detach)", executor)
        self.assertIn("checkNotNull(ownership.retain())", executor)

    def test_real_success_regression_completes_running_and_queued_without_resume(self):
        tests = (TEST / "runtime/ChildGroupLifecycleTest.kt").read_text()
        regression = tests.split("fun successfulParentEndAndDetachLetsRunningAndQueuedChildrenComplete()", 1)[1].split("@Test", 1)[0]
        for marker in ["modelParallelLimits = listOf(1)", "registry.onTaskChanged(generation)",
                       "AgentChildControlPolicy.Reason.SUCCESS", "registry.detach(generation)",
                       "session.complete(", "release.countDown()", "optBoolean(\"archived\")",
                       "await(f.released)", "assertEquals(2, calls.get())"]:
            self.assertIn(marker, regression)
        self.assertNotIn("continue_task", regression)
        self.assertNotIn("resumeGroup", regression)
        self.assertNotIn("registry.resume", regression)

    def test_manual_pause_and_non_success_controls_still_have_regressions(self):
        tests = (TEST / "runtime/ChildGroupLifecycleTest.kt").read_text()
        regression = tests.split("fun successfulParentEndDoesNotResumePreviouslyManuallyPausedChild()", 1)[1].split("@Test", 1)[0]
        self.assertIn('put("action", "pause")', regression)
        self.assertIn('assertEquals("manual", paused.getString("pause_source"))', regression)
        self.assertIn('assertEquals(0, paused.getInt("continuation_count"))', regression)
        policy_tests = (TEST / "runtime/AgentChildControlPolicyTest.kt").read_text()
        self.assertIn("onlyNormalSuccessLeavesChildrenRunningByDefault", policy_tests)
        self.assertIn("successfulCompletionDoesNotEraseAnEarlierExplicitPause", policy_tests)

    def test_prompt_reports_background_progress_without_claiming_completion(self):
        prompt = (MAIN / "model/AgentPromptBuilder.kt").read_text()
        tools = (MAIN / "delegation/SubAgentTools.kt").read_text()
        self.assertIn("已经派出的子任务默认继续在后台执行", prompt)
        self.assertIn("不会自动恢复此前已经暂停的任务", prompt)
        self.assertIn("不得宣称已完成或已验收", prompt)
        self.assertIn("凡依赖子任务结果的结论，仍必须取回结果并核对证据", prompt)
        self.assertIn("continue in the background after a normal parent final reply", tools)
        self.assertIn("Pending tasks are not completed or verified results", tools)


if __name__ == "__main__":
    unittest.main()
