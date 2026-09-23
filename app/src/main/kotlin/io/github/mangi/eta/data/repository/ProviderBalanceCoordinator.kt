package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.canQueryBalance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One independently published, cancellable request per provider. No network work under gate. */
internal class ProviderBalanceCoordinator(
    private val fetch: suspend (ProviderSetting) -> Result<String>,
    private val clock: () -> Long,
    private val format: (String) -> String = ::formatBalanceDisplay,
) {
    private val gate = Any()
    private val jobs = mutableMapOf<String, Job>()
    // Structural equality covers custom headers and every credential/config field without logging them.
    private val configurations = mutableMapOf<String, ProviderSetting>()
    private val stateFlow = MutableStateFlow<Map<String, ProviderBalanceState>>(emptyMap())
    val states: StateFlow<Map<String, ProviderBalanceState>> = stateFlow.asStateFlow()
    private val amountFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val balances: StateFlow<Map<String, String>> = amountFlow.asStateFlow()

    suspend fun refresh(scope: CoroutineScope, providers: List<ProviderSetting>) {
        if (!scope.isActive) return
        val enabled = providers.filter(ProviderSetting::canQueryBalance).distinctBy { it.id }
        val pending = mutableListOf<Job>()
        synchronized(gate) {
            val ids = enabled.mapTo(mutableSetOf()) { it.id }
            (jobs.keys - ids).forEach { jobs.remove(it)?.cancel() }
            configurations.keys.retainAll(ids)
            publishStates(stateFlow.value.filterKeys { it in ids })
            for (provider in enabled) {
                val id = provider.id
                val previousJob = jobs[id]
                if (previousJob != null && !previousJob.isCompleted && !previousJob.isCancelled && configurations[id] == provider) continue
                jobs.remove(id)?.cancel()
                val previous = if (configurations[id] == provider) stateFlow.value[id] ?: ProviderBalanceState() else ProviderBalanceState()
                configurations[id] = provider
                publishStates(stateFlow.value + (id to previous.copy(refreshing = true)))
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    val self = coroutineContext[Job]!!
                    val result = try { fetch(provider).mapCatching(format) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                    synchronized(gate) {
                        if (jobs[id] !== self || !self.isActive) return@synchronized
                        val current = stateFlow.value[id] ?: ProviderBalanceState()
                        val next = result.fold(
                            onSuccess = { current.copy(amount = it, updatedAtMillis = clock(), refreshing = false, error = null) },
                            onFailure = { current.copy(refreshing = false, error = "Balance query failed; retry shortly") },
                        )
                        jobs.remove(id)
                        publishStates(stateFlow.value + (id to next))
                    }
                }
                jobs[id] = job
                job.invokeOnCompletion {
                    synchronized(gate) {
                        if (jobs[id] === job) {
                            jobs.remove(id)
                            stateFlow.value[id]?.let { previousState ->
                                publishStates(stateFlow.value + (id to previousState.copy(refreshing = false)))
                            }
                        }
                    }
                }
                pending.add(job)
            }
        }
        pending.forEach { it.start() }
    }

    private fun publishStates(states: Map<String, ProviderBalanceState>) {
        stateFlow.value = states
        amountFlow.value = states.mapNotNull { (id, state) -> state.amount?.let { id to it } }.toMap()
    }
}

/** The poller follows its current owner scope and can be started again after that scope is gone. */
internal class ProviderBalancePoller(
    private val refresh: suspend (CoroutineScope, List<ProviderSetting>) -> Unit,
    private val providersFlow: () -> Flow<List<ProviderSetting>>,
    private val intervalMs: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    @Synchronized fun start(scope: CoroutineScope) {
        if (!scope.isActive || job?.isActive == true) return
        job?.cancel()
        job = scope.launch(dispatcher) {
            val pollScope = this
            providersFlow().collectLatest { providers ->
                while (isActive) {
                    refresh(pollScope, providers)
                    delay(intervalMs)
                }
            }
        }
    }
    @Synchronized fun stop() { job?.cancel(); job = null }
}
