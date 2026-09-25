package io.github.mangi.eta.agent.device

import org.junit.Assert.*
import org.junit.Test

/** Pure, bounded fixtures: no Android, sockets, sleeps or owner process. */
class VirtualDisplayHandoffRetryTest {
    private val code = VirtualDisplayHandoffRetry.ERROR_CODE
    private val detail = "preflight:focus:Focus_BINDER_CHANGED;moved=[] removed=[]"
    private val identity = VirtualDisplayHandoffRetry.OwnerIdentity("owner-socket", 123L, 2, "owner-unique")
    private val flags = VirtualDisplayRecoveryPolicy.Flags(false, false, false, false, false)
    private val state = VirtualDisplayHandoffRetry.OwnerState(identity, true, flags, setOf(16, 17, 18), 3)
    private val selected = setOf(16, 17)

    private fun refused(c: String = code, d: String = detail, authenticated: Boolean = true) =
        VirtualDisplayHandoffRetry.Attempt.Refused(c, d, authenticated)

    @Test fun onlyExactFocusPreflightWithStrictlyEmptyTalliesIsRetryable() {
        assertTrue(VirtualDisplayHandoffRetry.isRetryable(code, detail))
        assertTrue(VirtualDisplayHandoffRetry.isRetryable(code, detail.replace(";", "; ")))
        assertTrue(VirtualDisplayHandoffRetry.isRetryable(code,
            "preflight:focus:IllegalStateException;moved=[] removed=[]"))
        for (bad in listOf(
            "", "random garbage", "$detail\n", " $detail", "$detail extra",
            detail.replace("moved=[]", "moved=[16]"), detail.replace("removed=[]", "removed=[18]"),
            detail.replace("moved=[]", "moved=[ ]"), detail.replace("removed=[]", "removed=[ ]"),
            detail.replace(";", ";\t"), detail.replace(";", ";  "),
            detail.replace("preflight:focus", "preflight:display"),
            detail.replace("preflight:focus", "preflight:selection"),
            detail.replace("preflight:focus", "preflight:inventory"),
            detail.replace("preflight:focus", "anchor:launch"),
            "preflight:focus:IllegalStateException",
        )) assertFalse(bad, VirtualDisplayHandoffRetry.isRetryable(code, bad))
        for (badCode in listOf("HANDOFF_UNCERTAIN", "OWNER_REQUEST_IO", "OWNER_REQUEST_TIMEOUT", ""))
            assertFalse(badCode, VirtualDisplayHandoffRetry.isRetryable(badCode, detail))
    }

