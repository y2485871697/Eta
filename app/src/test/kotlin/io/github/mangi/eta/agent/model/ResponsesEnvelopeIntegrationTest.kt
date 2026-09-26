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
    @Test fun screenshotErrorRecoversThroughHttpAndBothSseEnvelopesAndConstrainsLaterRequests() {
        for (kind in listOf("http", "error", "response.failed")) {
            val bodies = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
            val rejection = error().put("message", error().getString("message").replace("7941", "5003").replace("589", "761").replace("missing-separator", "missing_separator"))
            server(reply = { n -> if (n > 1) 200 to success() else when (kind) {
                "http" -> 500 to JSONObject().put("error", rejection).toString()
                "error" -> 200 to frame("error", rejection)
                else -> 200 to frame("response.failed", JSONObject().put("response", JSONObject().put("status", "failed").put("error", rejection).put("output", JSONArray())))
            } }, onRequest = bodies::add) { url, calls ->
                val original = request(url).copy(tools = JSONArray().put(AgentToolSchema.function("read_file", "read", JSONObject().put("type", "object"))))
                fun execute() = AgentModelRetry { _, _ -> }.complete(1, original, OpenAiResponsesProvider, AgentRunController(), {}, { _, _ -> }, {})
                assertEquals("done", execute().response.assistantMessage.getString("content"))
                assertEquals(2, calls.get())
                assertFalse(bodies[1].getBoolean("parallel_tool_calls"))
                assertTrue(bodies[1].getString("instructions").contains(ResponsesToolEnvelopeRecovery.CORRECTION))
                execute()
                assertEquals(3, calls.get())
                assertFalse(bodies[2].getBoolean("parallel_tool_calls"))
                assertTrue(bodies[2].getString("instructions").contains(ResponsesToolEnvelopeRecovery.SERIALIZATION))
                assertFalse(original.singleToolCall)
                assertEquals(0, original.messages.length())
            }
        }
    }

    @Test fun rejectedNextRoundDoesNotReplayCompletedToolsOrPersistCorrectionHint() {
        val bodies = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val executed = mutableListOf<String>()
        val error = error().put("message", error().getString("message").replace("missing-separator", "missing_separator"))
        fun tool(id: String) = frame("response.completed", JSONObject().put("response", JSONObject()
            .put("status", "completed").put("output", JSONArray().put(JSONObject()
                .put("type", "function_call").put("id", "item-$id").put("call_id", id)
                .put("name", "get_current_context").put("arguments", "{}")))))
        server(reply = { n -> when (n) {
            1 -> 200 to tool("first")
            2 -> 200 to frame("response.failed", JSONObject().put("response", JSONObject()
                .put("status", "failed").put("error", error).put("output", JSONArray())))
            3 -> 200 to tool("second")
            else -> 200 to success()
        } }, onRequest = bodies::add) { url, calls ->
            val messages = JSONArray().put(AgentConversationCodec.userTextMessage("task"))
            val tools = JSONArray().put(AgentToolSchema.function("get_current_context", "read", JSONObject().put("type", "object")))
            val result = AgentLoop(request(url).config, messages, tools, OpenAiResponsesProvider,
                AgentModelClient.ToolExecutor { call -> executed += call.id; AgentModelClient.ToolResult("saved-" + call.id) },
                AgentRunController(), AgentTraceFormatter(), onEvent = {}, turnId = "original-turn").run()
            assertEquals("done", result.content)
            assertEquals(listOf("first", "second"), executed)
            assertEquals(4, calls.get())
            assertTrue(bodies[2].getJSONArray("input").toString().contains("saved-first"))
            assertFalse(bodies[2].getBoolean("parallel_tool_calls"))
            assertFalse(messages.toString().contains(ResponsesToolEnvelopeRecovery.CORRECTION))
            assertFalse(messages.toString().contains("missing_separator"))
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
    @Test fun transientHttpErrorWithOutputForbidsRetryBeforeOrAfterCorrection() {
        for (afterCorrection in listOf(false, true)) for (status in listOf(502, 503, 504)) {
            val output = JSONArray().put(JSONObject().put("type", "function_call")
                .put("name", "already_generated").put("arguments", "{}"))
            val body = JSONObject().put("error", JSONObject().put("message", "temporary"))
                .put("output", output).toString()
            server({ n -> if (afterCorrection && n == 1)
                500 to JSONObject().put("error", error()).toString()
                else status to body }) { url, calls ->
                assertThrows(AgentModelFailure::class.java) { run(url) }
                assertEquals(if (afterCorrection) 2 else 1, calls.get())
            }
        }
    }

    private fun server(reply: (Int) -> Pair<Int, String>, onRequest: (JSONObject) -> Unit = {}, block: (String, AtomicInteger) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val calls = AtomicInteger()
        server.executor = executor
        server.createContext("/responses") { exchange ->
            onRequest(JSONObject(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }))
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
