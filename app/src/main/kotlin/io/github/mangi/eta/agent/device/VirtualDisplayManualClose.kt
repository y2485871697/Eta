package io.github.mangi.eta.agent.device

/** Explicit empty-display release only. No handoff, task migration, process kill or retry. */
internal class VirtualDisplayManualClose(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    data class Key(val displayId: Int, val uniqueId: String, val boot: String,
        val pid: Long, val socket: String)
    data class Evidence(
        val key: Key, val currentBoot: String?, val authenticated: Boolean,
        val activeAgent: Boolean, val sourceState: String?, val taskCount: Int?,
        val sourceEmpty: Boolean?, val retainedCount: Int?, val finishing: Boolean?,
        val handoffComplete: Boolean?, val releaseAttempted: Boolean?,
        val mutationUncertain: Boolean?, val journalBlocked: Boolean,
    )
    data class Result(val outcome: String, val reason: String = "",
        val nonce: String? = null, val expiresInMs: Long? = null) {
        val confirmed: Boolean get() = outcome == "closed_confirmed"
        override fun toString() = "ManualCloseResult(outcome=$outcome, reason=$reason)"
    }
    interface Backend {
        fun evidence(): Evidence
        /** Commit before any release IPC; never erase credentials or previous evidence. */
        fun markAttempt(): Boolean
        /** Sends at most one release. A response alone is not exit confirmation. */
        fun release(): Boolean
        /** Read-only: exact display gone AND original process exited. Unknown is false. */
        fun confirmGone(): Boolean
        fun clearConfirmed(): Boolean
    }
    private data class Prepared(val evidence: Evidence, val expires: Long)
    private val prepared = linkedMapOf<String, Prepared>()

    @Synchronized fun prepare(backend: Backend): Result {
        val evidence = backend.evidence()
        val reason = refusal(evidence)
        if (reason.isNotEmpty()) return Result("blocked", reason)
        val now = clock()
        prepared.entries.removeAll { it.value.expires <= now }
        // Only one owner exists; preparing again invalidates old confirmations.
        prepared.clear()
        val nonce = random()
        prepared[nonce] = Prepared(evidence, now + TTL_MS)
        return Result("prepared", nonce = nonce, expiresInMs = TTL_MS)
    }

    @Synchronized fun close(nonce: String, backend: Backend): Result {
        // Consume even when subsequent checks fail; POST is never automatically retried.
        val ticket = prepared.remove(nonce) ?: return Result("blocked", "CLOSE_NONCE_INVALID")
        if (clock() >= ticket.expires) return Result("blocked", "CLOSE_NONCE_EXPIRED")
        val current = backend.evidence()
        val reason = refusal(current)
        if (reason.isNotEmpty()) return Result("blocked", reason)
        if (ticket.evidence != current) return Result("blocked", "CLOSE_STATE_CHANGED")
        if (!backend.markAttempt()) return Result("blocked", "RECOVERY_STATE_UNWRITABLE")
        // Even an exception or lost reply consumes both the nonce and durable release permission.
        try { backend.release() } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result("closed_unconfirmed", "RELEASE_INTERRUPTED")
        } catch (_: Exception) {
            return Result("closed_unconfirmed", "RELEASE_UNCONFIRMED")
        }
        if (!backend.confirmGone()) return Result("closed_unconfirmed", "RELEASE_UNCONFIRMED")
        return if (backend.clearConfirmed()) Result("closed_confirmed")
        else Result("closed_confirmed", "RECOVERY_RECORD_CLEAR_FAILED")
    }

    companion object {
        const val TTL_MS = 30_000L
        fun refusal(e: Evidence): String = when {
            e.currentBoot == null -> "BOOT_ID_UNAVAILABLE"
            e.key.boot != e.currentBoot -> "RECOVERY_BOOT_MISMATCH"
            e.activeAgent -> "ACTIVE_AGENT_OWNER"
            !e.authenticated -> "RECOVERY_OWNER_IDENTITY_MISMATCH"
            e.journalBlocked || e.mutationUncertain == true -> "MUTATION_UNCERTAIN_NO_REPLAY"
            e.releaseAttempted == true -> "RELEASE_ALREADY_ATTEMPTED"
            e.finishing == null || e.handoffComplete == null || e.releaseAttempted == null ||
                e.mutationUncertain == null || e.retainedCount == null || e.retainedCount < 0 -> "OWNER_STATE_UNKNOWN"
            e.sourceState !in setOf("empty", "occupied") || e.taskCount == null ||
                e.taskCount < 0 || e.sourceEmpty == null -> "SOURCE_STATE_UNKNOWN"
            e.sourceState != "empty" || e.taskCount != 0 || !e.sourceEmpty -> "SOURCE_NOT_EMPTY"
            e.finishing != e.handoffComplete -> "HANDOFF_IN_PROGRESS"
            e.retainedCount > 0 && !e.handoffComplete -> "HANDOFF_REQUIRED"
            else -> ""
        }
    }
}
