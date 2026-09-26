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

/**
 * Automatic compaction is driven purely by the provider's billed input usage (cloud policy):
 *  - no billed usage => no automatic compaction, no matter how large the local history is;
 *  - freshly appended assistant/tool content is never accumulated into a local projection;
 *  - automatic compaction fires at exactly 80% of the effective window (configured window wins);
 *  - after a summary commits the loop waits for the next reported usage before it can fire again;
 *  - hitting the local persistence cap pauses without summarizing protected history.
 *
 * Retained behaviours: explicit provider overflow recovery, manual compaction, whole tool-batch
 * retention without replay, cancellation safety, and protected-history integrity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentAutomaticCompactionTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val timeout = Timeout.seconds(45)

    // --- Billing-driven automatic summarization -------------------------------------------------

    @Test fun partialOutputUsagePreservesSameRequestInputAndAllowsRealCorrection() {
        for (corrected in listOf(false, true)) {
            val frames = mutableListOf(AgentTokenUsage(outputTokens = 20))
            if (corrected) frames += AgentTokenUsage(inputTokens = AUTO_PRESSURE - 1)
            val provider = ScriptedProvider(listOf({ _, _ -> assistant(promptTokens = AUTO_PRESSURE) }), frames)
            val events = mutableListOf<AgentEvent>()
            var summaries = 0
            runLoop(smallHistory(), provider, events, compactHistory = { source, policy ->
                summaries++
                summarize(source, policy)
            })
            assertEquals(if (corrected) 0 else 1, summaries)
            assertEquals(1, provider.requests.size)
            assertFalse(events.filterIsInstance<AgentEvent.UsageReceived>().any { it.projected })
        }
    }

    @Test fun missingBilledUsageNeverTriggersAutomaticCompaction() {
        // The local history is already above 80%, yet with no billed usage there is no decision.
        val messages = largeHistory()
        assertTrue(requestTokens(messages) >= AUTO_PRESSURE)
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val provider = ScriptedProvider(listOf({ _, _ -> assistant() }))
        assertEquals("done", runLoop(messages, provider, events,
            compactHistory = { source, policy -> summaries++; summarize(source, policy) }).content)

        assertEquals(1, provider.requests.size)
        assertEquals(0, summaries)
        assertTrue(events.none { it is AgentEvent.UsageReceived })
        assertTrue(events.none { it is AgentEvent.ContextCompactionStarted })
        assertTrue(events.none { it is AgentEvent.ContextCompacted })
    }

    @Test fun billedUsageBelowEightyPercentNeverCompactsEvenWithLargeLocalHistory() {
        val messages = largeHistory()
        assertTrue(requestTokens(messages) >= AUTO_PRESSURE)
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val provider = ScriptedProvider(listOf({ _, _ -> assistant(promptTokens = AUTO_PRESSURE - 1) }))
        assertEquals("done", runLoop(messages, provider, events,
            compactHistory = { source, policy -> summaries++; summarize(source, policy) }).content)

        assertEquals(1, provider.requests.size)
        assertEquals(0, summaries)
        assertTrue(events.none { it is AgentEvent.ContextCompactionStarted })
        assertEquals(AUTO_PRESSURE - 1,
            requireNotNull(events.filterIsInstance<AgentEvent.UsageReceived>().single().usage.inputTokens))
    }

    @Test fun configuredWindowCompactsExactlyAtEightyPercentButNotOneTokenBelow() {
        // The decision uses the request's configured window, not the policy's default window.
        val config = modelConfig().copy(contextWindow = WINDOW / 2)
        val threshold = AgentContextCompactor.autoPressureTokens(requireNotNull(config.contextWindow))
        for (decisionTokens in listOf(threshold - 1, threshold)) {
            val messages = smallHistory()
            val events = mutableListOf<AgentEvent>()
            var summaries = 0
            val provider = ScriptedProvider(listOf({ _, _ -> assistant(promptTokens = decisionTokens) }))
            assertEquals("done", runLoop(messages, provider, events, config = config,
                compactHistory = { source, policy -> summaries++; summarize(source, policy) }).content)

            assertEquals(1, provider.requests.size)
            assertEquals(decisionTokens,
                requireNotNull(events.filterIsInstance<AgentEvent.UsageReceived>().single().usage.inputTokens))
            if (decisionTokens < threshold) {
                assertEquals(0, summaries)
                assertTrue(events.none { it is AgentEvent.ContextCompactionStarted })
            } else {
                assertEquals(1, summaries)
                assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().single().applied)
            }
        }
    }

    @Test fun freshToolOutputIsNotLocallyAccumulatedIntoTheDecision() {
        val messages = smallHistory()
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val output = "x".repeat(900_000)
        val provider = ScriptedProvider(listOf(
            { _, _ -> toolReply("big").put("usage", JSONObject().put("prompt_tokens", 131_470)) },
            { _, _ -> assistant(promptTokens = 20) },
        ))
        assertEquals("done", runLoop(messages, provider, events,
            toolExecutor = AgentModelClient.ToolExecutor { _ -> AgentModelClient.ToolResult(output) },
            compactHistory = { source, policy -> summaries++; summarize(source, policy) }).content)

        assertEquals(2, provider.requests.size)
        assertEquals(0, summaries)
        assertTrue(events.none { it is AgentEvent.ContextCompactionStarted })
        // The retained history is far above 80% locally, yet only the billed usage gates a summary.
        assertTrue(requestTokens(provider.requests.last()) >= AUTO_PRESSURE)
    }

    @Test fun automaticCompactionUsesBilledUsageThenWaitsForTheNextReport() {
        val messages = smallHistory()
        val events = mutableListOf<AgentEvent>()
        val executions = mutableListOf<String>()
        var summaries = 0
        val provider = ScriptedProvider(listOf(
            { _, _ -> toolReply("first").put("usage", JSONObject().put("prompt_tokens", AUTO_PRESSURE)) },
            { _, _ -> toolReply("second") },
            { _, _ -> assistant(promptTokens = 20) },
        ))
        assertEquals("done", runLoop(messages, provider, events,
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executions += call.id
                AgentModelClient.ToolResult("ok")
            },
            compactHistory = { source, policy -> summaries++; summarize(source, policy) }).content)

        assertEquals(listOf("first", "second"), executions)
        assertEquals(3, provider.requests.size)
        assertEquals(1, summaries)
        assertTrue(events.filterIsInstance<AgentEvent.UsageReceived>().none { it.projected })
        assertEquals(2, events.filterIsInstance<AgentEvent.ContextCompactionStarted>().single().round)
        // The batch that reported 80% is summarized once, after it finished. The usage-less round
        // after the summary must not re-summarize until a fresh usage is reported.
        val firstToolIndex = events.indexOfFirst { it is AgentEvent.ToolFinished && it.toolCallId == "first" }
        val startIndex = events.indexOfFirst { it is AgentEvent.ContextCompactionStarted }
        assertTrue(firstToolIndex in 0 until startIndex)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().none { it.blocked })
    }

    @Test fun localPersistenceOverLimitPausesWithoutAutomaticSummary() {
        val messages = escapedHistory(charsPerMessage = 40_000)
        assertTrue(storedChars(messages) > HARD_STORAGE_CAP)
        val original = messages.toString()
        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(emptyList())
        var summaries = 0
        assertThrows(AgentRunCancelledException::class.java) {
            runLoop(messages, provider, events,
                compactHistory = { source, policy -> summaries++; summarize(source, policy) })
        }

        assertEquals(0, summaries)
        assertEquals(original, messages.toString())
        assertTrue(provider.requests.isEmpty())
        assertTrue(events.none { it is AgentEvent.ContextCompactionStarted })
        val blocked = events.filterIsInstance<AgentEvent.ContextCompacted>().single()
        assertTrue(blocked.blocked)
        assertTrue(blocked.reason.contains("持久化容量上限"))
    }

    // --- Explicit provider overflow recovery ----------------------------------------------------

    @Test fun genuineProviderOverflowBelowThresholdRecoversBeforeRetry() {
        val messages = smallHistory()
        assertTrue(requestTokens(messages) < AUTO_PRESSURE)
        val originalTokens = requestTokens(messages)
        val provider = ScriptedProvider(listOf(
            { _, _ -> throw overflow() },
            { request, _ ->
                assertTrue(requestTokens(request.messages) < originalTokens)
                assistant(promptTokens = 20)
            },
        ))

        assertAutomaticRecovery(messages, provider)

        assertEquals(2, provider.requests.size)
    }

    @Test fun repeatedProviderOverflowStopsAfterOneSuccessfulReductionAndRetry() {
        val messages = smallHistory()
        lateinit var liveBeforeRetryOverflow: String
        val provider = ScriptedProvider(listOf(
            { _, _ -> throw overflow() },
            { _, _ -> liveBeforeRetryOverflow = messages.toString(); throw overflow() },
        ))
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        assertThrows(AgentRunCancelledException::class.java) {
            runLoop(messages, provider, events,
                compactHistory = { source, policy -> summaries++; summarize(source, policy) })
        }
        assertEquals(1, summaries)
        assertEquals(2, provider.requests.size)
        assertEquals(liveBeforeRetryOverflow, messages.toString())
        assertEquals(wireJson(JSONArray(liveBeforeRetryOverflow)), provider.requests.last().toString())
        val blocked = events.filterIsInstance<AgentEvent.ContextCompacted>().single { it.blocked }
        assertTrue(blocked.reason.contains("提供方确认上下文超限"))
    }

    @Test fun belowThresholdOverflowKeepsCompleteLiveToolPairAndTurnIdentityVerbatim() {
        val messages = smallHistory()
            .put(toolReply("live").put(AgentTurnIdentity.JSON_KEY, "current-turn")
                .put("provider_private", "opaque-call"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "live")
                .put("content", "live evidence").put(AgentTurnIdentity.JSON_KEY, "current-turn")
                .put("provider_private", "opaque-result"))
        val liveTail = (messages.length() - 2 until messages.length()).map { messages.getJSONObject(it).toString() }
        val provider = ScriptedProvider(listOf(
            { _, _ -> throw overflow() },
            { request, _ ->
                val tail = JSONArray((request.messages.length() - 2 until request.messages.length())
                    .map { request.messages.getJSONObject(it) })
                assertEquals(wireJson(JSONArray(liveTail.map { JSONObject(it) })), tail.toString())
                assistant(promptTokens = 20)
            },
        ))
        assertAutomaticRecovery(messages, provider)
        assertEquals(liveTail, (messages.length() - 3 until messages.length() - 1)
            .map { messages.getJSONObject(it).toString() })
        assertEquals("current-turn", messages.getJSONObject(messages.length() - 1).getString(AgentTurnIdentity.JSON_KEY))
    }

    // --- Whole tool batch is retained, never replayed -------------------------------------------

    @Test fun providerOverflowRecoveryKeepsWholeToolBatchWithoutRepeatingOrChangingTurn() {
        val messages = smallHistory()
        val ids = listOf("batch-first", "batch-second", "batch-third")
        val callsJson = JSONArray().also { calls ->
            ids.forEach { calls.put(toolReply(it).getJSONArray("tool_calls").getJSONObject(0)) }
        }
        val reply = assistant(content = "").put("finish_reason", "tool_calls").put("tool_calls", callsJson)
        val output = "x".repeat(20_000)
        val events = mutableListOf<AgentEvent>()
        val executions = mutableListOf<String>()
        var summaries = 0
        var keptJson = emptyList<String>()
        val provider = ScriptedProvider(listOf(
            { _, _ -> reply.put("usage", JSONObject().put("prompt_tokens", 41_000)) },
            { _, _ -> throw overflow() },
            { _, _ ->
                assertEquals(1, summaries)
                assertEquals(ids, executions)
                assistant(promptTokens = 20)
            },
        ))
        assertEquals("done", runLoop(messages, provider, events,
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executions += call.id
                AgentModelClient.ToolResult(output)
            },
            compactHistory = { source, policy ->
                summaries++
                assertEquals(ids, executions)
                assertEquals(ids, source.filter { it.role == "tool" }.map { it.toolCallId })
                val cut = requireNotNull(policy.keepStartOverride)
                assertTrue(AgentCompressionBoundary.balancedCuts(source).contains(cut))
                assertEquals(listOf("assistant", "tool", "tool", "tool"), source.drop(cut).map { it.role })
                keptJson = (cut until messages.length()).map { messages.getJSONObject(it).toString() }
                keptJson.forEach { assertEquals("current-turn", JSONObject(it).getString(AgentTurnIdentity.JSON_KEY)) }
                summarize(source, policy)
            }).content)

        assertEquals(ids, executions)
        assertEquals(3, provider.requests.size)
        assertEquals(1, summaries)
        val start = events.indexOfFirst { it is AgentEvent.ContextCompactionStarted }
        assertTrue(events.withIndex().filter { it.value is AgentEvent.ToolFinished }.all { it.index < start })
        assertEquals(ids, events.filterIsInstance<AgentEvent.ToolStarted>().map { it.toolCallId })
        assertEquals(ids, events.filterIsInstance<AgentEvent.ToolFinished>().map { it.toolCallId })
        assertTrue(events.filterIsInstance<AgentEvent.ToolFinished>().all { it.round == 1 })
        assertEquals(2, events.filterIsInstance<AgentEvent.ContextCompactionStarted>().single().round)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().single().applied)
        assertEquals(keptJson, (messages.length() - 1 - keptJson.size until messages.length() - 1)
            .map { messages.getJSONObject(it).toString() })
        assertEquals("current-turn", messages.getJSONObject(messages.length() - 1).getString(AgentTurnIdentity.JSON_KEY))
    }

    // --- Manual compaction ----------------------------------------------------------------------

    @Test fun manualCompactionBelowThresholdCompactsWithoutLocalProjection() {
        val controller = AgentRunController().also { it.requestCompact(keepRecentMessages = 1) }
        val messages = smallHistory()
        val expectedTokens = requestTokens(messages)
        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(listOf({ _, _ -> assistant(promptTokens = 20) }))
        var compactCalls = 0
        assertEquals("done", runLoop(messages, provider, events, enabled = false, controller = controller,
            compactHistory = { source, policy ->
                compactCalls++
                assertEquals(1, policy.keepRecentMessages)
                summarize(source, policy)
            }).content)

        assertTrue(expectedTokens < AUTO_PRESSURE)
        assertEquals(1, compactCalls)
        assertEquals(1, events.filterIsInstance<AgentEvent.ContextCompactionStarted>().size)
        // The manual request compacts without any billed usage and without a local projection.
        assertTrue(events.none { it is AgentEvent.UsageReceived && it.projected })
        assertEquals("current task", provider.requests.single()
            .getJSONObject(provider.requests.single().length() - 1).getString("content"))
    }

    @Test fun manualCompressorAndRetentionExpireBeforeLaterBelowThresholdOverflow() {
        val automatic = modelConfig().copy(model = "automatic-A")
        val manual = modelConfig().copy(model = "manual-B")
        val controller = AgentRunController().also {
            assertTrue(it.requestCompact(keepRecentMessages = 1, compressModelConfig = manual))
        }
        val messages = smallHistory()
        val events = mutableListOf<AgentEvent>()
        val attempts = mutableListOf<Pair<String, Int>>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertEquals(listOf("manual-B" to 1), attempts)
                assertTrue(requestTokens(request.messages) < AUTO_PRESSURE)
                throw overflow()
            },
            { _, _ -> assistant(promptTokens = 20) },
        ))
        assertEquals("done", runLoop(messages, provider, events, controller = controller, compressor = automatic,
            compactHistory = { source, policy ->
                attempts += requireNotNull(policy.compressModelConfig).model to policy.keepRecentMessages
                assertTrue(requestTokens(messages) < AUTO_PRESSURE)
                if (attempts.size == 1) {
                    // A valid first reduction with enough remaining prefix for overflow recovery.
                    listOf(AgentModelClient.ConversationMessage("system",
                        AgentContextCompactor.SUMMARY_PREFIX_ZH + "\n" + "manual evidence ".repeat(100))) +
                        source.drop(requireNotNull(policy.keepStartOverride))
                } else summarize(source, policy)
            }).content)

        assertEquals(listOf("manual-B" to 1, "automatic-A" to 2), attempts)
        assertEquals(2, provider.requests.size)
        assertTrue(requestTokens(provider.requests.last()) < requestTokens(provider.requests.first()))
        val compactions = events.filterIsInstance<AgentEvent.ContextCompacted>()
        assertEquals(2, compactions.size)
        assertTrue(compactions.all { it.applied && !it.blocked })
        assertEquals("current-turn", messages.getJSONObject(messages.length() - 1).getString(AgentTurnIdentity.JSON_KEY))
    }

    @Test fun manualCompressorCannotFillMissingAutomaticConfigAfterRequest() {
        val manual = modelConfig().copy(model = "manual-B")
        val controller = AgentRunController().also {
            assertTrue(it.requestCompact(keepRecentMessages = 1, compressModelConfig = manual))
        }
        val messages = smallHistory()
        val events = mutableListOf<AgentEvent>()
        lateinit var liveBeforeOverflow: String
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertTrue(requestTokens(request.messages) < AUTO_PRESSURE)
                liveBeforeOverflow = messages.toString()
                throw overflow()
            },
            // A leaked override would wrongly recover and reach this request instead of pausing.
            { _, _ -> assistant(promptTokens = 20) },
        ))
        var summaries = 0
        assertThrows(AgentRunCancelledException::class.java) {
            runLoop(messages, provider, events, controller = controller, compressor = null,
                compactHistory = { source, policy ->
                    summaries++
                    assertEquals("manual-B", requireNotNull(policy.compressModelConfig).model)
                    assertEquals(1, policy.keepRecentMessages)
                    if (summaries == 1) {
                        listOf(AgentModelClient.ConversationMessage("system",
                            AgentContextCompactor.SUMMARY_PREFIX_ZH + "\n" + "manual evidence ".repeat(100))) +
                            source.drop(requireNotNull(policy.keepStartOverride))
                    } else summarize(source, policy)
                })
        }

        assertEquals(1, summaries)
        assertEquals(1, provider.requests.size)
        assertEquals(liveBeforeOverflow, messages.toString())
        assertEquals(wireJson(JSONArray(liveBeforeOverflow)), provider.requests.single().toString())
        assertEquals(1, events.filterIsInstance<AgentEvent.ContextCompactionStarted>().size)
        val compactions = events.filterIsInstance<AgentEvent.ContextCompacted>()
        assertEquals(1, compactions.count { it.applied })
        assertTrue(compactions.single { it.blocked }.reason.contains("提供方确认上下文超限"))
    }

    // --- Cancellation and protected-history integrity -------------------------------------------

    @Test fun failedRecoverySummaryBlocksWithoutChangingProtectedHistory() {
        val messages = smallHistory()
        val original = messages.toString()
        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(listOf({ _, _ -> throw overflow() }))
        assertThrows(AgentRunCancelledException::class.java) {
            runLoop(messages, provider, events,
                compactHistory = { _, _ -> listOf(AgentModelClient.ConversationMessage("system", "lost tail")) })
        }
        assertEquals(original, messages.toString())
        assertEquals(1, provider.requests.size)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.blocked })
        assertFalse(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.applied })
    }

    @Test fun failedRecoverySummaryKeepsHistoryAndReasonAcrossResumesWithoutRepeating() {
        for (outcome in listOf("throw", "unchanged", "lost-tail", "no-reduction")) {
            val messages = smallHistory()
            val original = messages.toString()
            val events = mutableListOf<AgentEvent>()
            val provider = ScriptedProvider(listOf({ _, _ -> throw overflow() }))
            var summaries = 0
            var pauses = 0
            assertThrows(AgentRunCancelledException::class.java) {
                runLoop(messages, provider, events,
                    onBlocked = { controller -> if (++pauses < 3) controller.resume() else controller.cancel() },
                    compactHistory = { source, policy ->
                        summaries++
                        when (outcome) {
                            "throw" -> error("summary transport failed")
                            "unchanged" -> source
                            "lost-tail" -> listOf(AgentModelClient.ConversationMessage("system", "lost tail"))
                            else -> listOf(AgentModelClient.ConversationMessage("system", "x".repeat(900_000))) +
                                source.drop(requireNotNull(policy.keepStartOverride))
                        }
                    })
            }

            assertEquals(1, summaries)
            assertEquals(3, pauses)
            assertEquals(original, messages.toString())
            assertEquals(1, provider.requests.size)
            assertEquals(1, events.filterIsInstance<AgentEvent.ContextCompactionStarted>().size)
            val compactions = events.filterIsInstance<AgentEvent.ContextCompacted>()
            assertFalse(compactions.any { it.applied })
            val failure = compactions.single { !it.blocked }.reason
            assertTrue(failure.isNotBlank())
            if (outcome == "throw") assertEquals("summary transport failed", failure)
            assertTrue(compactions.filter { it.blocked }.all { it.reason == failure })
            assertFalse(failure.contains("80%"))
        }
    }

    @Test fun hardPressureDoesNotEnableDisabledOrUnconfiguredAutomaticSummary() {
        for (enabled in listOf(false, true)) {
            val messages = smallHistory()
            val original = messages.toString()
            val events = mutableListOf<AgentEvent>()
            val provider = ScriptedProvider(listOf({ _, _ -> throw overflow() }))
            var summaries = 0
            assertThrows(AgentRunCancelledException::class.java) {
                runLoop(messages, provider, events, enabled = enabled,
                    compressor = if (enabled) null else modelConfig(),
                    compactHistory = { source, policy -> summaries++; summarize(source, policy) })
            }
            assertEquals(0, summaries)
            assertEquals(original, messages.toString())
            assertEquals(1, provider.requests.size)
            assertTrue(events.none { it is AgentEvent.ContextCompactionStarted })
            assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().single().blocked)
        }
    }

    @Test fun cancellationDuringOverflowSummaryNeverCommits() {
        val messages = smallHistory()
        val original = messages.toString()
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(listOf({ _, _ -> throw overflow() }))
        var summaries = 0
        assertThrows(AgentRunCancelledException::class.java) {
            runLoop(messages, provider, events, controller = controller,
                compactHistory = { source, policy ->
                    summaries++
                    controller.cancel()
                    summarize(source, policy)
                })
        }
        assertEquals(1, summaries)
        assertEquals(original, messages.toString())
        assertEquals(1, provider.requests.size)
        assertFalse(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.applied })
    }

    // --- Helpers --------------------------------------------------------------------------------

    // Request expectations use the same text-only wire projection as AgentLoop.
    private fun wireJson(messages: JSONArray): String =
        AgentRequestMediaPolicy.filter(messages, false, false).toString()

    private fun assertAutomaticRecovery(
        messages: JSONArray,
        provider: ScriptedProvider,
        config: AgentModelClient.ModelConfig = modelConfig(),
    ) {
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        var keptJson = emptyList<String>()
        assertEquals("done", runLoop(messages, provider, events, config = config,
            archive = AgentCompactionArchive(temporary.newFolder(), "hard-recovery"),
            compactHistory = { source, policy ->
                summaries++
                val cut = requireNotNull(policy.keepStartOverride)
                assertTrue(cut > 0 && cut < source.size)
                assertTrue(AgentCompressionBoundary.balancedCuts(source).contains(cut))
                keptJson = (cut until messages.length()).map { messages.getJSONObject(it).toString() }
                summarize(source, policy)
            }).content)

        assertEquals(1, summaries)
        // No local projection is ever published; the decision comes from the provider bill only.
        assertTrue(events.none { it is AgentEvent.UsageReceived && it.projected })
        val applied = events.filterIsInstance<AgentEvent.ContextCompacted>().single()
        assertTrue(applied.applied)
        assertFalse(applied.blocked)
        // Only the natural response is appended after the verbatim protected tail.
        assertEquals(keptJson, (messages.length() - 1 - keptJson.size until messages.length() - 1)
            .map { messages.getJSONObject(it).toString() })
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
        compressor: AgentModelClient.ModelConfig? = config,
        onBlocked: (AgentRunController) -> Unit = { it.cancel() },
        compactHistory: (List<AgentModelClient.ConversationMessage>, AgentLoop.CompactPolicy) -> List<AgentModelClient.ConversationMessage> = ::summarize,
    ): AgentLoop.Result = AgentLoop(
        config = config, messages = messages, tools = tools(), provider = provider,
        toolExecutor = toolExecutor, runController = controller, traceFormatter = AgentTraceFormatter(),
        onEvent = { event ->
            events += event
            // Turn an unexpected pause into a deterministic failure, never a hanging test.
            if (event is AgentEvent.ContextCompacted && event.blocked) onBlocked(controller)
        },
        compactPolicy = AgentLoop.CompactPolicy(enabled, WINDOW, 2, compressor),
        compactionArchive = archive, turnId = "current-turn", compactHistory = compactHistory,
    ).run()

    private fun storedChars(messages: JSONArray): Long = AgentConversationCodec.transcript(messages, 0)
        .sumOf { AgentConversationCodec.toJsonObject(it).toString().length.toLong() }

    private fun requestTokens(messages: JSONArray): Int =
        AgentContextBudget.estimate(messages) + AgentContextBudget.countTokens(tools().toString())

    // Plain latin history whose local estimate alone is above the 80% boundary.
    private fun largeHistory() = history("x".repeat(100_000), count = 9)

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
        private val usageFrames: List<AgentTokenUsage> = emptyList(),
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
            usageFrames.forEach { onEvent(ProviderEvent.Usage(it)) }
            return ProviderResponse(reply)
        }
    }

    private companion object {
        const val WINDOW = 260_000
        const val AUTO_PRESSURE = 208_000
        const val HARD_STORAGE_CAP = 1_560_000L
    }
}
