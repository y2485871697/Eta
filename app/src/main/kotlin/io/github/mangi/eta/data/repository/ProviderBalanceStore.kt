package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.canQueryBalance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ProviderBalanceStore {
    private const val POLL_INTERVAL_MS = 30_000L
    private val balancesState = MutableStateFlow<Map<String, String>>(emptyMap())
    val balances: StateFlow<Map<String, String>> = balancesState.asStateFlow()
    private val refreshMutex = Mutex()
    @Volatile private var started = false

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            ProviderRepository.providersFlow().collectLatest { providers ->
                while (isActive) {
                    refresh(providers)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun requestRefresh(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            refresh(ProviderRepository.allProviders())
        }
    }

    private suspend fun refresh(providers: List<ProviderSetting>) {
        refreshMutex.withLock {
            val enabled = providers.filter(ProviderSetting::canQueryBalance)
            val enabledIds = enabled.map(ProviderSetting::id).toSet()
            coroutineScope {
                enabled.map { provider ->
                    async {
                        provider.id to ProviderBalanceFetcher.fetch(provider)
                            .getOrNull()
                            ?.let(::formatBalanceDisplay)
                    }
                }.awaitAll().forEach { (id, value) ->
                    if (value != null) {
                        balancesState.update { current -> current + (id to value) }
                    }
                }
            }
            balancesState.update { current -> current.filterKeys { it in enabledIds } }
        }
    }
}
