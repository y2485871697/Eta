package io.github.mangi.eta.agent.runtime

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Serializes result drains and ACKs without waiting for Room on the main looper. */
internal class AgentRuntimeResultWorker(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-runtime-results").apply { isDaemon = true }
    },
    private val onFailure: (Exception) -> Unit,
) {
    fun submit(action: () -> Unit): Boolean = try {
        executor.execute {
            try { action() }
            catch (failure: Exception) { report(failure) }
        }
        true
    } catch (failure: Exception) {
        report(failure)
        false
    }

    // Accepted ACKs must still commit when the service is destroyed.
    fun close() = executor.shutdown()

    private fun report(failure: Exception) {
        runCatching { onFailure(failure) }
    }
}
