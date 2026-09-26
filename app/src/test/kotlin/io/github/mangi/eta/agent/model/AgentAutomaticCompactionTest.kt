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

    @Test fun actualConfiguredWindowAllowsExactlyEightyPercentButNotOneTokenBelow() {
        // The runLoop policy still uses WINDOW; the request's configured window wins.
        val config = modelConfig().copy(contextWindow = WINDOW / 2)
        val threshold = AgentContextCompactor.autoPressureTokens(requireNotNull(config.contextWindow))
        for (decisionTokens in listOf(threshold - 1, threshold)) {
            val messages = smallHistory()
            assertTrue(requestTokens(messages) < threshold)
            val reply = assistant()
            val replyTokens = AgentContextBudget.countMessage(AgentConversationCodec.fromJsonObject(reply))
            val provider = ScriptedProvider(listOf({ _, _ ->
                reply.put("usage", JSONObject().put("prompt_tokens", decisionTokens - replyTokens))
            }))
            val events = mutableListOf<AgentEvent>()
            var summaries = 0
            assertEquals("done", runLoop(messages, provider, events, config = config,
                compactHistory = { source, policy -> summaries++; summarize(source, policy) }).content)

            assertEquals(1, provider.requests.size)
            assertFalse(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.blocked })
            if (decisionTokens < threshold) {
                assertEquals(0, summaries)
                assertFalse(events.any { it is AgentEvent.ContextCompactionStarted })
            } else {
                assertEquals(1, summaries)
                assertUsageBeforeStart(events, threshold)
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
        assertTrue(compactions.single { it.blocked }.reason.orEmpty().contains("提供方确认上下文超限"))
    }

    @Test fun manualOverrideStillCoversBoundedHardRecoveryBeforeItsRequest() {
        val config = modelConfig().copy(extraBodyJson = """{"max_tokens":200000}""")
        val inputLimit = AgentCompressionBoundary.inputLimit(WINDOW, AgentCompressionBoundary.outputReserve(config))
        val manual = modelConfig().copy(model = "manual-B")
        val controller = AgentRunController().also {
            assertTrue(it.requestCompact(keepRecentMessages = 1, compressModelConfig = manual))
        }
        val messages = history("x".repeat(24_000), count = 10)
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val provider = ScriptedProvider(listOf({ request, _ ->
            assertEquals(2, summaries)
            assertTrue(requestTokens(request.messages) <= inputLimit)
            assistant(promptTokens = 20)
        }))
        assertEquals("done", runLoop(messages, provider, events, config = config,
            enabled = false, controller = controller, compressor = null,
            compactHistory = { source, policy ->
                summaries++
                assertEquals("manual-B", requireNotNull(policy.compressModelConfig).model)
                assertEquals(1, policy.keepRecentMessages)
                assertTrue(requestTokens(messages) > inputLimit)
                assertTrue(requestTokens(messages) < AUTO_PRESSURE)
                if (summaries == 1) {
                    // Reduces the prefix, but the full request still exceeds the hard input limit.
                    listOf(AgentModelClient.ConversationMessage("system",
                        AgentContextCompactor.SUMMARY_PREFIX_ZH + "\n" + "x".repeat(32_000))) +
                        source.drop(requireNotNull(policy.keepStartOverride))
                } else summarize(source, policy)
            }).content)

        assertEquals(2, summaries)
        assertEquals(1, provider.requests.size)
        val compactions = events.filterIsInstance<AgentEvent.ContextCompacted>()
        assertEquals(2, compactions.size)
        assertTrue(compactions.all { it.applied && !it.blocked && it.round == 1 })
    }

    @Test fun hardInputRecoveryWaitsForWholeLiveToolBatchWithoutRepeatingOrChangingTurn() {
        val config = modelConfig().copy(extraBodyJson = """{"max_tokens":200000}""")
        val inputLimit = AgentCompressionBoundary.inputLimit(WINDOW, AgentCompressionBoundary.outputReserve(config))
        val messages = smallHistory()
        val ids = listOf("batch-first", "batch-second", "batch-third")
        val callsJson = JSONArray().also { calls ->
            ids.forEach { calls.put(toolReply(it).getJSONArray("tool_calls").getJSONObject(0)) }
        }
        val reply = assistant(content = "").put("finish_reason", "tool_calls").put("tool_calls", callsJson)
        val calls = AgentConversationCodec.parseToolCalls(reply)
        val output = "x".repeat(20_000)
        val billedTokens = 41_000
        val assistantTokens = AgentContextBudget.countMessage(AgentConversationCodec.fromJsonObject(
            AgentConversationCodec.assistantHistoryMessage(reply, calls)))
        val resultTokens = calls.map { call ->
            AgentContextBudget.countMessage(AgentConversationCodec.fromJsonObject(
                AgentConversationCodec.toolResultMessage(call, AgentModelClient.ToolResult(output))))
        }
        val decisionTokens = billedTokens + assistantTokens + resultTokens.sum()
        assertTrue(requestTokens(messages) < inputLimit)
        // Pressure is already hard after the second result, but the third must still run.
        assertTrue(billedTokens + assistantTokens + resultTokens.take(2).sum() > inputLimit)
        assertTrue(decisionTokens < AUTO_PRESSURE)
        val events = mutableListOf<AgentEvent>()
        val executions = mutableListOf<String>()
        var summaries = 0
        var keptJson = emptyList<String>()
        val provider = ScriptedProvider(listOf(
            { _, _ -> reply.put("usage", JSONObject().put("prompt_tokens", billedTokens)) },
            { request, _ ->
                assertEquals(1, summaries)
                assertEquals(ids, executions)
                assertTrue(requestTokens(request.messages) <= inputLimit)
                assertEquals(wireJson(JSONArray(keptJson.map { JSONObject(it) })),
                    JSONArray((request.messages.length() - keptJson.size until request.messages.length())
                        .map { request.messages.getJSONObject(it) }).toString())
                assistant(promptTokens = 20)
            },
        ))
        assertEquals("done", runLoop(messages, provider, events, config = config,
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                assertEquals(0, summaries)
                assertFalse(events.any { it is AgentEvent.ContextCompactionStarted })
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
        assertEquals(2, provider.requests.size)
        assertEquals(1, summaries)
        assertUsageBeforeStart(events, decisionTokens)
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

    @Test fun outputReserveBelowAutoThresholdRecoversWithAutomaticSummary() {
        for ((config, messages) in listOf(
            modelConfig().copy(extraBodyJson = """{"max_tokens":200000}""") to history("x".repeat(24_000), count = 10),
            // Above the policy's threshold, but below the actual larger window's threshold.
            modelConfig().copy(contextWindow = WINDOW * 2, extraBodyJson = """{"max_tokens":300000}""") to
                history("x".repeat(100_000), count = 10),
        )) {
            val window = requireNotNull(config.contextWindow)
            val decisionTokens = requestTokens(messages)
            val inputLimit = AgentCompressionBoundary.inputLimit(window, AgentCompressionBoundary.outputReserve(config))
            assertTrue(decisionTokens > inputLimit)
            assertTrue(decisionTokens < AgentContextCompactor.autoPressureTokens(window))
            val provider = ScriptedProvider(listOf({ request, _ ->
                assertTrue(requestTokens(request.messages) <= inputLimit)
                assistant(promptTokens = 20)
            }))

            assertAutomaticRecovery(messages, provider, config)

            assertEquals(1, provider.requests.size)
        }
    }

    @Test fun hardStorageCapBelowAutoThresholdRecoversWithAutomaticSummary() {
        val messages = escapedHistory(charsPerMessage = 40_000)
        assertTrue(storedChars(messages) > HARD_STORAGE_CAP)
        assertTrue(requestTokens(messages) < AUTO_PRESSURE)
        val provider = ScriptedProvider(listOf({ request, _ ->
            assertTrue(storedChars(request.messages) < HARD_STORAGE_CAP)
            assistant(promptTokens = 20)
        }))

        assertAutomaticRecovery(messages, provider)

        assertEquals(1, provider.requests.size)
    }

    @Test fun unsafeHardBudgetSummaryBlocksWithoutChangingProtectedHistory() {
        val messages = history("x".repeat(84_000), count = 10)
        val decisionTokens = requestTokens(messages)
        assertTrue(decisionTokens >= AUTO_PRESSURE)
        val original = messages.toString()
        val provider = ScriptedProvider(emptyList())
        val events = mutableListOf<AgentEvent>()
        assertThrows(AgentRunCancelledException::class.java) {
            runLoop(messages, provider, events,
                config = modelConfig().copy(extraBodyJson = """{"max_tokens":200000}"""),
                compactHistory = { _, _ -> listOf(AgentModelClient.ConversationMessage("system", "lost tail")) })
        }
        assertUsageBeforeStart(events, decisionTokens)
        assertTrue(provider.requests.isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.blocked })
        assertFalse(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.applied })
        assertEquals(original, messages.toString())
    }

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

    @Test fun postPruneBelowThresholdStillRecoversWhenHardStoragePressureRemains() {
        val messages = JSONArray()
            .put(AgentConversationCodec.userTextMessage("old task"))
            .put(toolReply("old"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "old")
                .put("content", "x".repeat(240_000)).put(AgentTurnIdentity.JSON_KEY, "old-turn"))
        val tail = escapedHistory(charsPerMessage = 40_000)
        for (i in 0 until tail.length()) messages.put(tail.getJSONObject(i))
        assertTrue(requestTokens(messages) >= AUTO_PRESSURE)
        assertTrue(storedChars(messages) > HARD_STORAGE_CAP)
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        var postPruneTokens = 0
        val provider = ScriptedProvider(listOf({ request, _ ->
            assertTrue(storedChars(request.messages) < HARD_STORAGE_CAP)
            assistant(promptTokens = 20)
        }))
        assertEquals("done", runLoop(messages, provider, events,
            archive = AgentCompactionArchive(temporary.root, "hard-post-prune"),
            compactHistory = { source, policy ->
                summaries++
                postPruneTokens = requestTokens(messages)
                assertTrue(postPruneTokens < AUTO_PRESSURE)
                assertTrue(storedChars(messages) > HARD_STORAGE_CAP)
                assertTrue(source.single { it.toolCallId == "old" }.content.contains("[Eta tool output pruned;"))
                val cut = requireNotNull(policy.keepStartOverride)
                assertTrue(AgentCompressionBoundary.balancedCuts(source).contains(cut))
                summarize(source, policy)
            }).content)

        assertEquals(1, summaries)
        assertEquals(1, provider.requests.size)
        assertUsageBeforeStart(events, postPruneTokens)
        val compactions = events.filterIsInstance<AgentEvent.ContextCompacted>()
        assertEquals(2, compactions.size) // Pruning commits first, summary uses that fresh snapshot.
        assertTrue(compactions.all { it.applied && !it.blocked })
    }

    @Test fun belowThresholdSummaryFailureKeepsHistoryAndReasonAcrossResumesWithoutRepeating() {
        for (outcome in listOf("throw", "unchanged", "lost-tail", "no-reduction")) {
            val messages = escapedHistory(charsPerMessage = 40_000)
            val original = messages.toString()
            val events = mutableListOf<AgentEvent>()
            val provider = ScriptedProvider(emptyList())
            var summaries = 0
            var pauses = 0
            assertThrows(AgentRunCancelledException::class.java) {
                runLoop(messages, provider, events,
                    onBlocked = { controller ->
                        if (++pauses < 3) controller.resume() else controller.cancel()
                    },
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
            assertTrue(provider.requests.isEmpty())
            assertEquals(1, events.filterIsInstance<AgentEvent.ContextCompactionStarted>().size)
            val compactions = events.filterIsInstance<AgentEvent.ContextCompacted>()
            assertFalse(compactions.any { it.applied })
            val failure = compactions.single { !it.blocked }.reason
            assertTrue(!failure.isNullOrBlank())
            if (outcome == "throw") assertEquals("summary transport failed", failure)
            assertTrue(compactions.filter { it.blocked }.all { it.reason == failure })
            assertFalse(failure.orEmpty().contains("80%"))
        }
    }

    @Test fun hardPressureDoesNotEnableDisabledOrUnconfiguredAutomaticSummary() {
        for (enabled in listOf(false, true)) {
            val messages = escapedHistory(charsPerMessage = 40_000)
            val original = messages.toString()
            val events = mutableListOf<AgentEvent>()
            val provider = ScriptedProvider(emptyList())
            var summaries = 0
            assertThrows(AgentRunCancelledException::class.java) {
                runLoop(messages, provider, events, enabled = enabled,
                    compressor = if (enabled) null else modelConfig(),
                    compactHistory = { source, policy -> summaries++; summarize(source, policy) })
            }
            assertEquals(0, summaries)
            assertEquals(original, messages.toString())
            assertTrue(provider.requests.isEmpty())
            assertFalse(events.any { it is AgentEvent.ContextCompactionStarted })
            assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().single().blocked)
        }
    }

    @Test fun cancellationDuringHardPressureSummaryNeverCommits() {
        val messages = escapedHistory(charsPerMessage = 40_000)
        val original = messages.toString()
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(emptyList())
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
        assertTrue(provider.requests.isEmpty())
        assertFalse(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.applied })
    }

    @Test fun repeatedProviderOverflowStopsAfterOneSuccessfulReductionAndRetry() {
        val messages = smallHistory()
        lateinit var liveBeforeRetryOverflow: String
        val provider = ScriptedProvider(listOf(
            { _, _ -> throw overflow() },
            { _, _ ->
                liveBeforeRetryOverflow = messages.toString()
                throw overflow()
            },
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
        assertTrue(blocked.reason.orEmpty().contains("提供方确认上下文超限"))
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

    // Request expectations use the same text-only wire projection as AgentLoop.
    private fun wireJson(messages: JSONArray): String =
        AgentRequestMediaPolicy.filter(messages, false, false).toString()

    private fun assertAutomaticRecovery(
        messages: JSONArray,
        provider: ScriptedProvider,
        config: AgentModelClient.ModelConfig = modelConfig(),
    ) {
        val events = mutableListOf<AgentEvent>()
        val decisionTokens = requestTokens(messages)
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
        assertUsageBeforeStart(events, decisionTokens)
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
