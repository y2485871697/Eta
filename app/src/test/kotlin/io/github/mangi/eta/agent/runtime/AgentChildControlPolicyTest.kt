package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChildControlPolicyTest {
    private fun policy(): AgentChildControlPolicy<String> {
        var id = 0
        return AgentChildControlPolicy { "event-${++id}" }
    }

    @Test fun pauseNeverPromptsAndSameParentResumesOnlyCapturedGroups() {
        val policy = policy()
        val parent = Any()
        policy.begin(parent, "run")
        assertEquals(listOf("old-generation"), policy.pause(parent, listOf("old-generation")))
        assertTrue(policy.pending.isEmpty())
        assertEquals(listOf("old-generation"), policy.resume(parent))
        assertTrue(policy.resume(parent).isEmpty())
    }

    @Test fun lateRegistrationInheritsPauseAndCanResumeWithSameParent() {
        val policy = policy()
        val parent = Any()
        policy.begin(parent, "run")
        policy.pause(parent, emptyList())
        assertTrue(policy.registered(parent, "late-generation"))
        assertTrue(policy.pending.isEmpty())
        assertEquals(listOf("late-generation"), policy.resume(parent))
    }

    @Test fun terminationReasonsOfferOnlyWithUnfinishedChildren() {
        for (reason in AgentChildControlPolicy.Reason.values()) {
            val policy = policy()
            val parent = Any()
            policy.begin(parent, "run")
            val offered = policy.terminate(parent, reason, listOf("old"), hasUnfinished = true)
            val shouldOffer = reason in setOf(
                AgentChildControlPolicy.Reason.USER_STOP,
                AgentChildControlPolicy.Reason.SETTINGS_CHANGED,
                AgentChildControlPolicy.Reason.FINAL_NETWORK_FAILURE,
            )
            assertEquals(reason.name, shouldOffer, offered != null)
            assertTrue(policy.resume(parent).isEmpty())
        }
    }

    @Test fun emptyOrFinishedGroupsDoNotPrompt() {
        val policy = policy()
        val noTasks = Any()
        val completedTasks = Any()
        policy.begin(noTasks, "empty")
        policy.begin(completedTasks, "done")
        assertNull(policy.terminate(noTasks, AgentChildControlPolicy.Reason.USER_STOP, emptyList(), true))
        assertNull(policy.terminate(completedTasks, AgentChildControlPolicy.Reason.FINAL_NETWORK_FAILURE, listOf("done"), false))
        assertTrue(policy.pending.isEmpty())
    }

    @Test fun dismissIsOneShotAndDoesNotResumeOrReofferSettingsChange() {
        val policy = policy()
        val parent = Any()
        var settingsApplications = 0
        policy.begin(parent, "run")
        settingsApplications++ // the settings owner applies immediately; policy has no settings callback
        val selection = policy.terminate(parent, AgentChildControlPolicy.Reason.SETTINGS_CHANGED, listOf("old"), true)!!
        assertNotNull(policy.resolve(selection.eventId))
        assertNull(policy.resolve(selection.eventId))
        assertTrue(policy.resume(parent).isEmpty())
        assertNull(policy.terminate(parent, AgentChildControlPolicy.Reason.FINAL_NETWORK_FAILURE, listOf("old"), true))
        assertTrue(policy.pending.isEmpty())
        assertEquals(1, settingsApplications)
    }

    @Test fun noUiRetainsChoiceAfterParentFinishesAndNewTurnStarts() {
        val policy = policy()
        val oldParent = Any()
        val newParent = Any()
        policy.begin(oldParent, "old-run")
        val selection = policy.terminate(oldParent, AgentChildControlPolicy.Reason.USER_STOP, listOf("old"), true)!!
        policy.finish(oldParent)
        policy.begin(newParent, "new-run")
        assertEquals(listOf(selection), policy.pending)
        assertEquals(listOf("old"), policy.resolve(selection.eventId)?.targets)
        assertFalse(policy.registered(newParent, "new"))
    }

    @Test fun identicalRunIdDoesNotGiveStaleCallbackNewGeneration() {
        val policy = policy()
        val oldParent = Any()
        val newParent = Any()
        policy.begin(oldParent, "reused-run-id")
        val old = policy.terminate(oldParent, AgentChildControlPolicy.Reason.USER_STOP, listOf("old"), true)!!
        policy.begin(newParent, "reused-run-id")
        val newer = policy.terminate(newParent, AgentChildControlPolicy.Reason.USER_STOP, listOf("new"), true)!!
        assertEquals(listOf("old"), policy.resolve(old.eventId)?.targets)
        assertNull(policy.resolve(old.eventId))
        assertEquals(listOf(newer), policy.pending)
    }

    @Test fun targetListIsCopiedAndTerminalLateRegistrationCannotReopenChoice() {
        val policy = policy()
        val parent = Any()
        policy.begin(parent, "run")
        val targets = mutableListOf("old")
        val selection = policy.terminate(parent, AgentChildControlPolicy.Reason.USER_STOP, targets, true)!!
        targets += "new"
        assertEquals(listOf("old"), selection.targets)
        assertTrue(policy.registered(parent, "late"))
        assertEquals(listOf(selection), policy.pending)
        assertTrue(policy.resume(parent).isEmpty())
    }

    @Test fun successfulCompletionNeverOffersChoice() {
        val policy = policy()
        val parent = Any()
        policy.begin(parent, "run")
        assertNull(policy.terminate(parent, AgentChildControlPolicy.Reason.SUCCESS, listOf("old"), true))
        assertTrue(policy.pending.isEmpty())
        assertFalse(policy.registered(parent, "late"))
        assertFalse(policy.isControllable(parent))
        assertTrue(policy.resume(parent).isEmpty())
    }

    @Test fun onlyNormalSuccessLeavesChildrenRunningByDefault() {
        AgentChildControlPolicy.Reason.values().forEach { reason ->
            assertEquals(reason.name, reason != AgentChildControlPolicy.Reason.SUCCESS,
                AgentChildControlPolicy.shouldPauseChildren(reason))
        }
    }

    @Test fun successfulCompletionDoesNotEraseAnEarlierExplicitPause() {
        val policy = policy()
        val parent = Any()
        policy.begin(parent, "run")
        policy.pause(parent, listOf("manual"))
        assertNull(policy.terminate(parent, AgentChildControlPolicy.Reason.SUCCESS, listOf("manual"), true))
        assertTrue(policy.registered(parent, "late"))
        assertTrue(policy.resume(parent).isEmpty())
        assertTrue(policy.pending.isEmpty())
    }
}