    @Test fun freshStateRequiresAuthenticatedUnchangedOwnerAndCompleteInventory() {
        assertTrue(VirtualDisplayHandoffRetry.freshStateAllowsRetry(state, state.copy(), selected))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(null, state, selected))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(state, null, selected))
        for (changed in listOf(
            state.copy(authenticated = false),
            state.copy(identity = identity.copy(socketName = "another")),
            state.copy(identity = identity.copy(pid = 124L)),
            state.copy(identity = identity.copy(displayId = 3)),
            state.copy(identity = identity.copy(uniqueId = "another")),
            state.copy(retainedTaskIds = setOf(16, 17)), // Even an unselected task matters.
            state.copy(retainedTaskIds = setOf(16, 17, 19)),
            state.copy(retainedTaskIds = setOf(16, 17, 18, 19), sourceTaskCount = 4),
            state.copy(sourceTaskCount = 4),
        )) {
            assertFalse(changed.toString(), VirtualDisplayHandoffRetry.freshStateAllowsRetry(state, changed, selected))
            assertFalse(changed.toString(), VirtualDisplayHandoffRetry.freshStateAllowsRetry(changed, state, selected))
        }
    }

    @Test fun unchangedButInvalidOrMutatedStateNeverAuthorizesRetry() {
        for (bad in listOf(
            state.copy(authenticated = false),
            state.copy(identity = identity.copy(socketName = " ")),
            state.copy(identity = identity.copy(pid = 0)),
            state.copy(identity = identity.copy(displayId = 0)),
            state.copy(identity = identity.copy(uniqueId = "")),
            state.copy(flags = flags.copy(finishing = true)),
            state.copy(flags = flags.copy(handoffComplete = true)),
            state.copy(flags = flags.copy(releaseAttempted = true)),
            state.copy(flags = flags.copy(mutationUncertain = true)),
            state.copy(flags = flags.copy(sourceEmpty = true)),
            state.copy(sourceTaskCount = -1), state.copy(sourceTaskCount = 2),
            state.copy(retainedTaskIds = setOf(0, 16, 17)),
            state.copy(retainedTaskIds = setOf(16)),
        )) assertFalse(bad.toString(), VirtualDisplayHandoffRetry.freshStateAllowsRetry(bad, bad, selected))
        for (badSelection in listOf(emptySet(), setOf(0), setOf(-1), setOf(16, 19)))
            assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(state, state, badSelection))
    }

    @Test fun budgetAndDelaysAreBoundedEvenWithAnOversizedLimit() {
        assertEquals(3, VirtualDisplayHandoffRetry.MAX_ATTEMPTS)
        assertEquals(300L, VirtualDisplayHandoffRetry.delayForRetry(-1))
        assertEquals(300L, VirtualDisplayHandoffRetry.delayForRetry(0))
        assertEquals(700L, VirtualDisplayHandoffRetry.delayForRetry(1))
        assertEquals(700L, VirtualDisplayHandoffRetry.delayForRetry(9))
        val budget = VirtualDisplayHandoffRetry.Budget(100)
        assertEquals(3, budget.remaining)
        repeat(5) { budget.consume() }
        assertEquals(0, budget.remaining)
        assertFalse(budget.hasRemaining())
        budget.reset() // Only an explicit later round may call this.
        assertEquals(3, budget.remaining)
        budget.stop()
        budget.reset()
        assertTrue(budget.blocked)
        assertEquals(0, budget.remaining)
    }

    @Test fun successClosesBudgetWithoutDelayOrPreflightVerification() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        var calls = 0
        val outcome = VirtualDisplayHandoffRetry.run(budget,
            { calls++; VirtualDisplayHandoffRetry.Attempt.Completed },
            { error("success must not be classified as preflight") },
            { error("unexpected delay") })
        assertSame(VirtualDisplayHandoffRetry.Outcome.HandedOff, outcome)
        budget.reset()
        VirtualDisplayHandoffRetry.run(budget, { calls++; refused() }, { true }, {})
        assertEquals(1, calls)
        assertTrue(budget.blocked)
    }

    @Test fun cleanRefusalThenSuccessRevalidatesAfterDelayBeforeSecondAttempt() {
        val events = mutableListOf<String>()
        var calls = 0
        val result = VirtualDisplayHandoffRetry.run(VirtualDisplayHandoffRetry.Budget(), {
            events.add("handoff")
            if (++calls == 1) refused() else VirtualDisplayHandoffRetry.Attempt.Completed
        }, { events.add("status"); true }, { events.add("delay:$it") })
        assertSame(VirtualDisplayHandoffRetry.Outcome.HandedOff, result)
        assertEquals(listOf("handoff", "delay:300", "status", "handoff"), events)
    }

    @Test fun cleanExhaustionChecksFinalStatusAndStaysPendingWithOriginalDiagnostic() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        val events = mutableListOf<String>()
        var calls = 0
        val result = VirtualDisplayHandoffRetry.run(budget, {
            events.add("handoff")
            if (++calls == 1) refused() else refused(d = detail.replace("BINDER_CHANGED", "FOCUS_UNSTABLE"))
        }, { events.add("status"); true }, { events.add("delay:$it") }) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(3, calls)
        assertEquals(listOf("handoff", "delay:300", "status", "handoff", "delay:700", "status", "handoff", "status"), events)
        assertEquals(VirtualDisplayHandoffRetry.Failure(code, detail), result.failure)
        assertTrue(result.cleanPreflight)
        assertEquals("handoff_pending", result.phase)
        assertFalse(budget.blocked)
    }

    @Test fun thirdStatusFailureIsUncertainAndCannotBeResetOrReplayed() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        var calls = 0
        var reads = 0
        val result = VirtualDisplayHandoffRetry.run(budget, { calls++; refused() },
            { ++reads < 3 }, {}) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(3, reads)
        assertFalse(result.cleanPreflight)
        assertEquals("uncertain", result.phase)
        budget.reset()
        VirtualDisplayHandoffRetry.run(budget, { calls++; refused() }, { true }, {})
        assertEquals(3, calls)
        assertTrue(budget.blocked)
    }

    @Test fun sharedBudgetAcrossFinishAndRunClosedCannotMultiplyAttempts() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        var calls = 0
        var reads = 0
        fun drive() = VirtualDisplayHandoffRetry.run(budget, { calls++; refused() }, { reads++; true }, {})
            as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals("handoff_pending", drive().phase)
        repeat(4) {
            // Session revalidates its stored baseline and preserves the pending receipt on reentry;
            // the exhausted runner alone cannot assert clean state or authorize more mutation.
            assertNull(drive().failure)
        }
        assertEquals(3, calls)
        assertEquals(3, reads)
        budget.reset() // Deliberate new round, not repeat finish/onRunClosed.
        assertEquals("handoff_pending", drive().phase)
        assertEquals(6, calls)
        assertEquals(6, reads)
    }

    @Test fun zeroBudgetCannotInventCleanPreflightEvidence() {
        val result = VirtualDisplayHandoffRetry.run(VirtualDisplayHandoffRetry.Budget(0),
            { error("no handoff") }, { error("no status") }, { error("no delay") })
            as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertNull(result.failure)
        assertFalse(result.cleanPreflight)
    }

    @Test fun unauthenticatedMalformedAndAttemptedMutationsNeverRetry() {
        for (attempt in listOf(
            VirtualDisplayHandoffRetry.Attempt.Refused(code, detail), // Fail-closed default.
            refused(authenticated = false),
            refused("HANDOFF_UNCERTAIN", "anchor:launch:Exception;moved=[] removed=[]"),
            refused("OWNER_REQUEST_IO", ""), refused("OWNER_REQUEST_TIMEOUT", ""),
            refused(d = detail.replace("moved=[]", "moved=[16]")),
            refused(d = detail.replace("removed=[]", "removed=[18]")),
            refused(d = detail.replace("preflight:focus", "anchor:launch")),
            refused(d = detail.replace("preflight:focus", "preflight:inventory")),
        )) {
            val budget = VirtualDisplayHandoffRetry.Budget()
            var calls = 0
            val result = VirtualDisplayHandoffRetry.run(budget, { calls++; attempt },
                { error("must not revalidate non-retryable failure") }, { error("must not delay") })
                as VirtualDisplayHandoffRetry.Outcome.Stopped
            assertEquals("uncertain", result.phase)
            assertEquals(VirtualDisplayHandoffRetry.Failure(attempt.code, attempt.detail), result.failure)
            budget.reset()
            VirtualDisplayHandoffRetry.run(budget, { calls++; refused() }, { true }, {})
            assertEquals(1, calls)
        }
    }

    @Test fun dangerousSecondFailureOverridesFirstPreflightDiagnostic() {
        val budget = VirtualDisplayHandoffRetry.Budget()
        var calls = 0
        val result = VirtualDisplayHandoffRetry.run(budget, {
            if (++calls == 1) refused() else refused("HANDOFF_UNCERTAIN", "anchor:launch:Exception;moved=[] removed=[]")
        }, { true }, {}) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals("HANDOFF_UNCERTAIN", result.failure!!.code)
        assertEquals("uncertain", result.phase)
        assertEquals(2, calls)
        assertTrue(budget.blocked)
    }

    @Test fun stateChangeDuringDelayRejectsRetry() {
        var after = state
        var calls = 0
        val budget = VirtualDisplayHandoffRetry.Budget()
        val result = VirtualDisplayHandoffRetry.run(budget, { calls++; refused() },
            { VirtualDisplayHandoffRetry.freshStateAllowsRetry(state, after, selected) },
            { after = state.copy(sourceTaskCount = 4) }) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(1, calls)
        assertEquals("uncertain", result.phase)
        assertTrue(budget.blocked)
    }

    @Test fun handoffStatusAndDelayExceptionsCloseBudget() {
        for (stage in 0..2) {
            val budget = VirtualDisplayHandoffRetry.Budget()
            var calls = 0
            val result = VirtualDisplayHandoffRetry.run(budget,
                { calls++; if (stage == 0) error("transport") else refused() },
                { if (stage == 1) error("status") else true },
                { if (stage == 2) error("delay") }) as VirtualDisplayHandoffRetry.Outcome.Stopped
            assertEquals("uncertain", result.phase)
            assertEquals(1, calls)
            assertTrue(budget.blocked)
        }
    }

    @Test fun interruptionAtEveryBoundaryStopsAndPreservesInterruptFlag() {
        // Before call, during handoff, status, delay, and interrupt-without-throw success.
        for (stage in 0..4) try {
            val budget = VirtualDisplayHandoffRetry.Budget()
            var calls = 0
            if (stage == 0) Thread.currentThread().interrupt()
            val result = VirtualDisplayHandoffRetry.run(budget, {
                calls++
                if (stage == 1) throw InterruptedException()
                if (stage == 4) {
                    Thread.currentThread().interrupt()
                    VirtualDisplayHandoffRetry.Attempt.Completed
                } else refused()
            }, { if (stage == 2) throw InterruptedException() else true },
                { if (stage == 3) throw InterruptedException() }) as VirtualDisplayHandoffRetry.Outcome.Stopped
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(if (stage == 0) 0 else 1, calls)
            assertEquals("uncertain", result.phase)
            assertTrue(budget.blocked)
        } finally {
            Thread.interrupted()
        }
    }
}
