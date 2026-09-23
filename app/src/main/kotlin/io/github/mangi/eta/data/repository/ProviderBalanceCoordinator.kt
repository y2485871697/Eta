package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.canQueryBalance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 余额刷新的核心状态机（可注入 fetch/clock，便于回归测试）。
 *
 * 设计目标：
 * - 每个 provider 独立刷新，慢 provider 不阻塞其它 provider（不再有全局 mutex 包裹 awaitAll）。
 * - 结果一旦返回立即发布，不等同轮其它 provider。
 * - 同一 provider 在途时重复请求会被合并，不排队堆积。
 * - provider 配置（baseUrl/apiKey/balanceOption）变化时，旧请求的结果按签名丢弃，避免覆盖新配置。
 * - 失败保留最后一次成功金额并标记 error；updatedAtMillis 只在成功时更新。
 */
internal class ProviderBalanceCoordinator(
    private val fetch: suspend (ProviderSetting) -> Result<String>,
    private val clock: () -> Long,
    private val format: (String) -> String = ::formatBalanceDisplay,
) {
    private val gate = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val signatures = mutableMapOf<String, String>()

    private val _states = MutableStateFlow<Map<String, ProviderBalanceState>>(emptyMap())
    val states: StateFlow<Map<String, ProviderBalanceState>> = _states.asStateFlow()

    private val _balances = MutableStateFlow<Map<String, String>>(emptyMap())
    val balances: StateFlow<Map<String, String>> = _balances.asStateFlow()

    /** 对给定 provider 列表触发一轮刷新；返回后各 provider 的请求已各自在 [scope] 内启动。 */
    suspend fun refresh(scope: CoroutineScope, providers: List<ProviderSetting>) {
        val queryable = providers.filter(ProviderSetting::canQueryBalance)
        val queryableIds = queryable.mapTo(mutableSetOf(), ProviderSetting::id)
        gate.withLock {
            (jobs.keys - queryableIds).forEach { id -> jobs.remove(id)?.cancel() }
            signatures.keys.retainAll(queryableIds)
            publishStates(_states.value.filterKeys { it in queryableIds })
        }
        queryable.forEach { provider -> startRefresh(scope, provider) }
    }

    private suspend fun startRefresh(scope: CoroutineScope, provider: ProviderSetting) {
        val signature = signatureOf(provider)
        val started = gate.withLock {
            if (jobs[provider.id] != null && signatures[provider.id] == signature) {
                // 已有同配置请求在途：合并，避免排队。
                return
            }
            jobs.remove(provider.id)?.cancel()
            signatures[provider.id] = signature
            val previous = _states.value[provider.id] ?: ProviderBalanceState()
            publishStates(_states.value + (provider.id to previous.copy(refreshing = true)))
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                try {
                    val result = try {
                        fetch(provider)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        Result.failure(throwable)
                    }
                    publish(provider.id, signature, result)
                } finally {
                    // 取消时也要清理 jobs 表，避免遗留“僵尸”条目导致后续刷新被错误合并。
                    withContext(NonCancellable) {
                        gate.withLock {
                            if (jobs[provider.id] === self) {
                                jobs.remove(provider.id)
                            }
                        }
                    }
                }
            }
            jobs[provider.id] = job
            job
        }
        // 在锁外启动，避免 Unconfined 等调度器下自锁。
        started.start()
    }

    private suspend fun publish(id: String, signature: String, result: Result<String>) {
        gate.withLock {
            if (signatures[id] != signature) return
            jobs.remove(id)
            val previous = _states.value[id] ?: ProviderBalanceState()
            val next = result.fold(
                onSuccess = { raw ->
                    previous.copy(
                        amount = format(raw),
                        updatedAtMillis = clock(),
                        refreshing = false,
                        error = null,
                    )
                },
                onFailure = { throwable ->
                    previous.copy(
                        refreshing = false,
                        error = errorText(throwable),
                    )
                },
            )
            publishStates(_states.value + (id to next))
        }
    }

    private fun publishStates(states: Map<String, ProviderBalanceState>) {
        _states.value = states
        _balances.value = states.mapNotNull { (id, state) -> state.amount?.let { id to it } }.toMap()
    }

    private fun signatureOf(provider: ProviderSetting): String {
        val option = provider.balanceOption
        return listOf(
            provider.baseUrl,
            provider.apiKey,
            option.enabled.toString(),
            option.preset,
            option.apiPath,
            option.resultPath,
            option.accessToken,
        ).joinToString("\u0000")
    }

    private fun errorText(throwable: Throwable): String =
        throwable.message?.takeIf(String::isNotBlank) ?: "Balance query failed"
}

/**
 * 轮询引擎：持有单个可重启的 poller job。
 *
 * 修复了旧实现「started 永久 true」的问题——job 因 scope 取消后 [start] 会重新拉起，
 * 但同一时刻最多只有一个 poller，避免多轮询。
 */
internal class ProviderBalancePoller(
    private val refresh: suspend (CoroutineScope, List<ProviderSetting>) -> Unit,
    private val providersFlow: () -> Flow<List<ProviderSetting>>,
    private val intervalMs: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var job: Job? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
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

    fun stop() {
        job?.cancel()
        job = null
    }
}
