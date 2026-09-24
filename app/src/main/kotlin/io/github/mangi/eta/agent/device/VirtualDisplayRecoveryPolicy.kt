package io.github.mangi.eta.agent.device

/** Pure decisions: absent evidence never authorizes mutation replay. */
internal object VirtualDisplayRecoveryPolicy {
    data class Flags(val finishing: Boolean, val handoffComplete: Boolean,
        val releaseAttempted: Boolean, val mutationUncertain: Boolean, val sourceEmpty: Boolean)
    enum class Action { HANDOFF, RELEASE_ONLY, REFUSE }
    fun finishAction(f: Flags?): Action = when {
        f == null || f.mutationUncertain || f.releaseAttempted -> Action.REFUSE
        f.handoffComplete && f.finishing && f.sourceEmpty -> Action.RELEASE_ONLY
        f.handoffComplete || f.finishing -> Action.REFUSE
        else -> Action.HANDOFF
    }
    fun taskIds(raw: List<*>): Set<Int>? {
        val ids = linkedSetOf<Int>()
        for (value in raw) {
            val id = value as? Int ?: return null
            if (id <= 0 || !ids.add(id)) return null
        }
        return ids
    }
}
