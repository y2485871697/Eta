package io.github.mangi.eta.agent.model

/** Local estimates are a send-budget fallback, never published cloud usage. */
internal class AgentRequestBudgetPolicy {
    private var localBoundary = true

    fun consumeLocalBoundary(): Boolean = localBoundary

    fun requestStarted() { localBoundary = false }
    fun contextReplaced() { localBoundary = true }

    fun tokens(cloudInput: Int?, estimateRequest: () -> Int): Int =
        if (localBoundary) estimateRequest() else cloudInput?.takeIf { it > 0 } ?: 0
}
