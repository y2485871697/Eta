package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBalanceCoordinatorTest {

    private fun provider(id: String, baseUrl: String = "https://$id.example.com"): ProviderSetting =
        OpenAiCompatibleProviderSetting(
            id = id,
            name = id,
            baseUrl = baseUrl,
            balanceOption = BalanceOption(
                enabled = true,
                apiPath = "balance",
                resultPath = "amount",
            ),
        )

    @Test
    fun slowProviderDoesNotBlockFastProvider() = runBlocking {
        val slowGate = CompletableDeferred<Unit>()
        val coordinator = ProviderBalanceCoordinator(
            fetch = { provider ->
                if (provider.id == "slow") {
                    slowGate.await()
                    Result.success("1")
                } else {
                    Result.success("2")
                }
            },
            clock = { 0L },
            format = { it },
        )
        val scope = CoroutineScope(coroutineContext)
        coordinator.refresh(scope, listOf(provider("slow"), provider("fast")))

        val afterFast = withTimeout(2_000) {
            coordinator.states.first { it["fast"]?.amount == "2" }
        }
        assertTrue(afterFast.getValue("slow").refreshing)
        assertNull(afterFast["slow"]?.amount)

        slowGate.complete(Unit)
        val afterSlow = withTimeout(2_000) {
            coordinator.states.first { it["slow"]?.amount == "1" }
        }
        assertFalse(afterSlow.getValue("slow").refreshing)
        assertEquals("2", afterSlow["fast"]?.amount)
    }

    @Test
    fun repeatedRefreshWhileInFlightIsCoalesced() = runBlocking {
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val coordinator = ProviderBalanceCoordinator(
            fetch = {
                calls.incrementAndGet()
                gate.await()
                Result.success("5")
            },
            clock = { 0L },
            format = { it },
        )
        val scope = CoroutineScope(coroutineContext)
        coordinator.refresh(scope, listOf(provider("p")))
        yield()
        assertEquals(1, calls.get())

        coordinator.refresh(scope, listOf(provider("p")))
        coordinator.refresh(scope, listOf(provider("p")))
        yield()
        assertEquals(1, calls.get())

        gate.complete(Unit)
        withTimeout(2_000) { coordinator.states.first { it["p"]?.amount == "5" } }
        assertEquals(1, calls.get())
    }

    @Test
    fun failureKeepsLastAmountAndOnlySuccessAdvancesTimestamp() = runBlocking {
        var failing = false
        var now = 100L
        val coordinator = ProviderBalanceCoordinator(
            fetch = {
                if (failing) Result.failure(IOException("boom")) else Result.success("10")
            },
            clock = { now },
            format = { it },
        )
        val scope = CoroutineScope(coroutineContext)

        coordinator.refresh(scope, listOf(provider("p")))
        withTimeout(2_000) { coordinator.states.first { it["p"]?.amount == "10" } }
        assertEquals(100L, coordinator.states.value.getValue("p").updatedAtMillis)

        now = 200L
        failing = true
        coordinator.refresh(scope, listOf(provider("p")))
        val failed = withTimeout(2_000) {
            coordinator.states.first { it["p"]?.error != null }
        }
        assertEquals("10", failed.getValue("p").amount)
        assertEquals(100L, failed.getValue("p").updatedAtMillis)
        assertFalse(failed.getValue("p").refreshing)

        now = 300L
        failing = false
        coordinator.refresh(scope, listOf(provider("p")))
        val recovered = withTimeout(2_000) {
            coordinator.states.first { it["p"]?.error == null && it["p"]?.updatedAtMillis == 300L }
        }
        assertEquals("10", recovered.getValue("p").amount)
    }

    @Test
    fun staleResultFromPreviousConfigurationIsDiscarded() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val coordinator = ProviderBalanceCoordinator(
            fetch = { provider ->
                if (provider.baseUrl.contains("old")) {
                    firstGate.await()
                    Result.success("OLD")
                } else {
                    Result.success("NEW")
                }
            },
            clock = { 0L },
            format = { it },
        )
        val scope = CoroutineScope(coroutineContext)
        coordinator.refresh(scope, listOf(provider("p", "https://old.example.com")))
        yield()
        coordinator.refresh(scope, listOf(provider("p", "https://new.example.com")))
        withTimeout(2_000) { coordinator.states.first { it["p"]?.amount == "NEW" } }

        firstGate.complete(Unit)
        yield()
        assertEquals("NEW", coordinator.states.value.getValue("p").amount)
    }

    @Test
    fun balancesMirrorSuccessfulAmountsOnly() = runBlocking {
        val coordinator = ProviderBalanceCoordinator(
            fetch = { Result.success("42") },
            clock = { 0L },
            format = { it },
        )
        val scope = CoroutineScope(coroutineContext)
        coordinator.refresh(scope, listOf(provider("p")))
        withTimeout(2_000) { coordinator.balances.first { it["p"] == "42" } }
        assertEquals(mapOf("p" to "42"), coordinator.balances.value)
    }

    @Test
    fun pollerRestartsAfterScopeCancellation() = runBlocking {
        val dispatcher = (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default
        val counter = AtomicInteger(0)
        val providers = MutableStateFlow(listOf(provider("p")))
        val poller = ProviderBalancePoller(
            refresh = { _, _ -> counter.incrementAndGet() },
            providersFlow = { providers },
            intervalMs = 30_000L,
            dispatcher = dispatcher,
        )
        val firstScope = CoroutineScope(coroutineContext + Job())
        poller.start(firstScope)
        withTimeout(2_000) { while (counter.get() < 1) yield() }
        firstScope.cancel()

        val secondScope = CoroutineScope(coroutineContext + Job())
        poller.start(secondScope)
        withTimeout(2_000) { while (counter.get() < 2) yield() }
        assertEquals(2, counter.get())
        secondScope.cancel()
    }

    @Test
    fun startingTwiceOnActiveScopeKeepsSinglePoller() = runBlocking {
        val dispatcher = (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default
        val counter = AtomicInteger(0)
        val providers = MutableStateFlow(listOf(provider("p")))
        val poller = ProviderBalancePoller(
            refresh = { _, _ -> counter.incrementAndGet() },
            providersFlow = { providers },
            intervalMs = 30_000L,
            dispatcher = dispatcher,
        )
        val scope = CoroutineScope(coroutineContext + Job())
        poller.start(scope)
        withTimeout(2_000) { while (counter.get() < 1) yield() }
        poller.start(scope)
        yield()
        assertEquals(1, counter.get())
        scope.cancel()
    }
    @Test
    fun cancelledOwnerDoesNotLeaveRefreshingOrPreventRestart() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var first = true
        val coordinator = ProviderBalanceCoordinator(fetch = {
            if (first) { first = false; entered.complete(Unit); never.await() }
            Result.success("12")
        }, clock = { 0L }, format = { it })
        val oldScope = CoroutineScope(coroutineContext + Job())
        coordinator.refresh(oldScope, listOf(provider("p")))
        entered.await()
        oldScope.cancel()
        withTimeout(2000) { coordinator.states.first { it["p"]?.refreshing == false } }
        coordinator.refresh(this, listOf(provider("p")))
        withTimeout(2000) { coordinator.states.first { it["p"]?.amount == "12" } }
    }

    @Test
    fun alreadyCancelledScopeDoesNotCreateZombieRequest() = runBlocking {
        val oldScope = CoroutineScope(coroutineContext + Job())
        oldScope.cancel()
        val coordinator = ProviderBalanceCoordinator(fetch = { Result.success("1") }, clock = { 0L }, format = { it })
        coordinator.refresh(oldScope, listOf(provider("p")))
        assertTrue(coordinator.states.value.isEmpty())
        coordinator.refresh(this, listOf(provider("p")))
        withTimeout(2000) { coordinator.states.first { it["p"]?.amount == "1" } }
    }

    @Test
    fun failureDoesNotExposeExceptionSecrets() = runBlocking {
        val coordinator = ProviderBalanceCoordinator(
            fetch = { Result.failure(IOException("Authorization: Bearer secret-value")) },
            clock = { 0L }, format = { it },
        )
        coordinator.refresh(this, listOf(provider("p")))
        val result = withTimeout(2000) { coordinator.states.first { it["p"]?.error != null } }
        assertFalse(result.getValue("p").error!!.contains("secret-value"))
    }

}
