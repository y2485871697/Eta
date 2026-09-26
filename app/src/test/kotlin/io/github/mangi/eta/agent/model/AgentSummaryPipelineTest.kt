package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentSummaryPipelineTest {
    private fun validSummary() = "[Conversation summary]\n" + AgentContextCompactor.SUMMARY_SECTIONS.joinToString("\n") { "## $it\n- (none)" }
    private fun config() = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test",
        systemPrompt = "", contextWindow = 200_000)
    private fun history() = listOf(AgentModelClient.ConversationMessage("user", "OLD " + "a".repeat(10_000)),
        AgentModelClient.ConversationMessage("assistant", "verified old result"), AgentModelClient.ConversationMessage("user", "protected"))
    private fun provider(block: (ProviderRequest) -> JSONObject) = object : AgentProviderClient {
        override val id = "summary-test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = ProviderResponse(block(request))
    }
    private fun response(text: String, finish: String = "stop") = JSONObject().put("role", "assistant").put("content", text).put("finish_reason", finish)

    @Test fun streamingTimingObservationDoesNotChangeSummaryOrExposeReasoning() {
        val streaming = object : AgentProviderClient {
            override val id = "summary-streaming-timing"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                onEvent(ProviderEvent.RequestStarted)
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "private reasoning"))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, ""))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, validSummary()))
                return ProviderResponse(response(validSummary()))
            }
        }
        val result = AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), streaming))
        // Stored checkpoints use the canonical marker, not the provider's English marker.
        assertEquals(AgentContextCompactor.coerceSummary(validSummary()), result.first().content)
        assertEquals(history().last(), result.last())
        assertFalse(result.first().content.contains("private reasoning"))
    }

    @Test fun oversizedIntermediateChunksCanStillConsolidateWithinFinalBudget() {
        var calls = 0
        val source = listOf(
            AgentModelClient.ConversationMessage("user", "x".repeat(160_000)),
            AgentModelClient.ConversationMessage("assistant", "y".repeat(160_000)),
            AgentModelClient.ConversationMessage("user", "protected"),
        )
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(
            1, config().copy(contextWindow = 64_000), provider {
                calls++
                if (calls <= 2) response(validSummary() + "\n- " + "z".repeat(2000))
                else response(validSummary())
            },
        ))
        assertEquals(3, calls)
        assertEquals(source.last(), result.last())
        assertTrue(AgentContextBudget.countTokens(result.first().content) < source.sumOf { AgentContextBudget.countMessage(it) })
    }

    @Test fun splitChunksFitTheSameInputBudgetUsedByTheSummaryRequest() {
        var calls = 0
        val source = listOf(
            AgentModelClient.ConversationMessage("user", "x".repeat(160_000)),
            AgentModelClient.ConversationMessage("assistant", "y".repeat(160_000)),
            AgentModelClient.ConversationMessage("user", "protected"),
        )
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(
            1, config().copy(contextWindow = 128_000), provider {
                calls++
                val window = requireNotNull(it.config.contextWindow)
                val output = requireNotNull(it.config.summaryOutputLimit)
                val used = AgentContextBudget.estimate(it.messages) + AgentContextBudget.countTokens(it.tools.toString())
                assertTrue(used <= AgentCompressionBoundary.inputLimit(window, output))
                response(validSummary())
            },
        ))
        assertTrue(calls >= 1)
        assertEquals(source.last(), result.last())
    }

    @Test fun summaryCallIsOneShotCappedAndNeverExecutesTools() {
        var calls = 0
        val result = AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), provider {
            calls++
            assertEquals(16000, it.config.summaryOutputLimit)
            assertEquals(0, it.tools.length())
            assertFalse(it.config.hostedWebSearchEnabled)
            assertTrue(it.messages.toString().contains("historical"))
            response(validSummary())
        }), toolExecutor = AgentModelClient.ToolExecutor { error("Must never execute") })
        assertEquals(1, calls)
        assertEquals("user", result.first().role)
        assertEquals(history().last(), result.last())
        assertTrue(result.first().content.contains("## Critical Context"))
    }

    @Test fun truncatedEmptyMalformedAndToolCallingSummariesAreRejected() {
        val bad = listOf(response(validSummary(), "length"), response(""), response("not structured"),
            response(validSummary()).put("tool_calls", JSONArray().put(JSONObject().put("id", "bad"))))
        for (output in bad) {
            val source = history()
            val original = source.toList()
            assertThrows(RuntimeException::class.java) {
                AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, config(), provider { output }))
            }
            assertEquals(original, source)
        }
    }

    @Test fun cancellationIsPropagatedWithoutReplacingTheHistory() {
        val controller = AgentRunController()
        val source = history()
        assertThrows(AgentRunCancelledException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, config(), provider {
                controller.cancel()
                response(validSummary())
            }), controller = controller)
        }
        assertEquals("protected", source.last().content)
    }

    @Test fun parentCancellationImmediatelyCancelsInFlightProviderResource() {
        val parent = AgentRunController()
        var resourceCancelled = false
        val blockingProvider = object : AgentProviderClient {
            override val id = "cancel-resource"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                val binding = runController.register { resourceCancelled = true }
                try {
                    parent.cancel()
                    assertTrue("Parent cancellation must reach the active HTTP controller", runController.isCancelled)
                    assertTrue(resourceCancelled)
                    throw java.io.IOException("socket closed by cancellation")
                } finally { binding.close() }
            }
        }
        assertThrows(AgentRunCancelledException::class.java) {
            AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), blockingProvider), controller = parent)
        }
        assertTrue(resourceCancelled)
    }

    @Test fun completedSummaryDetachesParentCancellationBinding() {
        val parent = AgentRunController()
        var captured: AgentRunController? = null
        val completedProvider = object : AgentProviderClient {
            override val id = "cancel-binding"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                captured = runController
                return ProviderResponse(response(validSummary()))
            }
        }
        AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), completedProvider), controller = parent)
        parent.cancel()
        assertNotNull(captured)
        assertFalse(captured!!.isCancelled)
    }

    @Test fun interruptedUiWorkerCancelsProviderWithoutWaitingForSummaryTimeout() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val blockingProvider = object : AgentProviderClient {
            override val id = "interrupt-resource"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                val binding = runController.register { cancelled.set(true) }
                try {
                    entered.countDown()
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                    while (!cancelled.get() && System.nanoTime() < deadline) {
                        // Models a network call whose resource must be explicitly closed.
                        java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
                    }
                    if (!cancelled.get()) throw AssertionError("Provider was not cancelled")
                    throw java.io.IOException("cancelled")
                } finally { binding.close() }
            }
        }
        val worker = Thread {
            try {
                AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), blockingProvider))
            } catch (caught: Throwable) { failure.set(caught) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(4000)
            assertFalse(worker.isAlive)
            assertTrue(cancelled.get())
            assertTrue(failure.get() is InterruptedException)
        } finally { worker.interrupt(); worker.join(4000) }
    }

    @Test fun formatRepairAlsoUsesLinkedCancellationController() {
        val parent = AgentRunController()
        var calls = 0
        val repairingProvider = object : AgentProviderClient {
            override val id = "cancel-repair"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (++calls == 1) return ProviderResponse(response("unstructured checkpoint"))
                parent.cancel()
                assertTrue(runController.isCancelled)
                return ProviderResponse(response(validSummary()))
            }
        }
        assertThrows(AgentRunCancelledException::class.java) {
            AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), repairingProvider), controller = parent)
        }
        assertEquals(2, calls)
    }

    @Test fun outputLimitFailureReportsNormalizedReasonAndNeverAcceptsPartialSummary() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), provider {
                response(validSummary(), "length")
            }))
        }
        assertTrue(failure.message.orEmpty().contains("OUTPUT_LIMIT"))
    }

    @Test fun truncatedSummaryOnASmallWindowRetriesOnceWithLargerGenerationBudget() {
        val limits = mutableListOf<Int>()
        val result = AgentContextCompactor.compress(history(), AgentContextCompactor.Config(
            1, config().copy(contextWindow = 40_000), provider {
                limits += requireNotNull(it.config.summaryOutputLimit)
                if (limits.size == 1) response("INCOMPLETE_MUST_NOT_SURVIVE", "length") else response(validSummary())
            },
        ))
        assertEquals(listOf(8192, 16384), limits)
        assertFalse(result.first().content.contains("INCOMPLETE_MUST_NOT_SURVIVE"))
        assertEquals(history().last(), result.last())
    }

    @Test fun largeWindowInitialBudgetLeavesRoomBelowTheRetryCap() {
        assertEquals(16384, AgentContextCompactor.summaryGenerationLimit(200_000))
        assertEquals(16000, AgentContextCompactor.summaryGenerationLimit(128_000))
        assertEquals(8192, AgentContextCompactor.summaryGenerationLimit(64_000))
        assertEquals(2048, AgentContextCompactor.summaryGenerationLimit(8192))
        assertTrue(AgentContextCompactor.SUMMARY_GENERATION_INITIAL_CAP < AgentContextCompactor.SUMMARY_GENERATION_CAP)
    }

    @Test fun repeatedOutputLimitStopsAfterOneRetryAndPreservesHistory() {
        var calls = 0
        val source = history()
        val snapshot = source.toList()
        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, config(), provider {
                calls++
                response(validSummary(), "length")
            }))
        }
        assertEquals(2, calls)
        assertTrue(error.message.orEmpty().contains("生成上限=32000"))
        assertTrue(error.message.orEmpty().contains("已重试=1"))
        assertEquals(snapshot, source)
    }

    @Test fun outputRetryRespectsWindowRoomAndHardCap() {
        assertEquals(16384, AgentContextCompactor.summaryGenerationLimit(200_000))
        assertEquals(2048, AgentContextCompactor.summaryGenerationLimit(8192))
        assertEquals(24000, AgentContextCompactor.summaryRetryLimit(12_000, 200_000, 1000))
        assertEquals(32000, AgentContextCompactor.summaryRetryLimit(16000, 128_000, 1000))
        // Tiny leftover room is not worth a second request.
        assertNull(AgentContextCompactor.summaryRetryLimit(2048, 10_000, 6500))
        assertEquals(32768, AgentContextCompactor.summaryRetryLimit(16384, 200_000, 1000))
        assertNull(AgentContextCompactor.summaryRetryLimit(32768, 200_000, 1000))
    }

    @Test fun cancellationAfterTruncationPreventsRetry() {
        val parent = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), provider {
                calls++
                parent.cancel()
                response("partial", "length")
            }), controller = parent)
        }
        assertEquals(1, calls)
    }

    @Test fun outputLimitWithToolCallsIsNotRetried() {
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), provider {
                calls++
                response("partial", "length").put("tool_calls", JSONArray().put(JSONObject().put("id", "bad")))
            }))
        }
        assertEquals(1, calls)
    }

    @Test fun contentFilterIsNotRetriedAsOutputLimit() {
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), provider {
                calls++
                response("filtered", "content_filter")
            }))
        }
        assertEquals(1, calls)
    }

    @Test fun completeReducingCheckpointHasNoFixedLengthAcceptanceCap() {
        var calls = 0
        val longer = validSummary() + "\n- " + "x".repeat(8000)
        val source = history()
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, config(), provider {
            calls++
            assertEquals(16000, it.config.summaryOutputLimit)
            assertFalse(it.messages.toString().contains("Target approximately"))
            assertFalse(it.messages.toString().contains("characters in TOTAL"))
            response(longer)
        }))
        assertEquals(1, calls)
        assertEquals(AgentContextCompactor.coerceSummary(longer), result.first().content)
        assertEquals(source.last(), result.last())
    }

    @Test fun nonReducingCheckpointIsRejectedWithoutChangingHistory() {
        val source = history()
        val original = source.toList()
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, config(), provider {
                calls++
                response(validSummary() + "\n- " + "x".repeat(20_000))
            }))
        }
        assertEquals(1, calls)
        assertEquals(original, source)
    }

    @Test fun sameModelReplayRetainsPrefixToolsAndSessionButDoesNotRunAgentLoop() {
        val source = history()
        val system = JSONObject().put("role", "system").put("content", "original instructions")
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "test")))
        val replay = AgentContextCompactor.ReplayContext(JSONArray().put(system),
            JSONArray().put(AgentConversationCodec.toJsonObject(source[0])).put(AgentConversationCodec.toJsonObject(source[1])), tools, "stable-session")
        var calls = 0
        AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, config(), provider {
            calls++
            assertEquals("stable-session", it.sessionId)
            assertEquals(system.toString(), it.messages.getJSONObject(0).toString())
            assertEquals(source[0].content, it.messages.getJSONObject(1).getString("content"))
            assertEquals(tools.toString(), it.tools.toString())
            val instruction = it.messages.getJSONObject(it.messages.length() - 1).getString("content")
            assertTrue(instruction.contains(
                "Output contract for this request"))
            response(validSummary())
        }), replay = replay)
        assertEquals(1, calls)
    }

    @Test fun duplicateMissingAndReorderedHeadingsFailAcceptance() {
        AgentContextCompactor.validateSummary(validSummary())
        assertThrows(IllegalArgumentException::class.java) { AgentContextCompactor.validateSummary("not structured") }
        assertThrows(IllegalArgumentException::class.java) { AgentContextCompactor.validateSummary("## Tasks\n- x") }
        val sections = AgentContextCompactor.SUMMARY_SECTIONS
        fun checkpoint(names: List<String>) = names.joinToString("\n") { "## $it\n- (none)" }
        for (index in sections.indices) {
            assertNull(AgentContextCompactor.coerceSummary(checkpoint(sections.filterIndexed { i, _ -> i != index })))
        }
        assertNull(AgentContextCompactor.coerceSummary(checkpoint(sections + sections.last())))
        assertNull(AgentContextCompactor.coerceSummary(checkpoint(sections.reversed())))
        assertNull(AgentContextCompactor.coerceSummary(validSummary() + "\n## Unknown peer\n- content"))
        assertNull(AgentContextCompactor.coerceSummary(validSummary().replace("## Next Step", "## Next Steps invented")))
        assertNull(AgentContextCompactor.coerceSummary(validSummary().replace("## Pending Jobs\n- (none)", "## Pending Jobs")))
    }

    @Test fun chineseHeadingsAndFencesAreCoercedIntoCheckpoint() {
        val raw = """```markdown
好的，下面是摘要。
[对话摘要]
## 主要请求与意图
- 修压缩
## 关键技术概念
- compaction
## 文件与代码
- /workspace/Eta
## 错误与修复
- 存档 failed
## 待办工作
- 重试
## 当前工作
- 已回滚
## 下一步
- 修校验
## 关键上下文
- 不要丢原文
```""".trimIndent()
        val coerced = AgentContextCompactor.coerceSummary(raw)
        assertNotNull(coerced)
        AgentContextCompactor.validateSummary(coerced!!)
        assertTrue(coerced.startsWith(AgentContextCompactor.SUMMARY_PREFIX_ZH))
        assertTrue(coerced.contains("## Critical Context"))
        assertTrue(coerced.contains("存档 failed"))
        assertFalse(coerced.contains("```"))
    }

    @Test fun malformedSummaryIsRepairedWithASecondFormatOnlyCall() {
        var calls = 0
        val result = AgentContextCompactor.compress(history(), AgentContextCompactor.Config(1, config(), provider {
            calls++
            if (calls == 1) response("下面是摘要\n目标：继续任务")
            else {
                assertTrue(it.messages.toString().contains("Rewrite the checkpoint"))
                assertFalse(it.messages.toString().contains("OLD "))
                response(validSummary())
            }
        }))
        assertEquals(2, calls)
        assertEquals("user", result.first().role)
        assertTrue(result.first().content.contains("## Next Step"))
    }

    @Test fun compressionProbesReasoningFromOffThenRemembersWorkingEffort() {
        CompressionReasoningStore.clearForTests()
        var calls = 0
        val source = history()
        val cfg = config().copy(providerId = "compress-probe", model = "probe-model")
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, cfg, provider {
            calls++
            when (it.config.reasoningEffort) {
                ReasoningEffort.OFF, ReasoningEffort.MINIMAL ->
                    throw AgentModelFailure(
                        "HTTP_400",
                        false,
                        "DeepSeek reasoning_effort 只支持 low、medium、high、xhigh、max",
                    )
                else -> {
                    assertEquals(ReasoningEffort.LOW, it.config.reasoningEffort)
                    response(validSummary())
                }
            }
        }))
        assertEquals(3, calls)
        assertEquals(ReasoningEffort.LOW, CompressionReasoningStore.effortFor(cfg))
        assertEquals("user", result.first().role)

        val again = AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, cfg, provider {
            calls++
            assertEquals(ReasoningEffort.LOW, it.config.reasoningEffort)
            response(validSummary())
        }))
        assertEquals(4, calls)
        assertEquals(history().last(), again.last())
    }

    @Test fun turnIdsPersistButNeverLeakToProviderMessages() {
        val original = AgentModelClient.ConversationMessage("user", "question", turnId = "run-1")
        val json = AgentConversationCodec.toJsonObject(original)
        assertEquals(original, AgentConversationCodec.fromJsonObject(json))
        assertFalse(AgentRequestMediaPolicy.filter(JSONArray().put(json), false, false).toString().contains("eta_turn_id"))
        assertEquals("run-1", json.getString(AgentTurnIdentity.JSON_KEY))
        val messages = listOf(original, AgentModelClient.ConversationMessage("assistant", "step", turnId = "run-1"),
            AgentModelClient.ConversationMessage("user", "supplement", turnId = "run-1"))
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(messages, 1))
    }
    @Test fun orderedMergeReceivesIndependentSuccessfulRunEvenWhenChunkSummariesOmitIt() {
        val run = "35557982887"
        fun pair(callId: String, state: String) = listOf(
            AgentModelClient.ConversationMessage("assistant", toolCallsJson = JSONArray().put(JSONObject().put("id", callId)
                .put("function", JSONObject().put("name", "terminal").put("arguments", JSONObject()
                    .put("command", "gh run view $run -R owner/repo --json status,conclusion").toString()))).toString()),
            AgentModelClient.ConversationMessage("tool", toolCallId = callId, content = JSONObject().put("ok", true)
                .put("exit_code", 0).put("stdout", state).toString()))
        val source = listOf(AgentModelClient.ConversationMessage("user", "编译"),
            AgentModelClient.ConversationMessage("assistant", "a".repeat(160_000))) +
            pair("old", "{\"status\":\"in_progress\"}") +
            AgentModelClient.ConversationMessage("assistant", "b".repeat(160_000)) +
            pair("new", "{\"status\":\"completed\",\"conclusion\":\"success\"}") +
            listOf(AgentModelClient.ConversationMessage("user", "改设置页，之后修复生图参数"),
                AgentModelClient.ConversationMessage("user", "protected"))
        var merged = false
        val before = source.toList()
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, config().copy(contextWindow = 64_000), provider {
            val body = it.messages.toString()
            if (body.contains("Reconcile chronological partial checkpoints")) {
                merged = true
                assertTrue(body.contains("Independent source evidence"))
                assertTrue(body.contains(run)); assertTrue(body.contains("success"))
                assertTrue(body.contains("改设置页，之后修复生图参数"))
                assertTrue(body.contains("Intermediate checkpoint 1/"))
            }
            response(validSummary()) // deliberately loses run facts; independent evidence must survive
        }), keepStartOverride = source.lastIndex)
        assertTrue(merged)
        assertTrue(result.first().content.contains(run)); assertTrue(result.first().content.contains("success"))
        assertSame(source.last(), result.last()); assertEquals(before, source)
    }

    @Test fun formatRepairReceivesFullCheckpointPastOldTwelveThousandLimit() {
        var calls = 0
        val malformed = "unstructured " + "x".repeat(13_000) + " FINAL_SUCCESS_EVIDENCE"
        val source = listOf(AgentModelClient.ConversationMessage("user", "x".repeat(80_000)),
            AgentModelClient.ConversationMessage("user", "protected"))
        AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, config(), provider {
            calls++
            if (calls == 1) response(malformed) else {
                assertTrue(it.messages.toString().contains("FINAL_SUCCESS_EVIDENCE"))
                response(validSummary())
            }
        }))
        assertEquals(2, calls)
    }

    @Test fun singleChunkStalePendingFailsClosedWithoutAdditionalPaidRepairOrTailMutation() {
        val source = listOf(
            AgentModelClient.ConversationMessage("user", "build evidence " + "a".repeat(16_000)),
            AgentModelClient.ConversationMessage("assistant", toolCallsJson = JSONArray().put(JSONObject().put("id", "completed")
                .put("function", JSONObject().put("name", "terminal").put("arguments", JSONObject()
                    .put("command", "gh run view 35557982887 -R owner/repo --json status,conclusion").toString()))).toString()),
            AgentModelClient.ConversationMessage("tool", toolCallId = "completed", content = JSONObject().put("ok", true)
                .put("exit_code", 0).put("stdout", JSONObject().put("status", "completed").put("conclusion", "success").toString()).toString()),
            AgentModelClient.ConversationMessage("user", "protected"))
        val before = source.toList()
        var calls = 0
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, config(), provider {
                calls++
                response(validSummary().replace("## Pending Jobs\n- (none)", "## Pending Jobs\n- 等待工作流 35557982887 完成"))
            }), keepStartOverride = 3)
        }
        assertTrue(failure.message!!.contains("原历史保持不变"))
        assertEquals(1, calls); assertEquals(before, source)
    }

    @Test fun oversizedFormatRepairRefusesInsteadOfTruncatingOrCallingProviderAgain() {
        val source = listOf(AgentModelClient.ConversationMessage("user", "a".repeat(14_000)),
            AgentModelClient.ConversationMessage("user", "protected"))
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, config().copy(contextWindow = 8192), provider {
                calls++
                response("unstructured " + "x".repeat(50_000) + " FINAL_SUCCESS_EVIDENCE")
            }))
        }
        assertEquals(1, calls)
        assertEquals("protected", source.last().content)
    }

}
