package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentAutomaticCompactionTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val timeout = Timeout.seconds(45)

    @Test fun highSerializedSizeBelowHardCapDoesNotBypassTokenBoundary() {
        for (decisionTokens in listOf(131_470, 207_999, 208_000)) {
            val messages = escapedHistory()
            assertSoftStoragePressure(messages)
            assertTrue(requestTokens(messages) < AUTO_PRESSURE)
            val reply = assistant()
            val replyTokens = AgentContextBudget.countMessage(AgentConversationCodec.fromJsonObject(reply))
            val provider = ScriptedProvider(listOf({ _, _ ->
                // The decision is the bill plus the newly retained assistant message.
                reply.put("usage", JSONObject().put("prompt_tokens", decisionTokens - replyTokens))
            }))
            val events = mutableListOf<AgentEvent>()
            var compactCalls = 0
            val originalPrefix = messages.getJSONObject(1).getString("content")
            val result = runLoop(messages, provider, events, compactHistory = { source, policy ->
                compactCalls++
                summarize(source, policy)
            })

            assertEquals("done", result.content)
            assertEquals(1, provider.requests.size)
            val bill = events.filterIsInstance<AgentEvent.UsageReceived>().single { !it.projected }
            assertEquals(decisionTokens, requireNotNull(bill.usage.inputTokens) + replyTokens)
            if (decisionTokens < AUTO_PRESSURE) {
                assertEquals(0, compactCalls)
                assertFalse(events.any { it is AgentEvent.ContextCompactionStarted })
                assertEquals(originalPrefix, messages.getJSONObject(1).getString("content"))
            } else {
                assertEquals(1, compactCalls)
                assertUsageBeforeStart(events, decisionTokens)
                assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().single().applied)
            }
        }
    }

    @Test fun freshToolOutputReachingEightyPercentPublishesDecisionBeforeStart() {
        val messages = smallHistory()
        val reply = toolReply("fresh")
        val tool = AgentConversationCodec.parseToolCalls(reply).single()
        val assistantTokens = AgentContextBudget.countMessage(AgentConversationCodec.fromJsonObject(
            AgentConversationCodec.assistantHistoryMessage(reply, listOf(tool))))
        val emptyResultTokens = AgentContextBudget.countMessage(AgentConversationCodec.fromJsonObject(
            AgentConversationCodec.toolResultMessage(tool, AgentModelClient.ToolResult(""))))
        val resultTokens = AUTO_PRESSURE - 131_470 - assistantTokens - emptyResultTokens
        val freshOutput = "x".repeat(resultTokens * 4)
        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(listOf(
            { _, _ -> reply.put("usage", JSONObject().put("prompt_tokens", 131_470)) },
            { request, _ ->
                assertEquals(freshOutput, (0 until request.messages.length())
                    .map { request.messages.getJSONObject(it) }
                    .single { it.optString("tool_call_id") == "fresh" }.getString("content"))
                assistant(promptTokens = 20)
            },
        ))
        var executions = 0
        runLoop(messages, provider, events, toolExecutor = AgentModelClient.ToolExecutor {
            executions++
            AgentModelClient.ToolResult(freshOutput)
        })

        assertEquals(1, executions)
        assertEquals(2, provider.requests.size)
        assertUsageBeforeStart(events, AUTO_PRESSURE)
        val startIndex = events.indexOfFirst { it is AgentEvent.ContextCompactionStarted }
        val billIndex = events.indexOfFirst { it is AgentEvent.UsageReceived && !it.projected }
        val toolIndex = events.indexOfFirst { it is AgentEvent.ToolFinished }
        assertTrue(billIndex < toolIndex && toolIndex < startIndex)
        assertEquals(131_470, (events[billIndex] as AgentEvent.UsageReceived).usage.inputTokens)
    }

    @Test fun finalResponseRefreshesStaleBilledUsageBeforeAutomaticStart() {
        val events = mutableListOf<AgentEvent>()
        // Unlike a tool round, natural completion has no intervening projection.
        val reply = assistant(content = "x".repeat((AUTO_PRESSURE - 131_470 - 3) * 4), promptTokens = 131_470)
        val provider = ScriptedProvider(listOf({ _, _ -> reply }))
        runLoop(smallHistory(), provider, events)

        assertEquals(1, provider.requests.size)
        assertEquals(131_470, events.filterIsInstance<AgentEvent.UsageReceived>().single { !it.projected }.usage.inputTokens)
        assertUsageBeforeStart(events, AUTO_PRESSURE)
    }

    @Test fun postPruneRemeasuresTokensEvenWhenSerializedSizeRemainsAboveSeventyPercent() {
        val messages = JSONArray()
            .put(AgentConversationCodec.userTextMessage("old task"))
            .put(toolReply("old"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "old")
                .put("content", "x".repeat(240_000)).put(AgentTurnIdentity.JSON_KEY, "old-turn"))
        val tail = escapedHistory()
        for (i in 0 until tail.length()) messages.put(tail.getJSONObject(i))
        assertSoftStoragePressure(messages)
        assertTrue(requestTokens(messages) >= AUTO_PRESSURE)
        val protectedTail = messages.getJSONObject(messages.length() - 2).toString()
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val provider = ScriptedProvider(listOf({ request, _ ->
            assertTrue(request.messages.getJSONObject(2).getString("content").contains("[Eta tool output pruned;"))
            assertSoftStoragePressure(request.messages)
            assertTrue(requestTokens(request.messages) < AUTO_PRESSURE)
            assistant(promptTokens = 20)
        }))
        runLoop(messages, provider, events,
            archive = AgentCompactionArchive(temporary.root, "post-prune"),
            compactHistory = { source, _ -> summaries++; source })

        assertEquals(0, summaries)
        assertFalse(events.any { it is AgentEvent.ContextCompactionStarted })
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().single().applied)
        assertEquals(protectedTail, messages.getJSONObject(messages.length() - 3).toString())
    }

    @Test fun manualBelowThresholdStillCompactsWithFreshUsageAndOneStart() {
        val controller = AgentRunController().also { it.requestCompact(keepRecentMessages = 1) }
        val messages = smallHistory()
        val expectedTokens = requestTokens(messages)
        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(listOf({ _, _ -> assistant(promptTokens = 20) }))
        var compactCalls = 0
        runLoop(messages, provider, events, enabled = false, controller = controller,
            compactHistory = { source, policy ->
                compactCalls++
                assertEquals(1, policy.keepRecentMessages)
                summarize(source, policy)
            })

        assertTrue(expectedTokens < AUTO_PRESSURE)
        assertEquals(1, compactCalls)
        assertUsageBeforeStart(events, expectedTokens)
        assertEquals("current task", provider.requests.single()
            .getJSONObject(provider.requests.single().length() - 1).getString("content"))
    }

    @Test fun outputReserveStillEnforcesHardInputBudgetBelowAutoThreshold() {
        val config = modelConfig().copy(extraBodyJson = """{"max_tokens":200000}""")
        val messages = history("x".repeat(24_000), count = 10)
        val decisionTokens = requestTokens(messages)
        val inputLimit = AgentCompressionBoundary.inputLimit(WINDOW, AgentCompressionBoundary.outputReserve(config))
        assertTrue(decisionTokens > inputLimit && decisionTokens < AUTO_PRESSURE)
        val protectedTail = messages.getJSONObject(messages.length() - 2).toString()
        val provider = ScriptedProvider(listOf({ request, _ ->
            assertTrue(requestTokens(request.messages) <= inputLimit)
            assistant(promptTokens = 20)
        }))
        val events = mutableListOf<AgentEvent>()
        runLoop(messages, provider, events, config = config)

        assertUsageBeforeStart(events, decisionTokens)
        assertEquals(1, provider.requests.size)
        assertEquals(protectedTail, messages.getJSONObject(messages.length() - 3).toString())
    }

    @Test fun hardStorageCapStillReducesBelowAutoTokenThreshold() {
        val messages = escapedHistory(charsPerMessage = 40_000)
        val decisionTokens = requestTokens(messages)
        assertTrue(storedChars(messages) > HARD_STORAGE_CAP)
        assertTrue(decisionTokens < AUTO_PRESSURE)
        val protectedTail = messages.getJSONObject(messages.length() - 2).toString()
        val provider = ScriptedProvider(listOf({ request, _ ->
            assertTrue(storedChars(request.messages) <= HARD_STORAGE_CAP)
            assistant(promptTokens = 20)
        }))
        val events = mutableListOf<AgentEvent>()
        runLoop(messages, provider, events)

        assertUsageBeforeStart(events, decisionTokens)
        assertEquals(1, provider.requests.size)
        assertEquals(protectedTail, messages.getJSONObject(messages.length() - 3).toString())
    }

    @Test fun unsafeHardBudgetSummaryBlocksWithoutChangingProtectedHistory() {
        val messages = history("x".repeat(24_000), count = 10)
        val original = messages.toString()
        val provider = ScriptedProvider(emptyList())
        val events = mutableListOf<AgentEvent>()
        assertThrows(AgentRunCancelledException::class.java) {
            runLoop(messages, provider, events,
                config = modelConfig().copy(extraBodyJson = """{"max_tokens":200000}"""),
                compactHistory = { _, _ -> listOf(AgentModelClient.ConversationMessage("system", "lost tail")) })
        }
        assertTrue(provider.requests.isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.blocked })
        assertFalse(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.applied })
        assertEquals(original, messages.toString())
    }

    @Test fun genuineProviderOverflowRecoversBelowThresholdAndRepeatedOverflowStaysBounded() {
        for (repeatOverflow in listOf(false, true)) {
            val messages = smallHistory()
            val decisionTokens = requestTokens(messages)
            val events = mutableListOf<AgentEvent>()
            val provider = ScriptedProvider(listOf(
                { _, _ -> throw overflow() },
                { request, _ ->
                    assertTrue(requestTokens(request.messages) < decisionTokens)
                    assertEquals("current task", request.messages
                        .getJSONObject(request.messages.length() - 1).getString("content"))
                    if (repeatOverflow) throw overflow()
                    assistant(promptTokens = 20)
                },
            ))
            if (repeatOverflow) {
                assertThrows(AgentRunCancelledException::class.java) { runLoop(messages, provider, events) }
                assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.blocked })
            } else {
                assertEquals("done", runLoop(messages, provider, events).content)
            }
            assertEquals(2, provider.requests.size)
            assertTrue(decisionTokens < AUTO_PRESSURE)
            assertUsageBeforeStart(events, decisionTokens)
            assertEquals(1, events.filterIsInstance<AgentEvent.ContextCompacted>().count { it.applied })
        }
    }

    private fun runLoop(
        messages: JSONArray,
        provider: ScriptedProvider,
        events: MutableList<AgentEvent>,
        config: AgentModelClient.ModelConfig = modelConfig(),
        enabled: Boolean = true,
        controller: AgentRunController = AgentRunController(),
        archive: AgentCompactionArchive? = null,
        toolExecutor: AgentModelClient.ToolExecutor = AgentModelClient.ToolExecutor { error("Unexpected tool") },
        compactHistory: (List<AgentModelClient.ConversationMessage>, AgentLoop.CompactPolicy) -> List<AgentModelClient.ConversationMessage> = ::summarize,
    ): AgentLoop.Result = AgentLoop(
        config = config, messages = messages, tools = tools(), provider = provider,
        toolExecutor = toolExecutor, runController = controller, traceFormatter = AgentTraceFormatter(),
        onEvent = { event ->
            events += event
            // Turn an unexpected pause into a deterministic failure, never a hanging test.
            if (event is AgentEvent.ContextCompacted && event.blocked) controller.cancel()
        },
        compactPolicy = AgentLoop.CompactPolicy(enabled, WINDOW, 2, config),
        compactionArchive = archive, turnId = "current-turn", compactHistory = compactHistory,
    ).run()

    private fun assertUsageBeforeStart(events: List<AgentEvent>, decisionTokens: Int) {
        val starts = events.withIndex().filter { it.value is AgentEvent.ContextCompactionStarted }
        assertEquals(1, starts.size)
        val index = starts.single().index
        assertTrue(index > 0)
        val usage = events[index - 1] as? AgentEvent.UsageReceived
            ?: throw AssertionError("Decision usage must immediately precede compaction start")
        assertTrue(usage.projected)
        assertEquals(decisionTokens, usage.usage.inputTokens)
    }

    private fun assertSoftStoragePressure(messages: JSONArray) {
        val chars = storedChars(messages)
        assertTrue("Must exercise the removed 70% serialized-character bypass: $chars", chars > HARD_STORAGE_CAP * 7 / 10)
        assertTrue("Must not exercise hard storage protection: $chars", chars < HARD_STORAGE_CAP)
    }

    private fun storedChars(messages: JSONArray): Long = AgentConversationCodec.transcript(messages, 0)
        .sumOf { AgentConversationCodec.toJsonObject(it).toString().length.toLong() }

    private fun requestTokens(messages: JSONArray): Int =
        AgentContextBudget.estimate(messages) + AgentContextBudget.countTokens(tools().toString())

    // Escaping inflates serialized size without inflating the token estimate. Explicit
    // turn IDs also prevent the persistence DTO from clipping these historical bodies.
    private fun escapedHistory(charsPerMessage: Int = 30_000) = history("\\".repeat(charsPerMessage), count = 20)

    private fun smallHistory() = history("old evidence ".repeat(100), count = 8)

    private fun history(content: String, count: Int): JSONArray = JSONArray().also { messages ->
        repeat(count) { index ->
            messages.put(JSONObject().put("role", if (index % 2 == 0) "user" else "assistant")
                .put("content", content).put(AgentTurnIdentity.JSON_KEY, "old-$index"))
        }
        messages.put(AgentConversationCodec.userTextMessage("current task").put(AgentTurnIdentity.JSON_KEY, "current-turn"))
    }

    private fun summarize(source: List<AgentModelClient.ConversationMessage>, policy: AgentLoop.CompactPolicy) =
        listOf(AgentModelClient.ConversationMessage("system", AgentContextCompactor.SUMMARY_PREFIX_ZH + "\nsummary")) +
            source.drop(requireNotNull(policy.keepStartOverride))

    private fun modelConfig() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test",
        systemPrompt = "", contextWindow = WINDOW, browserTools = false,
    )

    private fun tools() = JSONArray().put(JSONObject().put("type", "function").put("function",
        JSONObject().put("name", "get_current_context").put("parameters", JSONObject().put("type", "object"))))

    private fun assistant(content: String = "done", promptTokens: Int? = null): JSONObject =
        JSONObject().put("role", "assistant").put("content", content).put("finish_reason", "stop").also {
            if (promptTokens != null) it.put("usage", JSONObject().put("prompt_tokens", promptTokens))
        }

    private fun toolReply(id: String): JSONObject = assistant(content = "").put("finish_reason", "tool_calls")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}"))))

    private fun overflow() = AgentModelFailure.http(400,
        """{"error":{"code":"context_length_exceeded","message":"maximum context length exceeded"}}""")

    private class ScriptedProvider(
        private val responses: List<(ProviderRequest, AgentRunController) -> JSONObject>,
    ) : AgentProviderClient {
        override val id = "automatic-compaction-test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        val requests = mutableListOf<JSONArray>()

        override fun complete(request: ProviderRequest, runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            val step = responses.getOrNull(requests.size) ?: error("Unexpected provider request ${requests.size + 1}")
            requests += JSONArray(request.messages.toString())
            val reply = step(request, runController)
            reply.optJSONObject("usage")?.let {
                onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = it.getInt("prompt_tokens"))))
            }
            return ProviderResponse(reply)
        }
    }

    private companion object {
        const val WINDOW = 260_000
        const val AUTO_PRESSURE = 208_000
        const val HARD_STORAGE_CAP = 1_560_000L
    }
}
