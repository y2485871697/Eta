package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.repository.ProviderBalanceFetcher
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHttpClientConcurrencyTest {
    @Test fun clientsHaveIndependentQueuesButKeepTheConnectionPoolAndTimeouts() {
        val base = AgentHttpClient.client
        val model = AgentHttpClient.modelClient
        val balance = ProviderBalanceFetcher.balanceClient
        assertNotSame(base.dispatcher, model.dispatcher)
        assertNotSame(base.dispatcher, balance.dispatcher)
        assertNotSame(model.dispatcher, balance.dispatcher)
        assertSame(base.connectionPool, model.connectionPool)
        assertSame(base.connectionPool, balance.connectionPool)
        assertEquals(base.dispatcher.maxRequests, model.dispatcher.maxRequests)
        assertEquals(model.dispatcher.maxRequests, model.dispatcher.maxRequestsPerHost)
        assertEquals(Dispatcher().maxRequestsPerHost, base.dispatcher.maxRequestsPerHost)
        assertEquals(base.readTimeoutMillis, model.readTimeoutMillis)
        assertEquals(base.writeTimeoutMillis, model.writeTimeoutMillis)
        assertEquals(ProviderBalanceFetcher.TOTAL_TIMEOUT_MS.toInt(), balance.callTimeoutMillis)
        assertEquals(base.interceptors, model.interceptors)
        assertEquals(base.networkInterceptors, balance.networkInterceptors)
    }

    @Test fun moreThanTheLegacyHostLimitCanStartLongModelCallsTogether() {
        val count = Dispatcher().maxRequestsPerHost + 1
        val entered = CountDownLatch(count)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(count)
        val client = AgentHttpClient.modelClient.newBuilder().addInterceptor { chain ->
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) throw IOException("Test stream was not released")
            response(chain.request(), "data: [DONE]\n\n")
        }.build()
        val calls = (0 until count).map { client.newCall(request("model-$it")) }
        try {
            calls.forEach { it.enqueue(closingCallback(finished)) }
            assertTrue("A same-host model call was held in the legacy queue", entered.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            calls.forEach { it.cancel() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun balanceCompletesWhileTheSharedBaseHostQueueIsFull() = runBlocking {
        val count = AgentHttpClient.client.dispatcher.maxRequestsPerHost
        val entered = CountDownLatch(count)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(count)
        val holdingClient = AgentHttpClient.client.newBuilder().addInterceptor { chain ->
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) throw IOException("Test stream was not released")
            response(chain.request(), "held")
        }.build()
        val calls = (0 until count).map { holdingClient.newCall(request("hold-$it")) }
        val balanceClient = ProviderBalanceFetcher.balanceClient.newBuilder().addInterceptor { chain ->
            response(chain.request(), "{\"remaining\":123.5}")
        }.build()
        val option = BalanceOption(enabled = true, apiPath = "balance", resultPath = "remaining")
        val provider = OpenAiCompatibleProviderSetting(
            id = "concurrency", name = "Concurrency",
            baseUrl = "https://concurrency.example/v1", balanceOption = option,
        )
        try {
            calls.forEach { it.enqueue(closingCallback(finished)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val balance = withTimeout(2_000) { ProviderBalanceFetcher.fetch(provider, option, balanceClient) }
            assertEquals("123.5", balance.getOrThrow())
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            calls.forEach { it.cancel() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        }
    }

    private fun request(path: String) = Request.Builder().url("https://concurrency.example/$path").build()

    private fun response(request: Request, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody()).build()

    private fun closingCallback(finished: CountDownLatch) = object : Callback {
        override fun onFailure(call: Call, e: IOException) { finished.countDown() }
        override fun onResponse(call: Call, response: Response) {
            response.close()
            finished.countDown()
        }
    }
}
