package io.github.mangi.eta.agent.device

/**
 * Bounded automatic retry for a focus-only, read-only handoff preflight failure.
 *
 * A handoff that stops entirely inside the read-only preflight has not crossed the first side
 * effect, so the owner reports the exact authenticated error [ERROR_CODE] with a sanitized symbolic
 * detail of the form `preflight:focus:<Type>;moved=[] removed=[]`. Only that failure may be retried:
 * IO, timeouts, any other preflight stage, an uncertain mutation, release and an already finished
 * session are never replayed.
 *
 * This type keeps the decisions pure and injects the physical handoff, the fresh-state re-read and
 * the delay, so the retry budget, the fresh-state gate, the frozen selection and interruption
 * handling are unit testable without a device.
 */
internal object VirtualDisplayHandoffRetry {
    /** The only owner error code that authorizes the focus-only read-only retry. */
    const val ERROR_CODE = "HANDOFF_PREFLIGHT_FAILED"

    /** Total physical handoff attempts allowed per session, shared across finish/onRunClosed. */
    const val MAX_ATTEMPTS = 3

    /** Delay before the first and second automatic retry. */
    val DELAYS_MILLIS = longArrayOf(300L, 700L)

    /**
     * Sanitized symbolic detail of a handoff that stopped in the `focus` preflight stage and moved
     * and removed nothing. The optional space after `;` matches the whitespace-collapsing sanitizer
     * in [VirtualDisplaySession].
     */
    private val FOCUS_DETAIL = Regex("preflight:focus:[A-Za-z0-9_$]+; ?moved=\\[\\] removed=\\[\\]")

    /** One physical handoff outcome; [Refused]'s code/detail are already the safe, token-free values. */
    sealed interface Attempt {
        object Completed : Attempt
        data class Refused(val code: String, val detail: String) : Attempt
    }

    /** The first meaningful failure of a run, kept verbatim for the receipt. */
    data class Failure(val code: String, val detail: String)

    sealed interface Outcome {
        object HandedOff : Outcome

        /** [failure] is the FIRST meaningful failure; null only when no attempt ever ran. */
        data class Stopped(val failure: Failure?) : Outcome
    }

    /**
     * A per-session cap on physical handoff attempts. One instance lives on the session, so the
     * explicit finish and the automatic onRunClosed finish share it and cannot multiply attempts.
     */
    class Budget(private val maxAttempts: Int = MAX_ATTEMPTS) {
        private var used = 0
        val remaining: Int get() = (maxAttempts - used).coerceAtLeast(0)
        fun hasRemaining(): Boolean = remaining > 0
        fun consume() { if (hasRemaining()) used++ }

        /**
         * A deliberate later run that adopts a held session starts a fresh bounded budget; it never
         * inherits the exhausted budget of the run that held the session.
         */
        fun reset() { used = 0 }
        fun stop() { used = maxAttempts }
    }

    /** True only for the exact authenticated focus-only read-only preflight failure. Pure. */
    fun isRetryable(code: String, detail: String): Boolean =
        code == ERROR_CODE && FOCUS_DETAIL.matches(detail)

    /**
     * Pure fresh-state gate: a retry is allowed only while the re-read status still proves a clean,
     * unfinishing preflight whose retained task ids still contain the frozen selection.
     */
    fun freshStateAllowsRetry(
        statusOk: Boolean,
        flags: VirtualDisplayRecoveryPolicy.Flags?,
        retainedTaskIds: Set<Int>?,
        frozenSelectedIds: Set<Int>,
    ): Boolean {
        if (!statusOk || flags == null || retainedTaskIds == null) return false
        if (flags.finishing || flags.handoffComplete || flags.releaseAttempted || flags.mutationUncertain) return false
        return retainedTaskIds.containsAll(frozenSelectedIds)
    }

    fun delayForRetry(retryIndex: Int): Long =
        DELAYS_MILLIS[retryIndex.coerceIn(0, DELAYS_MILLIS.lastIndex)]

    /**
     * Drives a handoff with a bounded number of retries.
     *
     * @param budget shared attempt budget; checked before every physical handoff
     * @param handoff performs exactly one physical handoff
     * @param verifyFresh re-reads the same client and proves a clean, unfinishing state whose
     *        retained ids still contain the frozen selection; called before every retry
     * @param delay sleeps the given millis; an [InterruptedException] stops the run
     */
    fun run(
        budget: Budget,
        handoff: () -> Attempt,
        verifyFresh: () -> Boolean,
        delay: (Long) -> Unit,
    ): Outcome {
        var first: Failure? = null
        var retryIndex = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) { budget.stop(); return Outcome.Stopped(first) }
            if (!budget.hasRemaining()) return Outcome.Stopped(first)
            budget.consume()
            val attempt = try {
                handoff()
            } catch (_: InterruptedException) {
                budget.stop()
                Thread.currentThread().interrupt()
                return Outcome.Stopped(Failure("HANDOFF_UNCERTAIN", ""))
            }
            when (attempt) {
                Attempt.Completed -> return Outcome.HandedOff
                is Attempt.Refused -> {
                    if (first == null) first = Failure(attempt.code, attempt.detail)
                    if (!isRetryable(attempt.code, attempt.detail)) {
                        budget.stop()
                        return Outcome.Stopped(Failure(attempt.code, attempt.detail))
                    }
                    if (!budget.hasRemaining()) return Outcome.Stopped(first)
                    if (!sleep(delay, delayForRetry(retryIndex))) { budget.stop(); return Outcome.Stopped(first) }
                    if (!verify(verifyFresh)) { budget.stop(); return Outcome.Stopped(first) }
                    if (Thread.currentThread().isInterrupted) { budget.stop(); return Outcome.Stopped(first) }
                    retryIndex++
                }
            }
        }
    }

    private fun verify(verifyFresh: () -> Boolean): Boolean =
        try {
            verifyFresh()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        } catch (_: Exception) {
            false
        }

    private fun sleep(delay: (Long) -> Unit, millis: Long): Boolean =
        try {
            delay(millis); true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); false
        } catch (_: Exception) {
            false
        }
}
