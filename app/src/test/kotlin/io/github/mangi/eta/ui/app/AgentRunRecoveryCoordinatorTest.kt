package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecoveryCoordinatorTest {
    @Test
    fun sameProcessActiveCheckpointIsReattached() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-active", owner = "same-process")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = setOf("run-active"),
            locallyObservedRunIds = emptySet(),
        )

        assertEquals(listOf("run-active"), plan.reattach.map { it.runId })
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun terminalResultTakesPrecedenceAndKeepsCheckpointForTraceReplay() {
        val checkpoint = checkpoint("run-complete")
        val completed = completed("run-complete")

        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint),
            completedRuns = listOf(completed),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = setOf("run-complete"),
            locallyObservedRunIds = emptySet(),
        )

        assertEquals(completed, plan.completed.single().result)
        assertEquals(checkpoint, plan.completed.single().checkpoint)
        assertTrue(plan.reattach.isEmpty())
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun inactiveCheckpointIsInterruptedButLocallyObservedRunIsIgnored() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-stale"), checkpoint("run-local")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = emptySet(),
            locallyObservedRunIds = setOf("run-local"),
        )

        assertEquals(listOf("run-stale"), plan.interrupted.map { it.runId })
        assertTrue(plan.reattach.isEmpty())
    }

    @Test
    fun unavailableRuntimeLeavesUnresolvedCheckpointUntouched() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-unknown")),
            completedRuns = emptyList(),
            activeStateKnown = false,
            terminalStateKnown = false,
            activeRunIds = emptySet(),
            locallyObservedRunIds = emptySet(),
        )

        assertTrue(plan.interrupted.isEmpty())
        assertTrue(plan.reattach.isEmpty())
    }

    @Test
    fun knownActiveRunCanReattachEvenWhenTerminalQueryFailed() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-active"), checkpoint("run-unknown")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = false,
            activeRunIds = setOf("run-active"),
            locallyObservedRunIds = emptySet(),
        )

        assertEquals(listOf("run-active"), plan.reattach.map { it.runId })
        assertTrue(plan.interrupted.isEmpty())
    }


    @Test
    fun multipleActiveRunsAreAllReattached() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-a"), checkpoint("run-b"), checkpoint("run-stale")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = setOf("run-a", "run-b"),
            locallyObservedRunIds = emptySet(),
        )

        assertEquals(listOf("run-a", "run-b"), plan.reattach.map { it.runId })
        assertEquals(listOf("run-stale"), plan.interrupted.map { it.runId })
    }

    @Test
    fun failedSubscriberWithRetainedBindingCanRecoverCompletedResult() {
        val bindings = mutableMapOf("run-detached" to "conversation-1")
        val subscriberJobs = mutableMapOf("run-detached" to Unit)
        subscriberJobs.remove("run-detached")
        val result = completed("run-detached")
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-detached")),
            completedRuns = listOf(result),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = emptySet(),
            locallyObservedRunIds = subscriberJobs.keys.toSet(),
        )

        assertTrue("Routing identity may survive a detached subscriber", bindings.containsKey("run-detached"))
        assertEquals(result, plan.completed.single().result)
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun detachedSubscriberCanReattachAfterRuntimeBecomesAvailable() {
        val unknown = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-detached")),
            completedRuns = emptyList(),
            activeStateKnown = false,
            terminalStateKnown = false,
            activeRunIds = emptySet(),
            locallyObservedRunIds = emptySet(),
        )
        assertTrue(unknown.completed.isEmpty())
        assertTrue(unknown.reattach.isEmpty())
        assertTrue(unknown.interrupted.isEmpty())

        val available = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-detached")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = setOf("run-detached"),
            locallyObservedRunIds = emptySet(),
        )
        assertEquals(listOf("run-detached"), available.reattach.map { it.runId })
        assertTrue(available.interrupted.isEmpty())
    }

    @Test
    fun registeredSubscriberStillOwnsItsPendingTerminalResult() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-local")),
            completedRuns = listOf(completed("run-local")),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = emptySet(),
            locallyObservedRunIds = setOf("run-local"),
        )
        assertTrue(plan.completed.isEmpty())
        assertTrue(plan.reattach.isEmpty())
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun detachedWithoutTerminalKnowledgeIsNotMarkedInterrupted() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-detached")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = false,
            activeRunIds = emptySet(),
            locallyObservedRunIds = emptySet(),
        )
        assertTrue(plan.interrupted.isEmpty())
    }

    private fun checkpoint(
        runId: String,
        owner: String = "old-process",
    ) = AgentRunCheckpointStore.Checkpoint(
        runId = runId,
        ownerInstanceId = owner,
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        events = emptyList(),
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun completed(runId: String) = AgentRuntimeWire.CompletedRun(
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        result = AgentRuntimeWire.RunResult(
            runId = runId,
            ok = true,
            content = "完成",
        ),
        createdAt = 3L,
    )
}
