package io.github.mangi.eta.agent.device

import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayRecoveryPolicyTest {
    private val fresh = VirtualDisplayRecoveryPolicy.Flags(false, false, false, false, false)
    @Test fun freshOwnerMayHandoff() {
        assertEquals(VirtualDisplayRecoveryPolicy.Action.HANDOFF, VirtualDisplayRecoveryPolicy.finishAction(fresh))
    }
    @Test fun completedHandoffOnlyReleases() {
        assertEquals(VirtualDisplayRecoveryPolicy.Action.RELEASE_ONLY,
            VirtualDisplayRecoveryPolicy.finishAction(fresh.copy(finishing=true, handoffComplete=true, sourceEmpty=true)))
    }
    @Test fun unknownOrAttemptedMutationNeverReplays() {
        for (state in listOf(null, fresh.copy(finishing=true), fresh.copy(releaseAttempted=true),
            fresh.copy(mutationUncertain=true), fresh.copy(handoffComplete=true),
            fresh.copy(finishing=true, handoffComplete=true))) {
            assertEquals(VirtualDisplayRecoveryPolicy.Action.REFUSE, VirtualDisplayRecoveryPolicy.finishAction(state))
        }
    }
    @Test fun onlyClosedRecoverablePhasesMayResumeCleanup() {
        for (phase in listOf("active", "held", "uncertain")) {
            assertTrue(phase, VirtualDisplayRecoveryPolicy.canRecoverExistingRun(phase, true))
            assertFalse(phase, VirtualDisplayRecoveryPolicy.canRecoverExistingRun(phase, false))
        }
        for (phase in listOf("starting", "finishing", "finished", "unknown", "")) {
            assertFalse(phase, VirtualDisplayRecoveryPolicy.canRecoverExistingRun(phase, true))
            assertFalse(phase, VirtualDisplayRecoveryPolicy.canRecoverExistingRun(phase, false))
        }
    }
    @Test fun cleanupEligibilityNeverAuthorizesUncertainMutationReplay() {
        assertTrue(VirtualDisplayRecoveryPolicy.canRecoverExistingRun("uncertain", true))
        assertEquals(VirtualDisplayRecoveryPolicy.Action.REFUSE,
            VirtualDisplayRecoveryPolicy.finishAction(fresh.copy(mutationUncertain=true)))
        assertEquals(VirtualDisplayRecoveryPolicy.Action.REFUSE,
            VirtualDisplayRecoveryPolicy.finishAction(fresh.copy(releaseAttempted=true)))
    }
    @Test fun taskIdentityParsingIsStrict() {
        assertEquals(setOf(16,17), VirtualDisplayRecoveryPolicy.taskIds(listOf(16,17)))
        assertEquals(emptySet<Int>(), VirtualDisplayRecoveryPolicy.taskIds(emptyList<Int>()))
        for (raw in listOf(listOf(16,16), listOf(0), listOf(-1), listOf("16"), listOf(16L), listOf(null)))
            assertNull(VirtualDisplayRecoveryPolicy.taskIds(raw))
    }
}
