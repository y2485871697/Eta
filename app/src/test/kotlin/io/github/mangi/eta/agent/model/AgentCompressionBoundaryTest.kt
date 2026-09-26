package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentCompressionBoundaryTest {
    @get:Rule val timeout = org.junit.rules.Timeout.seconds(45)
    @get:Rule val temporary = TemporaryFolder()
    private fun message(role: String, text: String = "", id: String = "", calls: String = "") =
        AgentModelClient.ConversationMessage(role, content = text, toolCallId = id, toolCallsJson = calls)

    @Test fun defaultPressureStartsAtEightyPercent() {
        val history = listOf(message("user", "old"), message("assistant", "result"), message("user", "current"))
        assertFalse(AgentContextCompactor.shouldCompress(history, 100_000, 1, estimatedTokens = 79_999))
        assertTrue(AgentContextCompactor.shouldCompress(history, 100_000, 1, estimatedTokens = 80_000))
    }

    @Test fun compressionCutsDoNotDependOnRequestRoundsOrSupplementTurnIds() {
        val history = listOf(message("user", "task"), message("assistant", "x".repeat(8000)),
            message("user", AgentContextCompactor.steeringUserContent("supplement")),
            message("assistant", calls = """[{"id":"a"},{"id":"b"}]"""),
            message("tool", "a", id = "a"), message("tool", "b", id = "b"))
        val sameTurn = history.map { it.copy(turnId = "original-turn") }
        val separateIds = history.mapIndexed { i, entry -> entry.copy(turnId = "request-$i") }
        val cut = AgentCompressionBoundary.selectStart(sameTurn, 10_000)
        assertTrue(cut > 0)
        assertEquals(cut, AgentCompressionBoundary.selectStart(separateIds, 10_000))
        assertTrue(cut in AgentCompressionBoundary.balancedCuts(sameTurn))
        assertTrue(cut <= 3) // Never split the latest parallel tool batch.
    }

    @Test fun parallelToolResultsCannotBeSplit() {
        val history = listOf(message("user", "old"),
            message("assistant", calls = "[{\"id\":\"a\"},{\"id\":\"b\"}]"),
            message("tool", "one", id = "a"), message("tool", "two", id = "b"), message("assistant", "done"))
        assertEquals(listOf(0, 1, 4, 5), AgentCompressionBoundary.balancedCuts(history))
        assertEquals(4, AgentCompressionBoundary.continuationStart(history, 1))
        assertEquals(1, AgentCompressionBoundary.continuationStart(history.dropLast(1), 1))
    }

    @Test fun continuationRetentionMatchesHarnessRatio() {
        assertEquals(3_200, AgentCompressionBoundary.continuationRetentionBudget(20_000))
        assertEquals(8_000, AgentCompressionBoundary.continuationRetentionBudget(50_000))
        assertEquals(20_480, AgentCompressionBoundary.continuationRetentionBudget(128_000))
        assertEquals(80_000, AgentCompressionBoundary.continuationRetentionBudget(500_000))
        assertEquals(1, AgentCompressionBoundary.continuationRetentionBudget(500_000, overflow = true))
    }

    @Test fun continuationUsesTheSameTokenTailInIdleAndActiveRuns() {
        val history = listOf(message("user", "one long task"),
            message("assistant", calls = """[{"id":"old"}]"""),
            message("tool", "x".repeat(20_000), id = "old"),
            message("assistant", calls = """[{"id":"latest"}]"""),
            message("tool", "y".repeat(8_000), id = "latest"))
        val idle = AgentCompressionBoundary.selectStart(history, 10_000)
        val active = AgentCompressionBoundary.selectStart(history, 10_000)
        assertEquals(3, idle)
        assertEquals(idle, active)
        assertTrue(AgentContextCompactor.shouldCompress(history, 10_000, 0,
            estimatedTokens = 9500))
    }

    @Test fun overflowCanReduceRetentionButNeverSplitsTheLatestParallelBatch() {
        val history = listOf(message("user", "task"), message("assistant", "x".repeat(8000)),
            message("assistant", calls = """[{"id":"a"},{"id":"b"}]"""),
            message("tool", "a", id = "a"), message("tool", "b", id = "b"))
        assertEquals(1, AgentCompressionBoundary.selectStart(history, 10_000))
        assertEquals(2, AgentCompressionBoundary.selectStart(history, 10_000, overflow = true))
    }

    @Test fun orphanedOrUnfinishedToolsCannotBeCompacted() {
        assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.balancedCuts(listOf(message("tool", "orphan", id = "a"))) }
        assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.balancedCuts(listOf(message("assistant", calls = "[{\"id\":\"a\"}]"))) }
    }

    @Test fun budgetsReserveOutputAndSafetyMargin() {
        assertEquals(4392, AgentCompressionBoundary.inputLimit(9000))
        assertEquals(0, AgentCompressionBoundary.inputLimit(100))
        assertEquals(6000, AgentCompressionBoundary.outputReserve(config().copy(extraBodyJson = "{\"max_tokens\":6000}")))
    }

    @Test fun summaryInputKeepsToolArgumentsCorrelationAndStructuredTextNotHiddenReasoning() {
        val input = message("assistant", calls = "[{\"id\":\"c1\",\"function\":{\"name\":\"terminal\",\"arguments\":\"exact command\"}}]")
            .copy(contentJson = "[{\"type\":\"text\",\"text\":\"structured evidence\"}]", reasoningContent = "PRIVATE_REASONING")
        val text = AgentContextCompactor.messageToSummaryText(input)
        assertTrue(text.contains("exact command"))
        assertTrue(text.contains("structured evidence"))
        assertFalse(text.contains("PRIVATE_REASONING"))
        assertTrue(AgentContextCompactor.messageToSummaryText(message("tool", "exit_code=0", "c1")).contains("c1"))
    }

    @Test fun manualRequestsAreNotInheritedByAnotherController() {
        val controller = AgentRunController()
        controller.requestCompact()
        assertTrue(controller.hasPendingCompact)
        assertFalse(AgentRunController().hasPendingCompact)
    }

    @Test fun strictOverflowPausesWithoutSendingOrDeletingHistory() {
        val controller = AgentRunController()
        val source = JSONArray().put(JSONObject().put("role", "system").put("content", "x".repeat(40_000)))
            .put(AgentConversationCodec.userTextMessage("protected user"))
        val original = source.toString()
        val provider = provider { error("Over-budget request must never be sent") }
        var paused = false
        val loop = AgentLoop(config(9000), source, JSONArray(), provider,
            AgentModelClient.ToolExecutor { error("No tools") }, controller, AgentTraceFormatter(),
            systemCount = 1, onEvent = { if (it is AgentEvent.ContextCompacted && it.blocked) { paused = true; controller.cancel() } })
        assertThrows(AgentRunCancelledException::class.java) { loop.run() }
        assertTrue(paused)
        source.getJSONObject(1).remove(AgentTurnIdentity.JSON_KEY)
        assertEquals(original, source.toString())
    }

    @Test fun forcedCompressionPreservesLongLiveToolBodyAndProviderFields() {
        val controller = AgentRunController()
        val toolBody = "Z".repeat(70_000)
        val currentUser = AgentConversationCodec.userTextMessage("active request").put("provider_private", JSONObject().put("opaque", "keep"))
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("old question " + "x".repeat(8000)))
            .put(JSONObject().put("role", "assistant").put("content", "old answer"))
            .put(currentUser)
        var calls = 0
        var compressed = false
        val provider = provider { request ->
            if (calls++ == 0) JSONObject().put("role", "assistant").put("content", "")
                .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", "tool-1").put("type", "function").put("function", JSONObject()
                        .put("name", "get_current_context").put("arguments", "{}"))))
            else {
                val liveCall = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                    .single { it.has("tool_calls") }
                assertEquals("keep", liveCall.getJSONObject("provider_private").getString("opaque"))
                val result = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                    .single { it.optString("role") == "tool" }
                assertEquals(toolBody, result.getString("content"))
                JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
            }
        }
        val model = config(100_000)
        AgentLoop(model, source, AgentToolCatalog.build(terminalTools = false, browserTools = false), provider,
            AgentModelClient.ToolExecutor {
                // Inject into the actual replay snapshot, after normal response serialization.
                source.getJSONObject(source.length() - 1).put("provider_private", JSONObject().put("opaque", "keep"))
                controller.requestCompact(1)
                AgentModelClient.ToolResult(toolBody)
            },
            controller, AgentTraceFormatter(), onEvent = { if (it is AgentEvent.ContextCompacted && it.applied) compressed = true },
            compactPolicy = AgentLoop.CompactPolicy(false, 100_000, 1, model),
            compactHistory = { history, policy -> listOf(message("user", "[Conversation summary]\nold work")) + history.drop(requireNotNull(policy.keepStartOverride)) },
        ).run()
        assertTrue(compressed)
        assertEquals(2, calls)
    }

    @Test fun firstRequestCompactsAtPressureBeforeSendingAnOtherwiseValidRequest() {
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("x".repeat(352_000)))
            .put(JSONObject().put("role", "assistant").put("content", "old result"))
            .put(AgentConversationCodec.userTextMessage("y".repeat(8000)))
        val model = config(100_000)
        var compacted = false
        var requests = 0
        AgentLoop(model, source, JSONArray(), provider { request ->
            requests++
            assertTrue(compacted)
            assertTrue(request.messages.getJSONObject(0).getString("content").contains("summary"))
            assertFalse(request.messages.toString().contains("x".repeat(100)))
            JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
        }, AgentModelClient.ToolExecutor { error("No tools") }, AgentRunController(), AgentTraceFormatter(),
            onEvent = { if (it is AgentEvent.ContextCompacted && it.applied) compacted = true },
            compactPolicy = AgentLoop.CompactPolicy(true, 100_000, 1, model),
            compactHistory = { history, policy -> listOf(message("user", "[Conversation summary]\nold task")) + history.drop(requireNotNull(policy.keepStartOverride)) },
        ).run()
        assertEquals(1, requests)
    }

    @Test fun manualContinuationPrunesOldStepsButPreservesLatestBatchWithinOneRun() {
        val controller = AgentRunController()
        val model = config(20_000)
        val events = mutableListOf<AgentEvent>()
        val tail = "L".repeat(16_000)
        var requests = 0
        var toolRuns = 0
        var summaries = 0
        val result = AgentLoop(model, JSONArray().put(AgentConversationCodec.userTextMessage("one long task")),
            JSONArray("""[{"type":"function","function":{"name":"get_current_context","parameters":{"type":"object","properties":{}}}}]"""), provider { request ->
                requests++
                if (requests <= 2) JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                        .put("id", "call-$requests").put("type", "function").put("function", JSONObject()
                            .put("name", "get_current_context").put("arguments", "{}"))))
                else {
                    assertEquals(1, summaries)
                    val liveTool = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                        .single { it.optString("role") == "tool" }
                    assertEquals("call-2", liveTool.getString("tool_call_id"))
                    assertEquals(tail, liveTool.getString("content"))
                    JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
                }
            }, AgentModelClient.ToolExecutor {
                toolRuns++
                if (toolRuns == 2) controller.requestCompact(0)
                AgentModelClient.ToolResult(if (toolRuns == 1) "O".repeat(20_000) else tail)
            }, controller, AgentTraceFormatter(), onEvent = {
                if (it is AgentEvent.ContextCompacted) assertFalse(it.reason, it.blocked)
                events += it
            },
            compactPolicy = AgentLoop.CompactPolicy(false, 20_000, 0, model),
            compactionArchive = AgentCompactionArchive(temporary.root, "same-run"),
            compactHistory = { history, _ ->
                summaries++
                assertTrue(history[2].content.contains("[Eta tool output pruned;"))
                assertEquals(tail, history.last().content)
                val cut = AgentCompressionBoundary.selectStart(history, 20_000)
                assertEquals(3, cut)
                listOf(message("user", "[Conversation summary]\nfirst step verified")) + history.drop(cut)
            },
        ).run()
        assertEquals("done", result.content)
        assertEquals(3, requests)
        assertEquals(2, toolRuns)
        assertEquals(2, events.filterIsInstance<AgentEvent.ContextCompacted>().count { it.applied })
    }

    @Test fun continuationCompactionRedactsSensitiveToolsInsteadOfFailing() {
        val controller = AgentRunController()
        val model = config(20_000)
        val events = mutableListOf<AgentEvent>()
        val tail = "L".repeat(16_000)
        val secret = "visible-pixels-secret"
        var requests = 0
        var toolRuns = 0
        var summaries = 0
        val tools = JSONArray(
            """[{"type":"function","function":{"name":"read_image","parameters":{"type":"object","properties":{}}}},""" +
                """{"type":"function","function":{"name":"get_current_context","parameters":{"type":"object","properties":{}}}}]"""
        )
        val result = AgentLoop(
            model,
            JSONArray().put(AgentConversationCodec.userTextMessage("one long task " + "x".repeat(20_000))),
            tools,
            provider { request ->
                requests++
                when (requests) {
                    1 -> JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put(
                            "tool_calls",
                            JSONArray().put(
                                JSONObject().put("id", "call-image").put("type", "function").put(
                                    "function",
                                    JSONObject().put("name", "read_image").put("arguments", """{"path":"/secret.jpg"}"""),
                                ),
                            ),
                        )
                    2 -> JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put(
                            "tool_calls",
                            JSONArray().put(
                                JSONObject().put("id", "call-live").put("type", "function").put(
                                    "function",
                                    JSONObject().put("name", "get_current_context").put("arguments", "{}"),
                                ),
                            ),
                        )
                    else -> {
                        assertEquals(1, summaries)
                        assertFalse(request.messages.toString().contains(secret))
                        JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
                    }
                }
            },
            AgentModelClient.ToolExecutor { call ->
                toolRuns++
                if (call.name == "read_image") AgentModelClient.ToolResult(secret, sensitive = true)
                else {
                    controller.requestCompact(0)
                    AgentModelClient.ToolResult(tail)
                }
            },
            controller,
            AgentTraceFormatter(),
            onEvent = {
                if (it is AgentEvent.ContextCompacted) {
                    assertFalse(it.reason, it.blocked)
                    assertFalse(it.reason.contains("不允许持久化"))
                }
                events += it
            },
            compactPolicy = AgentLoop.CompactPolicy(false, 20_000, 0, model),
            compactionArchive = AgentCompactionArchive(temporary.root, "sensitive-run"),
            compactHistory = { history, _ ->
                summaries++
                val encoded = history.joinToString { it.content + it.toolCallsJson }
                assertFalse(encoded.contains(secret))
                assertFalse(encoded.contains("/secret.jpg"))
                assertEquals(tail, history.last().content)
                val lastBulk = history.indexOfLast { it.content == tail }
                val keepFrom = if (lastBulk > 0 && history[lastBulk - 1].toolCallsJson.contains("get_current_context")) {
                    lastBulk - 1
                } else {
                    lastBulk.coerceAtLeast(0)
                }
                assertFalse(history.take(keepFrom).joinToString { it.content + it.toolCallsJson }.contains(secret))
                listOf(message("user", "[Conversation summary]\nlooked at an image")) + history.drop(keepFrom)
            },
        ).run()
        assertEquals("done", result.content)
        assertEquals(3, requests)
        assertEquals(2, toolRuns)
        assertEquals(1, summaries)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.applied })
        val archived = temporary.root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(archived.contains(secret))
        assertFalse(archived.contains("/secret.jpg"))
    }

    @Test fun providerOverflowDoesNotBlindlyRetryAnUnchangedRequest() {
        var calls = 0
        val controller = AgentRunController()
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("active"))
        val loop = AgentLoop(config(9000), source, JSONArray(), provider {
            calls++
            throw AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "too large")
        }, AgentModelClient.ToolExecutor { error("No tools") }, controller, AgentTraceFormatter(),
            onEvent = { if (it is AgentEvent.ContextCompacted && it.blocked) controller.cancel() })
        assertThrows(AgentRunCancelledException::class.java) { loop.run() }
        assertEquals(1, calls)
        assertEquals("active", source.getJSONObject(0).getString("content"))
    }

    @Test fun largeWindowDoesNotPauseAtHalfCapacityJustBecauseHistoryIsLong() {
        var calls = 0
        val controller = AgentRunController()
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("x".repeat(900_000)))
        val loop = AgentLoop(
            config(500_000),
            source,
            JSONArray(),
            provider {
                calls++
                JSONObject().put("role", "assistant").put("content", "ok").put("finish_reason", "stop")
            },
            AgentModelClient.ToolExecutor { error("No tools") },
            controller,
            AgentTraceFormatter(),
            onEvent = {},
        )
        val result = loop.run()
        assertEquals("ok", result.content)
        assertEquals(1, calls)
    }

    @Test fun overflowClassificationDoesNotTreatAllBadRequestsAsContextErrors() {
        assertEquals("CONTEXT_WINDOW_EXCEEDED", AgentModelFailure.http(400, "{\"error\":{\"code\":\"context_length_exceeded\"}}").code)
        assertEquals("HTTP_400", AgentModelFailure.http(400, "{\"error\":{\"message\":\"unknown parameter\"}}").code)
        assertEquals("HTTP_401", AgentModelFailure.http(401, "{\"error\":{\"code\":\"context_length_exceeded\"}}").code)
    }

    @Test fun compressionEndpointOverridesOpenAiCompatibleProviders() {
        val responses = config().copy(openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        val chat = AgentCompressionEndpoint.apply(responses, io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS)
        assertEquals(io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS, chat.openAiEndpointMode)
        val back = AgentCompressionEndpoint.apply(chat, io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        assertEquals(io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES, back.openAiEndpointMode)
    }

    @Test fun compressionEndpointDoesNotOverrideCodexOrRemovedBackend() {
        val codex = config().copy(
            baseUrl = "https://chatgpt.com/backend-api/codex",
            openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES,
        )
        assertEquals(codex, AgentCompressionEndpoint.apply(codex, io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS))
        val removed = config().copy(
            baseUrl = "https://cloudcode-pa.googleapis.com",
            openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES,
        )
        assertEquals(removed, AgentCompressionEndpoint.apply(removed, io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS))
    }

    @Test fun queuedManualEndpointOnlyOverridesThatCompression() {
        val automatic = config(100_000)
        val manual = automatic.copy(model = "manual-summary",
            openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        val controller = AgentRunController()
        controller.requestCompact(compressModelConfig = manual)
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("old ".repeat(4000)))
            .put(AgentConversationCodec.userTextMessage("latest"))
        var summaryCalls = 0
        AgentLoop(automatic, source, JSONArray(), provider { request ->
            assertEquals(automatic.model, request.config.model)
            assertEquals(automatic.openAiEndpointMode, request.config.openAiEndpointMode)
            JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
        }, AgentModelClient.ToolExecutor { error("No tools") }, controller, AgentTraceFormatter(),
            onEvent = {}, compactPolicy = AgentLoop.CompactPolicy(false, 100_000, 0, automatic),
            compactHistory = { history, policy ->
                summaryCalls++
                assertEquals(manual, policy.compressModelConfig)
                listOf(message("user", "[Conversation summary] old")) + history.drop(requireNotNull(policy.keepStartOverride))
            }).run()
        assertEquals(1, summaryCalls)
    }

    @Test fun compressionEndpointLeavesNativeProviderAndSessionUntouched() {
        val native = config().copy(providerType = io.github.mangi.eta.data.model.ProviderTypes.ANTHROPIC)
        assertEquals(native, AgentCompressionEndpoint.apply(native, io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES))
        val session = config().copy(openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        assertEquals(io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS,
            AgentCompressionEndpoint.apply(session, null).openAiEndpointMode)
        assertEquals(io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES, session.openAiEndpointMode)
    }

    private fun config(window: Int = 9000) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test", systemPrompt = "", contextWindow = window,
    )

    private fun provider(block: (ProviderRequest) -> JSONObject) = object : AgentProviderClient {
        override val id = "test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            ProviderResponse(block(request))
    }
}
