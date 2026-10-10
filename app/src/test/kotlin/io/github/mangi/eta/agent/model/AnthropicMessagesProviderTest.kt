package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.ProviderTypes
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMessagesProviderTest {
    @Test
    fun steeringDiscardsPartialToolInputInsteadOfInferringToolUse() {
        val controller = AgentRunController()
        val body = event("content_block_start", JSONObject().put("type", "content_block_start").put("index", 0)
            .put("content_block", JSONObject().put("type", "tool_use").put("id", "draft")
                .put("name", "terminal").put("input", JSONObject()))) +
            event("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", 0)
                .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "{}")))
        withAnthropicServer(body, onRequest = {}) { baseUrl ->
            val result = AnthropicMessagesProvider.complete(ProviderRequest(
                AgentModelClient.ModelConfig(providerType = ProviderTypes.ANTHROPIC,
                    baseUrl = baseUrl, apiKey = "test", model = "test", systemPrompt = ""),
                JSONArray(), JSONArray()), controller,
            ) { event ->
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TOOL_CALL) {
                    controller.steer("new instruction")
                }
            }
            assertEquals(AssistantStopReason.INTERRUPTED, result.stopReason)
            assertTrue(!result.assistantMessage.has("tool_calls"))
        }
    }

    @Test
    fun completeParsesTextAndToolUseStream() {
        val body = buildString {
            append(event("content_block_start", JSONObject()
                .put("type", "content_block_start")
                .put("index", 0)
                .put("content_block", JSONObject().put("type", "text"))))
            append(event("content_block_delta", JSONObject()
                .put("type", "content_block_delta")
                .put("index", 0)
                .put("delta", JSONObject().put("type", "text_delta").put("text", "Hello"))))
            append(event("content_block_stop", JSONObject()
                .put("type", "content_block_stop")
                .put("index", 0)))
            append(event("content_block_start", JSONObject()
                .put("type", "content_block_start")
                .put("index", 1)
                .put("content_block", JSONObject()
                    .put("type", "tool_use")
                    .put("id", "toolu_1")
                    .put("name", "observe_screen")
                    .put("input", JSONObject()))))
            append(event("content_block_delta", JSONObject()
                .put("type", "content_block_delta")
                .put("index", 1)
                .put("delta", JSONObject()
                    .put("type", "input_json_delta")
                    .put("partial_json", "{\"include_screenshot\":true}"))))
            append(event("content_block_stop", JSONObject()
                .put("type", "content_block_stop")
                .put("index", 1)))
            append(event("message_delta", JSONObject()
                .put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", "tool_use"))
                .put("usage", JSONObject().put("input_tokens", 4).put("output_tokens", 2))))
            append(event("message_stop", JSONObject().put("type", "message_stop")))
        }

        val requestBody = AtomicReference<String>()
        withAnthropicServer(body, onRequest = requestBody::set) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerType = ProviderTypes.ANTHROPIC,
                        baseUrl = baseUrl,
                        apiKey = "key",
                        model = "claude-sonnet-5",
                        systemPrompt = "system"
                    ),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                    tools = JSONArray().put(
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", "observe_screen")
                                    .put("description", "observe")
                                    .put("parameters", JSONObject().put("type", "object"))
                            )
                    )
                ),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("Hello", response.assistantMessage.getString("content"))
            val toolCall = response.assistantMessage.getJSONArray("tool_calls").getJSONObject(0)
            assertEquals("toolu_1", toolCall.getString("id"))
            assertEquals("observe_screen", toolCall.getJSONObject("function").getString("name"))
            assertEquals(
                "{\"include_screenshot\":true}",
                toolCall.getJSONObject("function").getString("arguments")
            )
            assertTrue(requestBody.get().contains("\"tools\""))
            val sentBody = JSONObject(requestBody.get())
            assertEquals("ephemeral", sentBody.getJSONArray("tools").getJSONObject(0)
                .getJSONObject("cache_control").getString("type"))
            assertEquals("ephemeral", sentBody.getJSONArray("messages").getJSONObject(0)
                .getJSONArray("content").getJSONObject(0)
                .getJSONObject("cache_control").getString("type"))
            assertEquals(
                "Hello",
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.TEXT }
                    .joinToString("") { it.delta }
            )
            assertEquals(
                listOf(
                    "start:TEXT:0",
                    "delta:TEXT:0:Hello",
                    "end:TEXT:0",
                    "start:TOOL_CALL:1",
                    "delta:TOOL_CALL:1:{\"include_screenshot\":true}",
                    "end:TOOL_CALL:1",
                ),
                events.mapNotNull { event ->
                    when (event) {
                        is ProviderEvent.BlockStart -> "start:${event.kind}:${event.index}"
                        is ProviderEvent.BlockDelta -> "delta:${event.kind}:${event.index}:${event.delta}"
                        is ProviderEvent.BlockEnd -> "end:${event.kind}:${event.index}"
                        else -> null
                    }
                },
            )
            assertEquals(1, events.filterIsInstance<ProviderEvent.Usage>().size)
        }
    }

    @Test
    fun completeBuildsAdaptiveThinkingRequestWhenEnabled() {
        val body = buildString {
            append(event("message_stop", JSONObject().put("type", "message_stop")))
        }

        val requestBody = AtomicReference<String>()
        withAnthropicServer(body, onRequest = requestBody::set) { baseUrl ->
            AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerType = ProviderTypes.ANTHROPIC,
                        providerSourceType = "anthropic",
                        baseUrl = baseUrl,
                        apiKey = "key",
                        model = "claude-sonnet-5",
                        systemPrompt = "system",
                        thinkingEnabled = true,
                        reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.MEDIUM,
                    ),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
            )

            val request = JSONObject(requestBody.get())
            assertEquals("adaptive", request.getJSONObject("thinking").getString("type"))
            assertEquals("medium", request.getJSONObject("output_config").getString("effort"))
        }
    }

    @Test
    fun completeReportsCacheInclusivePromptTokensFromStreamUsage() {
        val body = buildString {
            append(event("message_start", JSONObject()
                .put("type", "message_start")
                .put("message", JSONObject().put("usage", JSONObject()
                    .put("input_tokens", 3)
                    .put("cache_read_input_tokens", 120_000)
                    .put("cache_creation_input_tokens", 2_500)
                    .put("output_tokens", 1)))))
            append(event("message_delta", JSONObject()
                .put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", "end_turn"))
                .put("usage", JSONObject()
                    .put("input_tokens", 3)
                    .put("cache_read_input_tokens", 120_000)
                    .put("cache_creation_input_tokens", 2_500)
                    .put("output_tokens", 42))))
            append(event("message_stop", JSONObject().put("type", "message_stop")))
        }

        withAnthropicServer(body, onRequest = {}) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerType = ProviderTypes.ANTHROPIC,
                        baseUrl = baseUrl,
                        apiKey = "key",
                        model = "claude-sonnet-5",
                        systemPrompt = "system"
                    ),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
                onEvent = events::add,
            )

            val usages = events.filterIsInstance<ProviderEvent.Usage>().map { it.usage }
            assertEquals(2, usages.size)
            usages.forEach { usage ->
                assertEquals(122_503, usage.inputTokens)
                assertEquals(120_000, usage.cachedTokens)
                assertEquals(2_500, usage.cacheCreationTokens)
                assertEquals(122_503, usage.occupancyTokens())
            }
            assertEquals(42, usages.last().outputTokens)
        }
    }

    /**
     * 复现线上的圆环抖动：流开头只报未缓存部分（input 28225 / 无缓存 / output 1），
     * 同一次请求结束时才是真实账单（input 240479 / cache 206810）。开头那条不得单独
     * 更新占用，只发布合并后的最终账单。
     */
    @Test
    fun defersOpeningUsageUntilTheRequestEnds() {
        val body = buildString {
            append(event("message_start", JSONObject()
                .put("type", "message_start")
                .put("message", JSONObject().put("usage", JSONObject()
                    .put("input_tokens", 28_225)
                    .put("output_tokens", 1)))))
            append(event("content_block_delta", JSONObject()
                .put("type", "content_block_delta")
                .put("index", 0)
                .put("delta", JSONObject().put("type", "text_delta").put("text", "你好"))))
            append(event("message_delta", JSONObject()
                .put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", "end_turn"))
                .put("usage", JSONObject()
                    .put("input_tokens", 33_669)
                    .put("cache_read_input_tokens", 206_810)
                    .put("output_tokens", 538))))
            append(event("message_stop", JSONObject().put("type", "message_stop")))
        }

        withAnthropicServer(body, onRequest = {}) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerType = ProviderTypes.ANTHROPIC,
                        baseUrl = baseUrl,
                        apiKey = "key",
                        model = "claude-opus-5",
                        systemPrompt = "system"
                    ),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
                onEvent = events::add,
            )

            val usages = events.filterIsInstance<ProviderEvent.Usage>().map { it.usage }
            assertEquals(1, usages.size)
            assertEquals(240_479, usages.single().inputTokens)
            assertEquals(206_810, usages.single().cachedTokens)
            assertEquals(538, usages.single().outputTokens)
        }
    }

    /** 流在最终账单前中断时，被推迟的开头回执仍要发布，圆环不能停在上一轮。 */
    @Test
    fun publishesTheDeferredOpeningUsageWhenTheStreamEndsWithoutAMeasurement() {
        val body = buildString {
            append(event("message_start", JSONObject()
                .put("type", "message_start")
                .put("message", JSONObject().put("usage", JSONObject()
                    .put("input_tokens", 27_346)
                    .put("output_tokens", 1)))))
            append(event("content_block_delta", JSONObject()
                .put("type", "content_block_delta")
                .put("index", 0)
                .put("delta", JSONObject().put("type", "text_delta").put("text", "可以"))))
            append(event("message_stop", JSONObject().put("type", "message_stop")))
        }

        withAnthropicServer(body, onRequest = {}) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            AnthropicMessagesProvider.complete(
                request = ProviderRequest(
                    config = AgentModelClient.ModelConfig(
                        providerType = ProviderTypes.ANTHROPIC,
                        baseUrl = baseUrl,
                        apiKey = "key",
                        model = "claude-opus-5",
                        systemPrompt = "system"
                    ),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
                onEvent = events::add,
            )

            val usages = events.filterIsInstance<ProviderEvent.Usage>().map { it.usage }
            assertEquals(1, usages.size)
            assertEquals(27_346, usages.single().inputTokens)
        }
    }

    @Test
    fun completeSendsStableNativeAffinityAcrossFreshRuntimeRequests() {
        val body = event("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", 0)
            .put("delta", JSONObject().put("type", "text_delta").put("text", "ok"))) +
            event("message_delta", JSONObject().put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", "end_turn"))) +
            event("message_stop", JSONObject().put("type", "message_stop"))
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val headerSessions = java.util.concurrent.CopyOnWriteArrayList<String?>()
        withAnthropicServer(body, onRequest = bodies::add,
            onHeaders = { headerSessions.add(it.getFirst("session-id")) }) { baseUrl ->
            repeat(2) {
                AnthropicMessagesProvider.complete(
                    ProviderRequest(
                        AgentModelClient.ModelConfig(providerType = ProviderTypes.ANTHROPIC,
                            baseUrl = baseUrl, apiKey = "test", model = "claude-test", systemPrompt = ""),
                        JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                        JSONArray(), sessionId = String("conv-restored".toCharArray()),
                    ), AgentRunController(),
                )
            }
        }
        assertEquals(listOf("conv-restored", "conv-restored"), headerSessions)
        assertEquals(2, bodies.size)
        val first = JSONObject(bodies[0])
        val second = JSONObject(bodies[1])
        assertEquals(first.getJSONObject("metadata").getString("user_id"),
            second.getJSONObject("metadata").getString("user_id"))
        assertEquals(first.toString(), second.toString())
        assertTrue(!bodies[0].contains("conv-restored"))
        assertEquals("1h", first.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getJSONObject("cache_control").getString("ttl"))
    }

    private fun event(name: String, data: JSONObject): String =
        "event: $name\ndata: $data\n\n"

    private fun withAnthropicServer(
        body: String,
        onRequest: (String) -> Unit,
        onHeaders: (com.sun.net.httpserver.Headers) -> Unit = {},
        block: (String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/v1/messages") { exchange ->
            onHeaders(exchange.requestHeaders)
            onRequest(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
