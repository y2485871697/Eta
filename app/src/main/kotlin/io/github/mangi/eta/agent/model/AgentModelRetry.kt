package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        var envelopeRetries = 0
        var attemptRequest = request
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var toolDeliveryPossible = false
            var callbackFailure: Exception? = null
            var sawCompleted = false
            var sawVisibleText = false
            // 每次 provider.complete 尝试独占一个投递闸门。SSE 的 finish() 先 countDown 再 cancel，
            // 收集线程可能在读线程仍处于回调中时就从 complete 返回；闸门保证被取代的旧尝试的迟到
            // 回调不再进入 onProviderEvent，避免覆盖后续请求记录的 usage。
            val deliveryGate = ProviderEventDeliveryGate()
            try {
                val response = try {
                    provider.complete(attemptRequest, controller) { event ->
                        deliveryGate.deliver {
                            // Mark before calling consumers: a throwing callback may already have
                            // delivered a tool. Starts, deltas, and orphan ends all forbid replay.
                            if (when (event) {
                                    is ProviderEvent.HostedToolStarted, is ProviderEvent.HostedToolFinished -> true
                                    is ProviderEvent.BlockStart -> event.kind == AssistantBlockKind.TOOL_CALL
                                    is ProviderEvent.BlockDelta -> event.kind == AssistantBlockKind.TOOL_CALL
                                    is ProviderEvent.BlockEnd -> event.kind == AssistantBlockKind.TOOL_CALL
                                    else -> false
                                }
                            ) toolDeliveryPossible = true
                            if (event is ProviderEvent.Completed) sawCompleted = true
                            if (
                                (event is ProviderEvent.BlockDelta &&
                                    event.kind == AssistantBlockKind.TEXT && event.delta.isNotBlank()) ||
                                (event is ProviderEvent.BlockEnd &&
                                    event.kind == AssistantBlockKind.TEXT && event.content.isNotBlank())
                            ) {
                                sawVisibleText = true
                            }
                            try {
                                onProviderEvent(round, event)
                            } catch (failure: Exception) {
                                callbackFailure = failure
                                throw failure
                            }
                        }
                    }
                } finally {
                    // 封闸并等到在途回调结束：只等回调本身，不等网络线程收尾，也不改动 SSE 取消时机。
                    deliveryGate.close()
                }
                callbackFailure?.let { throw it }
                return Result(round, response)
            } catch (failure: Exception) {
                callbackFailure?.let { throw it }
                controller.throwIfCancelled()
                // 还没吐出可见正文就被 steering/暂停打断：当作空助手回合，Loop 继续同一 run。
                // 已有可见正文时必须由 Provider 带回部分内容，这里不能用空消息盖掉。
                if (
                    (controller.hasPendingSteering || controller.hasPausedInterrupt) &&
                    !sawVisibleText &&
                    !toolDeliveryPossible &&
                    !sawCompleted &&
                    failure !is AgentModelFailure
                ) {
                    return Result(
                        round,
                        ProviderResponse(
                            org.json.JSONObject()
                                .put("role", "assistant")
                                .put("content", "")
                                .put("finish_reason", "stop"),
                        ),
                    )
                }
                if (Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                val reasonDetail = AgentHttpFailureDiagnostics.safe(classified.message.orEmpty(), listOf(request.config.apiKey), 600)
                // Log the first failure, including terminal/non-retryable responses, before scheduling retries.
                if (classified.diagnostic.isNotBlank()) runCatching {
                    io.github.mangi.eta.core.AndroidAgentLogger.warn(
                        "Model HTTP failure: provider=${AgentHttpFailureDiagnostics.safe(provider.id, limit = 80)}, " +
                            "model=${AgentHttpFailureDiagnostics.safe(request.config.model, limit = 120)}, " +
                            "round=$round, attempt=${retries + 1}, " +
                            AgentHttpFailureDiagnostics.safe(classified.diagnostic, listOf(request.config.apiKey), 4000),
                    )
                }
                val envelopeRejected = classified.code == ResponsesToolEnvelopeRecovery.CODE
                val correctionAllowed = envelopeRejected && classified.envelopeCorrectionAllowed &&
                    provider.capabilities.endpoint == EndpointKind.RESPONSES
                if (toolDeliveryPossible || sawCompleted || sawVisibleText ||
                    (envelopeRejected && !correctionAllowed) ||
                    (!envelopeRejected && !classified.retryable)
                ) {
                    throw classified
                }
                if (envelopeRetries >= ResponsesToolEnvelopeRecovery.MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "工具封装 JSON 校验连续失败，已纠错重试 ${ResponsesToolEnvelopeRecovery.MAX_RETRIES} 次，停止自动重试；未执行被拒绝的工具调用。",
                        classified,
                        diagnostic = classified.diagnostic,
                    )
                }
                if (retries >= MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                        diagnostic = classified.diagnostic,
                    )
                }
                if (envelopeRejected) {
                    // Always start from the original history: one hint, never accumulated
                    // rejected generations, fabricated tool results, or guessed JSON fixes.
                    attemptRequest = ResponsesToolEnvelopeRecovery.corrected(request)
                }
                if (envelopeRejected || envelopeRetries > 0) envelopeRetries += 1
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, if (envelopeRetries > 0) envelopeRetries else retries, if (envelopeRetries > 0) ResponsesToolEnvelopeRecovery.MAX_RETRIES else MAX_RETRIES, delayMs.toInt(), classified.code, reasonDetail))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终推理摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L
    }
}
