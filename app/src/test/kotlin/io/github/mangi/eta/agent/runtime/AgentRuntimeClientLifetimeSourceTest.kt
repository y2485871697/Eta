package io.github.mangi.eta.agent.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source guards only: these do not exercise Android Binder or prove background survival. */
class AgentRuntimeClientLifetimeSourceTest {
    private val client = listOf(
        File("src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeClient.kt"),
        File("app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeClient.kt"),
    ).first { it.isFile }.readText()
    private val run = blockAfter(client, "isStopRequested: () -> Boolean,")

    @Test
    fun interruptedWaitCancelsOnlyForExplicitStopAndPropagatesWithoutATerminalResult() {
        // Exact control-flow guard: a failed predicate/send must not swallow the interruption.
        assertCodeEquals(
            """
                Thread.currentThread().interrupt()
                runCatching {
                    if (isStopRequested()) {
                        val cancelMessage = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
                        cancelMessage.data = AgentRuntimeWire.ackBundle(request.runId)
                        serviceMessenger.send(cancelMessage)
                    }
                }
                throw interrupted
            """,
            blockAfter(run, "catch (interrupted: InterruptedException)"),
        )
    }

    @Test
    fun preStartStopReturnsBeforeBindingOrPreparingTransfers() {
        assertTrue(
            run.trimStart().startsWith(
                "if (isStopRequested()) return AgentRuntimeWire.RunResult(request.runId, false, \"\", \"已停止\")",
            ),
        )
        assertTrue(client.contains("run(request, onEvent, isStopRequested = { false })"))
    }

    @Test
    fun stopRequestedWhileStartingStillCancelsBeforeWaiting() {
        val send = run.indexOf("serviceMessenger.send(msg)")
        val await = run.indexOf("resultLatch.await()")
        assertTrue(send >= 0 && await > send)
        val afterStart = run.substring(send, await)
        assertCodeEquals(
            """
                val cancel = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
                cancel.data = AgentRuntimeWire.ackBundle(request.runId)
                serviceMessenger.send(cancel)
            """,
            blockAfter(afterStart, "if (isStopRequested())"),
        )
    }

    @Test
    fun explicitCancelApiStillSendsCancelForTheRequestedRun() {
        assertCodeEquals(
            """
                if (runId.isBlank()) return
                withRuntimeMessenger(Unit) { serviceMessenger ->
                    val msg = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
                    msg.data = AgentRuntimeWire.ackBundle(runId)
                    serviceMessenger.send(msg)
                }
            """,
            blockAfter(client, "fun cancelRun(runId: String)"),
        )
    }

    @Test
    fun runFinallyStillReleasesTransfersDeathRecipientAndLease() {
        assertCodeEquals(
            """
                preparedImagesRef.getAndSet(null)?.close()
                preparedHistoryRef.getAndSet(null)?.close()
                runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
                lease.close()
            """,
            blockAfter(run, "finally"),
        )
    }

    @Test
    fun attachInterruptionStillDetachesWithoutCancellingOrCompleting() {
        val attach = blockAfter(client, "fun attachRun(")
        assertCodeEquals(
            """
                Thread.currentThread().interrupt()
                return AttachOutcome.Unavailable
            """,
            blockAfter(attach, "catch (interrupted: InterruptedException)"),
        )
        assertCodeEquals(
            """
                runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
                lease.close()
            """,
            blockAfter(attach, "finally"),
        )
    }

    private fun assertCodeEquals(expected: String, actual: String) {
        fun normalize(code: String) = code.lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
            .replace(Regex("\\s+"), "")
        assertEquals(normalize(expected), normalize(actual))
    }

    // Limited to the balanced blocks above, not a general Kotlin parser.
    private fun blockAfter(source: String, marker: String): String {
        val markerStart = source.indexOf(marker)
        check(markerStart >= 0) { "Missing source marker: $marker" }
        val start = source.indexOf('{', markerStart + marker.length)
        check(start >= 0) { "Missing block after: $marker" }
        var depth = 0
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start + 1, index)
            }
        }
        error("Unclosed block after: $marker")
    }
}
