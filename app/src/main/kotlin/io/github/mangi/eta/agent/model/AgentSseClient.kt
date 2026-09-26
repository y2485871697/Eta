package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Blocking SSE collector backed by OkHttp EventSource.
 *
 * Providers keep a synchronous complete() API. EventSource owns framing,
 * charset, and cancellation. Call [SseStream.finish] on terminal events
 * such as `[DONE]` so a keep-alive connection cannot hang the turn.
 */
internal object AgentSseClient {
    fun collect(
        request: Request,
        runController: AgentRunController,
        onOpen: (Int) -> Unit = {},
        onEvent: SseStream.(id: String?, type: String?, data: String) -> Unit,
        shouldIgnoreFailure: () -> Boolean = { false },
        inspectHttpErrorBody: (String) -> Unit = {},
    ) {
        runController.throwIfCancelled()
        val timingId = java.util.UUID.randomUUID().toString().take(8)
        val timings = StreamArrivalStats(System.nanoTime())
        fun reportTimings(final: Boolean = false) {
            timings.report(System.nanoTime(), final)?.let {
                // Diagnostics must never fail or replace the actual network outcome.
                runCatching { io.github.mangi.eta.core.AndroidAgentLogger.info("SseDiag id=$timingId $it") }
            }
        }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val opened = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val eventSourceRef = AtomicReference<EventSource?>(null)
        val stream = SseStream(
            completed = completed,
            done = done,
            cancelSource = { eventSourceRef.get()?.cancel() },
        )
        val emitOpen = onOpen
        val emitEvent = onEvent
        fun httpFailure(response: Response): AgentModelFailure {
            val secrets = request.headers.names().filter {
                it.equals("Authorization", true) || it.contains("key", true) || it.contains("token", true) || it.equals("Cookie", true)
            }.flatMap { name -> request.headers.values(name) }.flatMap { value ->
                listOf(value, value.removePrefix("Bearer ").removePrefix("bearer "))
            }
            val body = runCatching { response.peekBody(64L * 1024).string() }.getOrDefault("")
            inspectHttpErrorBody(body)
            return AgentModelFailure.http(
                status = response.code,
                body = body,
                headers = response.headers,
                secrets = secrets,
            )
        }


        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                opened.set(true)
                try {
                    runController.withTransportCallback { emitOpen(response.code) }
                    if (!response.isSuccessful) {
                        failure.compareAndSet(
                            null,
                            httpFailure(response),
                        )
                        stream.finish()
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    stream.finish()
                }
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                if (completed.get() || runController.hasPendingSteering || runController.isPaused) {
                    stream.finish()
                    return
                }
                try {
                    runController.withTransportCallback { runController.throwIfCancelled() }
                    if (runController.hasPendingSteering || runController.isPaused) {
                        stream.finish()
                        return
                    }
                    val arrivalNs = System.nanoTime()
                    timings.arrival(arrivalNs, data.length)
                    try {
                        runController.withTransportCallback { emitEvent(stream, id, type, data) }
                    } finally {
                        timings.callback(System.nanoTime() - arrivalNs)
                        reportTimings()
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    stream.finish()
                }
            }

            override fun onClosed(eventSource: EventSource) {
                completed.set(true)
                done.countDown()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                if (completed.get()) {
                    done.countDown()
                    return
                }
                if (failure.get() == null) {
                    when {
                        runController.isCancelled ->
                            failure.compareAndSet(null, AgentRunCancelledException())
                        runController.hasPendingSteering || runController.isPaused || runController.hasPausedInterrupt -> Unit
                        response != null && !response.isSuccessful -> {
                            if (!opened.get()) {
                                runCatching { runController.withTransportCallback { emitOpen(response.code) } }
                            }
                            failure.compareAndSet(
                                null,
                                httpFailure(response),
                            )
                        }
                        t != null &&
                            t.message.orEmpty().startsWith("Invalid content-type") -> {
                            val body = response?.let {
                                runCatching { it.body?.string() }.getOrNull()
                            }.orEmpty()
                            failure.compareAndSet(
                                null,
                                AgentModelFailure.unexpectedResponse(
                                    status = response?.code,
                                    contentType = response?.header("Content-Type")
                                        ?: t.message?.substringAfter("Invalid content-type:")?.trim(),
                                    body = body,
                                    cause = t,
                                ),
                            )
                        }
                        t != null &&
                            !shouldIgnoreFailure() &&
                            !isBenignClose(t) ->
                            failure.compareAndSet(null, t)
                    }
                }
                completed.set(true)
                done.countDown()
            }
        }

        val sseRequest = if (request.header("Accept").isNullOrBlank()) {
            request.newBuilder().header("Accept", "text/event-stream").build()
        } else {
            request
        }
        val eventSource = EventSources
            .createFactory(AgentHttpClient.modelClient)
            .newEventSource(sseRequest, listener)
        eventSourceRef.set(eventSource)
        // finish() 先标记 completed 并唤醒 collect，再 cancel EventSource。
        // 若先 cancel，OkHttp 可能排完当前 body 才返回，追加指令就会等到整段输出结束。
        val binding = runController.register(interruptible = true) {
            stream.finish()
        }
        try {
            runController.throwIfCancelled()
            done.await()
            runController.throwIfCancelled()
            val recordedFailure = failure.get()
            // A received provider rejection is not a benign socket cancellation.
            if (recordedFailure is AgentModelFailure ||
                (!runController.hasPendingSteering && !runController.hasPausedInterrupt)) {
                recordedFailure?.let { throw it }
            }
        } finally {
            completed.set(true)
            binding.close()
            runCatching { eventSource.cancel() }
            reportTimings(final = true)
        }
    }

    internal class SseStream(
        private val completed: AtomicBoolean,
        private val done: CountDownLatch,
        private val cancelSource: () -> Unit,
    ) {
        fun finish() {
            if (!completed.compareAndSet(false, true)) return
            done.countDown()
            runCatching { cancelSource() }
        }
    }

    private fun isBenignClose(error: Throwable): Boolean {
        if (error is AgentRunCancelledException) return true
        val message = error.message.orEmpty()
        if (error is SocketException) return true
        return error is IOException && (
            message.contains("Socket closed", ignoreCase = true) ||
                message.contains("stream was reset", ignoreCase = true) ||
                message.contains("canceled", ignoreCase = true) ||
                message.contains("Cancelled", ignoreCase = true)
            )
    }
}
