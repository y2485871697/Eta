package io.github.mangi.eta.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 单个 provider 的余额刷新状态。
 *
 * - [amount] 保留最后一次成功金额（失败不清空）。
 * - [updatedAtMillis] 仅在成功时更新。
 * - [refreshing] 当前是否有在途请求。
 * - [error] 最近一次失败的安全错误信息（不含响应正文）。
 */
internal data class ProviderBalanceState(
    val amount: String? = null,
    val updatedAtMillis: Long? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
)

internal object ProviderBalanceStore {
    private const val POLL_INTERVAL_MS = 30_000L

    private val coordinator = ProviderBalanceCoordinator(
        fetch = { provider -> ProviderBalanceFetcher.fetch(provider) },
        clock = { System.currentTimeMillis() },
    )

    private val poller = ProviderBalancePoller(
        refresh = { scope, providers -> coordinator.refresh(scope, providers) },
        providersFlow = { ProviderRepository.providersFlow() },
        intervalMs = POLL_INTERVAL_MS,
    )

    /** 富状态，供新 UI 消费者使用。 */
    val states: StateFlow<Map<String, ProviderBalanceState>> = coordinator.states

    /** 兼容旧消费者：仅暴露成功金额的映射。 */
    val balances: StateFlow<Map<String, String>> = coordinator.balances

    fun start(scope: CoroutineScope) {
        poller.start(scope)
    }

    fun requestRefresh(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            coordinator.refresh(scope, ProviderRepository.allProviders())
        }
    }
}
