package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Pending choices survive the UI/service view disappearing. No observer means no decision:
 * children stay paused. Each choice owns registry-issued generation/control-epoch tokens.
 */
internal object AgentChildRunControl {
    private val policy = AgentChildControlPolicy<AgentChildTaskGroups.StopTarget> { UUID.randomUUID().toString() }
    private val parents = mutableMapOf<String, AgentRuntimeSession>()
    private val choices = MutableStateFlow<List<AgentChildControlPolicy.Selection<AgentChildTaskGroups.StopTarget>>>(emptyList())
    val pendingSelections: StateFlow<List<AgentChildControlPolicy.Selection<AgentChildTaskGroups.StopTarget>>> = choices
    private val cleanup = Executors.newSingleThreadExecutor { task ->
        Thread(task, "agent-child-choice").apply { isDaemon = true }
    }

    @Synchronized fun begin(session: AgentRuntimeSession) {
        policy.begin(session, session.runId)
        parents[session.runId] = session
    }

    @Synchronized fun registered(session: AgentRuntimeSession, ownerId: String, generation: String) {
        if (parents[session.runId] !== session) return
        // Never construct a fresh two-argument StopTarget: only registry captures carry the
        // control epoch, which prevents an old callback from touching a newly adopted task.
        AgentChildTaskGroups.captureRunStopTargets(session.runId).filter {
            it.ownerId == ownerId && generation in it.generations
        }.forEach { target ->
            if (policy.registered(session, target)) AgentChildTaskGroups.pause(target)
        }
    }

    private fun controllable(session: AgentRuntimeSession): Boolean =
        parents[session.runId] === session && policy.isControllable(session) &&
            !session.isTerminal && !session.controller.isCancelled

    @Synchronized fun pause(session: AgentRuntimeSession) {
        if (!controllable(session)) return
        val targets = AgentChildTaskGroups.captureRunStopTargets(session.runId)
        policy.pause(session, targets).forEach(AgentChildTaskGroups::pause)
        session.controller.pause()
    }

    @Synchronized fun resume(session: AgentRuntimeSession) {
        if (!controllable(session)) return
        policy.resume(session).forEach(AgentChildTaskGroups::resume)
        session.controller.resume()
    }

    /**
     * Explicit controls/failures freeze before terminal publication. SUCCESS only seals
     * parent control: it neither pauses live children nor resumes previously paused tasks.
     * Registry capture uses the CURRENT controlling run, not a group's birth run; pause validates
     * the captured control epoch again atomically, so continue/adoption wins over stale cleanup.
     */
    @Synchronized fun terminate(session: AgentRuntimeSession, reason: AgentChildControlPolicy.Reason) {
        if (parents[session.runId] !== session || !policy.isControllable(session)) return
        val targets = if (AgentChildControlPolicy.shouldPauseChildren(reason)) {
            AgentChildTaskGroups.captureRunStopTargets(session.runId)
        } else emptyList()
        targets.forEach(AgentChildTaskGroups::pause)
        val unfinished = if (targets.isEmpty()) false else {
            val active = AgentChildTaskGroups.captureActiveStopTargets()
            targets.any { target -> active.any {
                it.ownerId == target.ownerId && it.generations.any(target.generations::contains)
            } }
        }
        policy.terminate(session, reason, targets, unfinished)
        choices.value = policy.pending
    }

    /** Child-only controls must use a registry-issued token, not construct one from an owner. */
    @Synchronized fun offerChildren(target: AgentChildTaskGroups.StopTarget, runId: String = "") {
        if (policy.pending.any { target in it.targets }) return
        AgentChildTaskGroups.pause(target)
        val unfinished = AgentChildTaskGroups.captureActiveStopTargets().any {
            it.ownerId == target.ownerId && it.generations.any(target.generations::contains)
        }
        val identity = Any()
        policy.begin(identity, runId)
        policy.terminate(identity, AgentChildControlPolicy.Reason.USER_STOP, listOf(target), unfinished)
        policy.finish(identity)
        choices.value = policy.pending
    }

    /** Pause/dismiss only consumes the choice; it NEVER resumes children or reapplies settings. */
    fun resolve(eventId: String, stopChildren: Boolean = false): Boolean {
        val selection = synchronized(this) {
            val captured = policy.resolve(eventId) ?: return false
            choices.value = policy.pending
            captured
        }
        if (stopChildren) cleanup.execute {
            selection.targets.forEach { target ->
                runCatching { AgentChildTaskGroups.stop(target) }.onFailure { failure ->
                    AndroidAgentLogger.error("Child selection stop failed: type=${failure.safeLogType()}")
                }
            }
        }
        return true
    }

    @Synchronized fun finish(session: AgentRuntimeSession) {
        policy.finish(session)
        if (parents[session.runId] === session) parents.remove(session.runId)
        // A pending selection outlives its parent. Never drop it because a new turn started.
    }
}
