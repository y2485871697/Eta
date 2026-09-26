package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real chunk/merge/repair pipeline, not just the budget arithmetic. */
class AgentSummaryOutputRetryTest {
    private data class Attempt(
        val input: String,
        val tools: String,
        val session: String,
        val window: Int,
        val output: Int,
        val inputTokens: Int,
        val controller: AgentRunController,
    )

    private fun model(window: Int) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "summary-output-retry-test",
        systemPrompt = "", contextWindow = window,
    )

    private fun summary(marker: String) = "[Conversation summary]\n" +
        AgentContextCompactor.SUMMARY_SECTIONS.joinToString("\n") { "## $it\n- (none)" } + "\n- $marker"

    private fun response(text: String, finish: String = "stop") = JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", finish)

    private fun tail() = listOf(
        AgentModelClient.ConversationMessage("user", "LIVE_TAIL_MUST_NOT_SEND 😀", turnId = "live-turn"),
        AgentModelClient.ConversationMessage("assistant", toolCallsJson = JSONArray().put(JSONObject()
            .put("id", "live-call").put("type", "function").put("function", JSONObject()
                .put("name", "terminal").put("arguments", "{\"command\":\"echo untouched\"}"))).toString(), turnId = "live-turn"),
        AgentModelClient.ConversationMessage("tool", "LIVE_TOOL_RESULT", toolCallId = "live-call", turnId = "live-turn"),
    )

    private fun twoChunks() = listOf(
        AgentModelClient.ConversationMessage("user", "FIRST_SOURCE " + "中".repeat(65_000)),
        AgentModelClient.ConversationMessage("assistant", "SECOND_SOURCE " + "文".repeat(65_000)),
    ) + tail()

    private fun oneChunk() = listOf(
        AgentModelClient.ConversationMessage("user", "OLD_SOURCE " + "a".repeat(8_000)),
    ) + tail()

    private fun fragmentedSource() = listOf(
        AgentModelClient.ConversationMessage("user", "中".repeat(100_000)),
    ) + tail()

    private fun provider(
        attempts: MutableList<Attempt>,
        reply: (Int, AgentRunController) -> JSONObject,
    ) = object : AgentProviderClient {
        override val id = "summary-output-retry-test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            val attempt = Attempt(request.messages.toString(), request.tools.toString(), request.sessionId,
                requireNotNull(request.config.contextWindow), requireNotNull(request.config.summaryOutputLimit),
                AgentContextBudget.estimate(request.messages) + AgentContextBudget.countTokens(request.tools.toString()), runController)
            attempts += attempt
            // Include protocol/tool overhead and the safety margin, not just the source text.
            assertTrue(attempt.output in 1..32_768)
            val safety = maxOf(512, attempt.window / 20)
            assertTrue("input=${attempt.inputTokens}, output=${attempt.output}, window=${attempt.window}",
                attempt.inputTokens.toLong() + attempt.output + safety <= attempt.window)
            assertEquals(0, request.tools.length())
            assertFalse(request.config.hostedWebSearchEnabled)
            assertFalse(attempt.input.contains("LIVE_TAIL_MUST_NOT_SEND"))
            assertFalse(attempt.input.contains("LIVE_TOOL_RESULT"))
            onEvent(ProviderEvent.RequestStarted)
            val result = reply(attempts.size, runController)
            onEvent(ProviderEvent.ResponseHeaders(200))
            return ProviderResponse(result)
        }
    }

    private fun compress(
        source: List<AgentModelClient.ConversationMessage>,
        window: Int,
        provider: AgentProviderClient,
        controller: AgentRunController = AgentRunController(),
    ) = AgentContextCompactor.compress(source, AgentContextCompactor.Config(1, model(window), provider),
        keepStartOverride = source.size - 3, controller = controller,
        toolExecutor = AgentModelClient.ToolExecutor { error("Summaries must never execute tools") })

    private fun assertSameInput(first: Attempt, retry: Attempt) {
        assertEquals(first.input, retry.input)
        assertEquals(first.tools, retry.tools)
        assertEquals(first.session, retry.session)
        assertEquals(first.window, retry.window)
        assertEquals(first.inputTokens, retry.inputTokens)
        // The enlarged attempt belongs to the same deadline/cancellation scope.
        assertSame(first.controller, retry.controller)
    }

    private fun assertMergeRetry(attempts: List<Attempt>) {
        assertEquals(4, attempts.size)
        assertEquals(listOf(16_000, 16_000, 16_000, 32_000), attempts.map { it.output })
        assertTrue(attempts.all { it.window == 260_000 })
        assertTrue(attempts[0].input.contains("Source chunk 1/2"))
        assertTrue(attempts[0].input.contains("FIRST_SOURCE"))
        assertFalse(attempts[0].input.contains("SECOND_SOURCE"))
        assertTrue(attempts[1].input.contains("Source chunk 2/2"))
        assertTrue(attempts[1].input.contains("SECOND_SOURCE"))
        assertFalse(attempts[1].input.contains("FIRST_SOURCE"))
        val merge = attempts[2].input
        assertTrue(merge.contains("Reconcile chronological partial checkpoints"))
        assertTrue(merge.contains("Intermediate checkpoint 1/2"))
        assertTrue(merge.contains("Intermediate checkpoint 2/2"))
        assertTrue(merge.contains("CHUNK_ONE"))
        assertTrue(merge.contains("CHUNK_TWO"))
        assertFalse(merge.contains("FIRST_SOURCE"))
        assertFalse(merge.contains("SECOND_SOURCE"))
        assertFalse(merge.contains("PARTIAL_MERGE"))
        assertSameInput(attempts[2], attempts[3])
        for (attempt in attempts.take(3)) {
            assertTrue(attempt.inputTokens <= AgentCompressionBoundary.inputLimit(128_000, 16_000))
        }
    }

    private fun assertTailUnchanged(source: List<AgentModelClient.ConversationMessage>, result: List<AgentModelClient.ConversationMessage>) {
        assertEquals(4, result.size)
        source.takeLast(3).zip(result.takeLast(3)).forEach { (original, retained) -> assertSame(original, retained) }
    }

    @Test fun twoCompletedChunksRetryOnlyTheTruncatedMergeWithIdenticalInput() {
        val source = twoChunks()
        val before = source.toList()
        val attempts = mutableListOf<Attempt>()
        val result = compress(source, 260_000, provider(attempts) { call, _ ->
            when (call) {
                1 -> response(summary("CHUNK_ONE"))
                2 -> response(summary("CHUNK_TWO"))
                // Even a structurally valid checkpoint is unusable when its finish reason is OUTPUT_LIMIT.
                3 -> response(summary("PARTIAL_MERGE"), "length")
                4 -> response(summary("FINAL_MERGE"))
                else -> error("Unexpected extra request")
            }
        })
        assertMergeRetry(attempts)
        assertEquals(AgentContextCompactor.coerceSummary(summary("FINAL_MERGE")), result.first().content)
        assertFalse(result.first().content.contains("PARTIAL_MERGE"))
        assertFalse(result.first().content.contains("CHUNK_ONE"))
        assertFalse(result.first().content.contains("CHUNK_TWO"))
        assertEquals(before, source)
        assertTailUnchanged(source, result)
    }

    @Test fun mergeTruncatedTwiceStopsAfterFourCallsWithoutCommittingChunksOrPartialMerge() {
        val source = twoChunks()
        val before = source.toList()
        val attempts = mutableListOf<Attempt>()
        var committed = source
        val failure = assertThrows(IllegalArgumentException::class.java) {
            committed = compress(source, 260_000, provider(attempts) { call, _ ->
                when (call) {
                    1 -> response(summary("CHUNK_ONE"))
                    2 -> response(summary("CHUNK_TWO"))
                    3, 4 -> response(summary("PARTIAL_MERGE"), "length")
                    else -> error("Must not restart chunks, repair a partial response, or retry a third time")
                }
            })
        }
        assertMergeRetry(attempts)
        assertTrue(failure.message.orEmpty().contains("phase=merge"))
        assertTrue(failure.message.orEmpty().contains("生成上限=32000"))
        assertTrue(failure.message.orEmpty().contains("已重试=1"))
        assertTrue(failure.cause?.message.orEmpty().contains("retry_exhausted"))
        assertSame(source, committed)
        assertEquals(before, source)
        before.takeLast(3).zip(committed.takeLast(3)).forEach { (original, retained) -> assertSame(original, retained) }
    }

    @Test fun serverRejectionOfLargerMergeBudgetDoesNotStartReasoningFallbackOrRepair() {
        for (reason in listOf("max_tokens exceeds supported maximum", "invalid reasoning_effort with larger max_tokens")) {
            val source = twoChunks()
            val before = source.toList()
            val attempts = mutableListOf<Attempt>()
            val rejection = AgentModelFailure("HTTP_400", false, reason)
            var committed = source
            val failure = assertThrows(IllegalArgumentException::class.java) {
                committed = compress(source, 260_000, provider(attempts) { call, _ ->
                    when (call) {
                        1 -> response(summary("CHUNK_ONE"))
                        2 -> response(summary("CHUNK_TWO"))
                        3 -> response(summary("PARTIAL_MERGE"), "length")
                        4 -> throw rejection
                        else -> error("An output-budget rejection must not enter the reasoning fallback ladder")
                    }
                })
            }
            assertMergeRetry(attempts)
            assertSame(rejection, failure.cause)
            assertTrue(failure.message.orEmpty().contains("phase=merge"))
            assertTrue(failure.message.orEmpty().contains("code=HTTP_400"))
            assertTrue(failure.message.orEmpty().contains("已重试=1"))
            assertSame(source, committed)
            assertEquals(before, source)
            before.takeLast(3).zip(committed.takeLast(3)).forEach { (original, retained) -> assertSame(original, retained) }
        }
    }

    @Test fun window260kStillPlansAt128kButRetryUsesTheRealOutputRoom() {
        val source = fragmentedSource()
        val before = source.toList()
        val attempts = mutableListOf<Attempt>()
        val result = compress(source, 260_000, provider(attempts) { call, _ ->
            when (call) {
                1 -> response(summary("PARTIAL_CHUNK"), "length")
                2 -> response(summary("CHUNK_ONE"))
                3 -> response(summary("CHUNK_TWO"))
                4 -> response(summary("FINAL_MERGE"))
                else -> error("Unexpected extra request")
            }
        })
        assertEquals(4, attempts.size)
        assertEquals(listOf(16_000, 32_000, 16_000, 16_000), attempts.map { it.output })
        assertTrue(attempts.all { it.window == 260_000 })
        assertTrue(attempts[0].input.contains("Source chunk 1/2"))
        assertTrue(attempts[0].input.contains("<history-fragment>"))
        assertTrue(attempts[2].input.contains("Source chunk 2/2"))
        assertTrue(attempts[3].input.contains("Intermediate checkpoint 2/2"))
        assertSameInput(attempts[0], attempts[1])
        for (index in listOf(0, 2, 3)) {
            assertTrue(attempts[index].inputTokens <= AgentCompressionBoundary.inputLimit(128_000, 16_000))
        }
        // The same planned input cannot fit this enlarged output in the artificial 128K window.
        assertTrue(attempts[1].inputTokens > AgentCompressionBoundary.inputLimit(128_000, 32_000))
        assertNull(AgentContextCompactor.summaryRetryLimit(16_000, 128_000, attempts[0].inputTokens))
        assertFalse(attempts.drop(2).any { it.input.contains("PARTIAL_CHUNK") })
        assertEquals(AgentContextCompactor.coerceSummary(summary("FINAL_MERGE")), result.first().content)
        assertEquals(before, source)
        assertTailUnchanged(source, result)
    }

    @Test fun nearFull128kInputDoesNotRetryWithoutMinimumOutputHeadroom() {
        val source = fragmentedSource()
        val before = source.toList()
        val attempts = mutableListOf<Attempt>()
        var committed = source
        val failure = assertThrows(IllegalArgumentException::class.java) {
            committed = compress(source, 128_000, provider(attempts) { _, _ ->
                response(summary("PARTIAL_CHUNK"), "length")
            })
        }
        assertEquals(1, attempts.size)
        val first = attempts.single()
        assertEquals(128_000, first.window)
        assertEquals(16_000, first.output)
        assertTrue(first.input.contains("Source chunk 1/2"))
        val spare = AgentCompressionBoundary.inputLimit(128_000, 0) - first.inputTokens - first.output
        assertTrue("remaining growth=$spare", spare in 0 until 4096)
        assertTrue(failure.message.orEmpty().contains("phase=chunk_1_of_2"))
        assertTrue(failure.message.orEmpty().contains("insufficient_window_or_cap"))
        assertTrue(failure.message.orEmpty().contains("已重试=0"))
        assertSame(source, committed)
        assertEquals(before, source)
    }

    @Test fun exactly4096AdditionalTokensPermitRetryButOneLessDoesNot() {
        val window = 128_000
        val current = 16_000
        val input = AgentCompressionBoundary.inputLimit(window, 0) - current - 4096
        assertEquals(20_096, AgentContextCompactor.summaryRetryLimit(current, window, input))
        assertEquals(input, AgentCompressionBoundary.inputLimit(window, 20_096))
        assertNull(AgentContextCompactor.summaryRetryLimit(current, window, input + 1))
        assertEquals(32_000, AgentContextCompactor.summaryRetryLimit(current, window, 1000))
        assertEquals(32_768, AgentContextCompactor.summaryRetryLimit(16_384, 260_000, 1000))
        assertNull(AgentContextCompactor.summaryRetryLimit(32_768, 260_000, 1000))
    }

    @Test fun smallWindowMayDouble2048EvenThoughGrowthIsLessThan4096() {
        val window = 8192
        val current = AgentContextCompactor.summaryGenerationLimit(window)
        assertEquals(2048, current)
        val exactInput = AgentCompressionBoundary.inputLimit(window, 0) - 4096
        assertEquals(4096, AgentContextCompactor.summaryRetryLimit(current, window, exactInput))
        assertNull(AgentContextCompactor.summaryRetryLimit(current, window, exactInput + 1))
        val source = oneChunk()
        val attempts = mutableListOf<Attempt>()
        val result = compress(source, window, provider(attempts) { call, _ ->
            when (call) {
                1 -> response(summary("PARTIAL_SMALL"), "length")
                2 -> response(summary("FINAL_SMALL"))
                else -> error("Unexpected extra request")
            }
        })
        assertEquals(listOf(2048, 4096), attempts.map { it.output })
        assertSameInput(attempts[0], attempts[1])
        assertTrue(attempts[1].inputTokens <= exactInput)
        assertEquals(AgentContextCompactor.coerceSummary(summary("FINAL_SMALL")), result.first().content)
        assertTailUnchanged(source, result)
    }

    @Test fun formatRepairAfterAnEnlargedSuccessRestartsAtInitialBudgetAndCanRetryOnce() {
        val source = oneChunk()
        val attempts = mutableListOf<Attempt>()
        val result = compress(source, 260_000, provider(attempts) { call, _ ->
            when (call) {
                1 -> response(summary("PARTIAL_SOURCE"), "length")
                2 -> response("Unstructured but complete checkpoint: COMPLETE_RAW_FACT and FINAL_RAW_FACT")
                3 -> response(summary("PARTIAL_REPAIR"), "length")
                4 -> response(summary("FINAL_REPAIRED"))
                else -> error("Unexpected extra request")
            }
        })
        assertEquals(listOf(16_000, 32_000, 16_000, 32_000), attempts.map { it.output })
        assertTrue(attempts.all { it.window == 260_000 })
        assertSameInput(attempts[0], attempts[1])
        assertSameInput(attempts[2], attempts[3])
        assertNotSame(attempts[1].controller, attempts[2].controller)
        assertNotEquals(attempts[1].session, attempts[2].session)
        val repair = attempts[2].input
        assertTrue(repair.contains("Rewrite the checkpoint"))
        assertTrue(repair.contains("COMPLETE_RAW_FACT"))
        assertTrue(repair.contains("FINAL_RAW_FACT"))
        assertFalse(repair.contains("OLD_SOURCE"))
        assertFalse(repair.contains("PARTIAL_SOURCE"))
        assertFalse(repair.contains("PARTIAL_REPAIR"))
        assertEquals(AgentContextCompactor.coerceSummary(summary("FINAL_REPAIRED")), result.first().content)
        assertTailUnchanged(source, result)
    }

    @Test fun oversizedFormatRepairAfterEnlargedSuccessIsRejectedBeforeSending() {
        val source = oneChunk()
        val before = source.toList()
        val attempts = mutableListOf<Attempt>()
        var committed = source
        val failure = assertThrows(IllegalArgumentException::class.java) {
            committed = compress(source, 8192, provider(attempts) { call, _ ->
                when (call) {
                    1 -> response(summary("PARTIAL_SOURCE"), "length")
                    2 -> response("Unstructured checkpoint " + "x".repeat(50_000) + " FINAL_RAW_FACT")
                    else -> error("Must reject over-budget repair, not trim it or send it")
                }
            })
        }
        assertEquals(listOf(2048, 4096), attempts.map { it.output })
        assertSameInput(attempts[0], attempts[1])
        assertTrue(failure.message.orEmpty().contains("摘要请求超过输入预算"))
        assertSame(source, committed)
        assertEquals(before, source)
    }

    @Test fun parentCancellationDuringEnlargedMergeRejectsEvenACompleteLateResult() {
        assertLateMergeResultRejected(cancelParent = true)
    }

    @Test fun expiredRequestControllerDuringEnlargedMergeRejectsLateResultWithoutWaiting120Seconds() {
        assertLateMergeResultRejected(cancelParent = false)
    }

    private fun assertLateMergeResultRejected(cancelParent: Boolean) {
        val source = twoChunks()
        val before = source.toList()
        val parent = AgentRunController()
        val attempts = mutableListOf<Attempt>()
        var resourceCancelled = false
        var returnedLateResult = false
        var committed = source
        val summarizer = provider(attempts) { call, timed ->
            when (call) {
                1 -> response(summary("CHUNK_ONE"))
                2 -> response(summary("CHUNK_TWO"))
                3 -> response(summary("PARTIAL_MERGE"), "length")
                4 -> {
                    val binding = timed.register { resourceCancelled = true }
                    try {
                        assertFalse(timed.isCancelled)
                        // Cancelling the request controller simulates the watchdog's action;
                        // cancelling the parent exercises its live binding during the retry.
                        if (cancelParent) parent.cancel() else timed.cancel()
                        assertTrue(timed.isCancelled)
                        assertTrue(resourceCancelled)
                        returnedLateResult = true
                        response(summary("LATE_COMPLETE_MERGE"))
                    } finally {
                        binding.close()
                    }
                }
                else -> error("Cancelled retry must never start another request")
            }
        }
        if (cancelParent) {
            assertThrows(AgentRunCancelledException::class.java) {
                committed = compress(source, 260_000, summarizer, parent)
            }
        } else {
            val failure = assertThrows(IllegalStateException::class.java) {
                committed = compress(source, 260_000, summarizer, parent)
            }
            assertTrue(failure.message.orEmpty().contains("摘要超时"))
            assertTrue(failure.message.orEmpty().contains("120 秒总时限"))
            assertFalse(parent.isCancelled)
        }
        assertEquals(120_000L, AgentContextCompactor.SUMMARY_REQUEST_TIMEOUT_MS)
        assertMergeRetry(attempts)
        assertTrue(resourceCancelled)
        assertTrue(returnedLateResult)
        assertSame(source, committed)
        assertEquals(before, source)
        before.takeLast(3).zip(committed.takeLast(3)).forEach { (original, retained) -> assertSame(original, retained) }
    }
}
