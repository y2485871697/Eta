package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import java.net.InetSocketAddress
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesEnvelopeIntegrationTest {
    private fun error() = JSONObject().put("code", "server_error").put("message",
        "basispoints tool transport code must contain one JSON client-tool envelope; " +
        "OfficeJS and multiple calls are unsupported (format=json_object; bytes=7941; " +
        "json_offset=589; json_failure=missing-separator)")
    private fun frame(type: String, body: JSONObject) = "data: " + body.put("type", type) + "\n\n"
    private fun success() = frame("response.completed", JSONObject().put("response", JSONObject()
        .put("status", "completed").put("output", JSONArray().put(JSONObject()
            .put("type", "message").put("id", "m").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "done")))))))
    private fun request(url: String) = ProviderRequest(AgentModelClient.ModelConfig(
        baseUrl = url, apiKey = "test", model = "test", systemPrompt = "original instructions",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES, browserTools = false,
    ), JSONArray(), JSONArray())
    private fun run(url: String, callback: (Int, ProviderEvent) -> Unit = { _, _ -> }) = AgentModelRetry { _, _ -> }.complete(
        1, request(url), OpenAiResponsesProvider, AgentRunController(), {}, callback, {},
    )
    @Test fun httpAndFlattenedSseRejectionsRegenerateOnce() {
        for (http in listOf(true, false)) {
            server({ n -> if (n > 1) 200 to success() else if (http)
                500 to JSONObject().put("error", error()).toString()
                else 200 to frame("error", error()) }) { url, calls ->
                assertEquals("done", run(url).response.assistantMessage.optString("content"))
                assertEquals(2, calls.get())
            }
        }
    }
    @Test fun rawTerminalTextOrToolOutputPreventsSecondRequest() {
        for (item in listOf(
            JSONObject().put("type", "message").put("content", JSONArray().put(
                JSONObject().put("type", "output_text").put("text", "already shown"))),
            JSONObject().put("type", "function_call")
        )) for (http in listOf(true, false)) {
            val response = JSONObject().put("status", "failed").put("error", error())
                .put("output", JSONArray().put(item))
            server({ _ -> if (http) 500 to response.toString() else
                200 to frame("response.failed", JSONObject().put("response", response)) }) { url, calls ->
                assertThrows(AgentModelFailure::class.java) { run(url) }
                assertEquals(1, calls.get())
            }
        }
    }
    @Test fun responseHeadersCallbackFailureIsPreserved() {
        val original = IOException("callback persistence failed")
        server({ _ -> 500 to JSONObject().put("error", error()).toString() }) { url, calls ->
            val thrown = assertThrows(IOException::class.java) {
                run(url) { _, event -> if (event is ProviderEvent.ResponseHeaders) throw original }
            }
            assertSame(original, thrown)
            assertEquals(1, calls.get())
        }
    }
    @Test fun correctedRequestsIncludingTransientFailuresAreBounded() {
        server({ n -> if (n == 2) 503 to "temporary" else
            500 to JSONObject().put("error", error()).toString() }) { url, calls ->
            assertThrows(AgentModelFailure::class.java) { run(url) }
            assertEquals(3, calls.get())
        }
    }
    private fun server(reply: (Int) -> Pair<Int, String>, block: (String, AtomicInteger) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val calls = AtomicInteger()
        server.executor = executor
        server.createContext("/responses") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val (code, body) = reply(calls.incrementAndGet())
            exchange.responseHeaders.set("Content-Type", if (code == 200) "text/event-stream" else "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}", calls) }
        finally { server.stop(0); executor.shutdownNow() }
    }
}
