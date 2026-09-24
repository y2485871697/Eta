package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayHandoffRetryTest {
    private val code = VirtualDisplayHandoffRetry.ERROR_CODE
    private val focusDetail = "preflight:focus:IllegalStateException;moved=[] removed=[]"
    private val focusDetailSpaced = "preflight:focus:IllegalStateException; moved=[] removed=[]"

    private fun refused(c: String = code, d: String = focusDetail) =
        VirtualDisplayHandoffRetry.Attempt.Refused(c, d)

    private fun flags(
        finishing: Boolean = false,
        handoffComplete: Boolean = false,
        releaseAttempted: Boolean = false,
        mutationUncertain: Boolean = false,
    ) = VirtualDisplayRecoveryPolicy.Flags(finishing, handoffComplete, releaseAttempted, mutationUncertain, false)

    @Test fun onlyExactFocusPreflightWithEmptyTalliesIsRetryable() {
        assertTrue(VirtualDisplayHandoffRetry.isRetryable(code, focusDetail))
        assertTrue(VirtualDisplayHandoffRetry.isRetryable(code, focusDetailSpaced))
    }

    @Test fun otherCodesStagesAndNonEmptyTalliesAreNeverRetryable() {
        assertFalse(VirtualDisplayHandoffRetry.isRetryable("HANDOFF_UNCERTAIN", focusDetail))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable("OWNER_REQUEST_IO", ""))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable("OWNER_REQUEST_TIMEOUT", ""))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable(code, ""))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable(code, "preflight:display:IllegalStateException;moved=[] removed=[]"))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable(code, "preflight:selection:IllegalStateException;moved=[] removed=[]"))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable(code, "preflight:inventory:IllegalStateException;moved=[] removed=[]"))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable(code, "anchor:launch:IllegalStateException;moved=[] removed=[]"))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable(code, "preflight:focus:IllegalStateException;moved=[5] removed=[]"))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable(code, "preflight:focus:IllegalStateException;moved=[] removed=[9]"))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable(code, "preflight:focus:IllegalStateException"))
        assertFalse(VirtualDisplayHandoffRetry.isRetryable(code, "random garbage"))
    }

    @Test fun freshStateGateRequiresCleanUnfinishingOwnerWithFrozenSelection() {
        val frozen = setOf(16, 17)
        assertTrue(VirtualDisplayHandoffRetry.freshStateAllowsRetry(true, flags(), setOf(16, 17, 18), frozen))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(false, flags(), setOf(16, 17), frozen))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(true, null, setOf(16, 17), frozen))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(true, flags(), null, frozen))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(true, flags(finishing = true), setOf(16, 17), frozen))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(true, flags(handoffComplete = true), setOf(16, 17), frozen))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(true, flags(releaseAttempted = true), setOf(16, 17), frozen))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(true, flags(mutationUncertain = true), setOf(16, 17), frozen))
    }

    @Test fun selectionChangeRejectsRetryEvenWhenOwnerIsClean() {
        val frozen = setOf(16, 17)
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(true, flags(), setOf(16), frozen))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(true, flags(), emptySet(), frozen))
    }

    @Test fun delayEscalatesThenClamps() {
        assertEquals(300L, VirtualDisplayHandoffRetry.delayForRetry(0))
        assertEquals(700L, VirtualDisplayHandoffRetry.delayForRetry(1))
        assertEquals(700L, VirtualDisplayHandoffRetry.delayForRetry(9))
    }

    @Test fun budgetTracksRemainingAndNeverOverConsumes() {
        val budget = VirtualDisplayHandoffRetry.Budget(2)
        assertEquals(2, budget.remaining)
        assertTrue(budget.hasRemaining())
        budget.consume()
        budget.consume()
        assertEquals(0, budget.remaining)
        assertFalse(budget.hasRemaining())
        budget.consume()
        assertEquals(0, budget.remaining)
    }

    @Test fun resetYieldsAFreshBoundedBudgetForALaterRun() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        repeat(VirtualDisplayHandoffRetry.MAX_ATTEMPTS) { budget.consume() }
        assertEquals(0, budget.remaining)
        budget.reset()
        assertEquals(VirtualDisplayHandoffRetry.MAX_ATTEMPTS, budget.remaining)
    }

    @Test fun successOnFirstAttemptUsesNoDelayAndNoVerification() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        val delays = mutableListOf<Long>()
        var attempts = 0
        var verifyCalls = 0
        val outcome = VirtualDisplayHandoffRetry.run(
            budget,
            { attempts++; VirtualDisplayHandoffRetry.Attempt.Completed },
            { verifyCalls++; false },
            { delays.add(it) },
        )
        assertSame(VirtualDisplayHandoffRetry.Outcome.HandedOff, outcome)
        assertEquals(1, attempts)
        assertEquals(0, verifyCalls)
        assertTrue(delays.isEmpty())
        assertEquals(VirtualDisplayHandoffRetry.MAX_ATTEMPTS - 1, budget.remaining)
    }

    @Test fun focusPreflightFailureRetriesOnceThenSucceeds() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        val delays = mutableListOf<Long>()
        var attempts = 0
        var verifyCalls = 0
        val outcome = VirtualDisplayHandoffRetry.run(
            budget,
            {
                attempts++
                if (attempts == 1) refused() else VirtualDisplayHandoffRetry.Attempt.Completed
            },
            { verifyCalls++; true },
            { delays.add(it) },
        )
        assertSame(VirtualDisplayHandoffRetry.Outcome.HandedOff, outcome)
        assertEquals(2, attempts)
        assertEquals(listOf(300L), delays)
        assertEquals(1, verifyCalls)
    }

    @Test fun exhaustionStopsAtBoundAndReportsFirstFailure() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        val delays = mutableListOf<Long>()
        var attempts = 0
        val outcome = VirtualDisplayHandoffRetry.run(
            budget,
            { attempts++; refused() },
            { true },
            { delays.add(it) },
        ) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(VirtualDisplayHandoffRetry.MAX_ATTEMPTS, attempts)
        assertEquals(listOf(300L, 700L), delays)
        assertEquals(VirtualDisplayHandoffRetry.Failure(code, focusDetail), outcome.failure)
    }

    @Test fun freshStateRejectionStopsAfterDelayBeforeRetry() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        val delays = mutableListOf<Long>()
        var attempts = 0
        val outcome = VirtualDisplayHandoffRetry.run(
            budget,
            { attempts++; refused() },
            { false },
            { delays.add(it) },
        ) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(1, attempts)
        assertEquals(listOf(300L), delays)
        assertEquals(VirtualDisplayHandoffRetry.Failure(code, focusDetail), outcome.failure)
    }

    @Test fun interruptionStopsRetriesAndReinterruptsThread() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        var attempts = 0
        try {
            val outcome = VirtualDisplayHandoffRetry.run(
                budget,
                { attempts++; refused() },
                { true },
                { throw InterruptedException() },
            )
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, attempts)
            assertTrue(outcome is VirtualDisplayHandoffRetry.Outcome.Stopped)
            assertEquals(VirtualDisplayHandoffRetry.Failure(code, focusDetail),
                (outcome as VirtualDisplayHandoffRetry.Outcome.Stopped).failure)
        } finally {
            Thread.interrupted() // never leak the interrupt flag into other tests
        }
    }

    @Test fun uncertainMutationIsNeverReplayed() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        val delays = mutableListOf<Long>()
        var attempts = 0
        var verifyCalls = 0
        val outcome = VirtualDisplayHandoffRetry.run(
            budget,
            { attempts++; refused("HANDOFF_UNCERTAIN", "") },
            { verifyCalls++; true },
            { delays.add(it) },
        ) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(1, attempts)
        assertEquals(0, verifyCalls)
        assertTrue(delays.isEmpty())
        assertEquals(VirtualDisplayHandoffRetry.Failure("HANDOFF_UNCERTAIN", ""), outcome.failure)
    }

    @Test fun otherPreflightStageIsNotReplayed() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        var attempts = 0
        val outcome = VirtualDisplayHandoffRetry.run(
            budget,
            { attempts++; refused(code, "preflight:display:IllegalStateException;moved=[] removed=[]") },
            { true },
            { },
        ) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(1, attempts)
        assertEquals(VirtualDisplayHandoffRetry.Failure(code, "preflight:display:IllegalStateException;moved=[] removed=[]"),
            outcome.failure)
    }

    @Test fun sharedBudgetAcrossFinishAndRunClosedCannotMultiplyAttempts() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        val delays = mutableListOf<Long>()
        var attempts = 0
        fun drive() = VirtualDisplayHandoffRetry.run(
            budget,
            { attempts++; refused() },
            { true },
            { delays.add(it) },
        )
        val explicit = drive() as VirtualDisplayHandoffRetry.Outcome.Stopped
        val automatic = drive() as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(VirtualDisplayHandoffRetry.MAX_ATTEMPTS, attempts)
        assertEquals(listOf(300L, 700L), delays)
        assertEquals(VirtualDisplayHandoffRetry.Failure(code, focusDetail), explicit.failure)
        // The second (automatic) finish had no budget left: it runs no handoff and keeps the first
        // diagnostics instead of inventing a new, generic failure.
        assertNull(automatic.failure)
    }

    @Test fun exhaustedBudgetStopsBeforeAnyHandoff() {
        val budget = VirtualDisplayHandoffRetry.Budget(0)
        var attempts = 0
        val outcome = VirtualDisplayHandoffRetry.run(
            budget,
            { attempts++; refused() },
            { true },
            { },
        ) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(0, attempts)
        assertNull(outcome.failure)
    }

    @Test fun dangerousSecondFailureOverridesPreflightAndClosesBudget() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        var calls = 0
        val result = VirtualDisplayHandoffRetry.run(budget, {
            calls++
            if (calls == 1) refused() else refused("HANDOFF_UNCERTAIN", "anchor:launch:Exception;moved=[] removed=[]")
        }, { true }, {}) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals("HANDOFF_UNCERTAIN", result.failure!!.code)
        assertEquals(0, budget.remaining)
        VirtualDisplayHandoffRetry.run(budget, { calls++; refused() }, { true }, {})
        assertEquals(2, calls)
    }
    @Test fun stateIsCheckedAfterDelay() {
        var clean = true
        var calls = 0
        val budget = VirtualDisplayHandoffRetry.Budget()
        VirtualDisplayHandoffRetry.run(budget, { calls++; refused() }, { clean }, { clean = false })
        assertEquals(1, calls)
        assertEquals(0, budget.remaining)
    }
}
