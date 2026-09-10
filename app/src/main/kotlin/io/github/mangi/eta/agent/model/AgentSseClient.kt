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
    ) {
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

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                opened.set(true)
                try {
                    emitOpen(response.code)
                    if (!response.isSuccessful) {
                        failure.compareAndSet(
                            null,
                            AgentModelFailure.http(
                                response.code,
                                runCatching { response.body?.string() }.getOrNull().orEmpty(),
                            ),
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
                if (completed.get()) return
                try {
                    runController.throwIfCancelled()
                    emitEvent(stream, id, type, data)
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
                        response != null && !response.isSuccessful -> {
                            if (!opened.get()) {
                                runCatching { emitOpen(response.code) }
                            }
                            failure.compareAndSet(
                                null,
                                AgentModelFailure.http(
                                    response.code,
                                    runCatching { response.body?.string() }.getOrNull().orEmpty(),
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
        val binding = runController.register(eventSource::cancel)
        try {
            runController.throwIfCancelled()
            done.await()
            runController.throwIfCancelled()
            failure.get()?.let { throw it }
        } finally {
            completed.set(true)
            binding.close()
            runCatching { eventSource.cancel() }
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
