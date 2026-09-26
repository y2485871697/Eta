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
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var callbackFailure: Exception? = null
            var sawCompleted = false
            var sawVisibleText = false
            val deliveryGate = ProviderEventDeliveryGate()
            try {
                val response = try {
                    provider.complete(request, controller) { event ->
                        deliveryGate.deliver {
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    if (event is ProviderEvent.Completed) sawCompleted = true
                    if (
                        event is ProviderEvent.BlockDelta &&
                        event.kind == AssistantBlockKind.TEXT &&
                        event.delta.isNotBlank()
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
                } finally { deliveryGate.close() }
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
                    !hostedToolStarted &&
                    !sawCompleted
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
                if (!classified.retryable || hostedToolStarted || sawCompleted || sawVisibleText) {
                    throw classified
                }
                if (retries == MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                        diagnostic = classified.diagnostic,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), classified.code, reasonDetail))
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
