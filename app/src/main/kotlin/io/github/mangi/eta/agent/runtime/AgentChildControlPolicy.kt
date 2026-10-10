package io.github.mangi.eta.agent.runtime

/** Pure, identity-scoped policy. Settings are applied by their owner, never by a dialog callback. */
internal class AgentChildControlPolicy<T>(private val nextEventId: () -> String) {
    enum class Reason {
        USER_STOP, SETTINGS_CHANGED, FINAL_NETWORK_FAILURE,
        USER_CANCEL, SUCCESS,
    }

    data class Selection<T>(
        val eventId: String,
        val runId: String,
        val reason: Reason,
        val targets: List<T>,
    )

    private class Run<T>(val runId: String) {
        var paused = false
        var terminated = false
        val pausedTargets = linkedSetOf<T>()
    }

    private val runs = mutableMapOf<Any, Run<T>>()
    private val choices = linkedMapOf<String, Selection<T>>()
    val pending: List<Selection<T>> get() = choices.values.toList()

    fun begin(identity: Any, runId: String) { runs.putIfAbsent(identity, Run(runId)) }
    fun isControllable(identity: Any): Boolean = runs[identity]?.terminated == false

    /** Capture before requesting the parent's pause. Repeated pause never opens a choice. */
    fun pause(identity: Any, targets: List<T>): List<T> {
        val run = runs[identity]?.takeUnless { it.terminated } ?: return emptyList()
        run.paused = true
        run.pausedTargets.addAll(targets)
        return run.pausedTargets.toList()
    }

    /** A group registered during parent preparation must inherit the already-requested freeze. */
    fun registered(identity: Any, target: T): Boolean {
        val run = runs[identity] ?: return false
        if (!run.paused) return false
        if (!run.terminated) run.pausedTargets.add(target)
        return true
    }

    fun resume(identity: Any): List<T> {
        val run = runs[identity]?.takeIf { it.paused && !it.terminated } ?: return emptyList()
        run.paused = false
        return run.pausedTargets.toList().also { run.pausedTargets.clear() }
    }

    fun terminate(identity: Any, reason: Reason, targets: List<T>, hasUnfinished: Boolean): Selection<T>? {
        val run = runs[identity]?.takeUnless { it.terminated } ?: return null
        run.terminated = true
        // Normal completion never adds a freeze or clears an explicit earlier pause.
        run.paused = run.paused || shouldPauseChildren(reason)
        run.pausedTargets.clear()
        if (!shouldOffer(reason, hasUnfinished) || targets.isEmpty()) return null
        return Selection(nextEventId(), run.runId, reason, targets.toList()).also { choices[it.eventId] = it }
    }

    /** There is deliberately no current-run lookup here. A stale callback can only consume its event. */
    fun resolve(eventId: String): Selection<T>? = choices.remove(eventId)
    fun finish(identity: Any) { runs.remove(identity) }

    companion object {
        /** A normal final reply ends only the parent; explicit controls/failures still freeze. */
        fun shouldPauseChildren(reason: Reason): Boolean = reason != Reason.SUCCESS

        fun shouldOffer(reason: Reason, hasUnfinished: Boolean): Boolean = hasUnfinished && when (reason) {
            Reason.USER_STOP, Reason.SETTINGS_CHANGED, Reason.FINAL_NETWORK_FAILURE -> true
            Reason.USER_CANCEL, Reason.SUCCESS -> false
        }
    }
}
