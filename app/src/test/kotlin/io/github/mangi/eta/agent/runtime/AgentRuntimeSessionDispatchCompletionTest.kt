package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeSessionDispatchCompletionTest {
    @Test fun acceptedNestedEventDrainsBeforePersistenceAndResult() {
        val first = mutableListOf<AgentEvent>()
        val second = mutableListOf<AgentEvent>()
        val order = mutableListOf<String>()
        val initial = AgentEvent.RoundStarted(1, 1)
        val nested = AgentEvent.UserSupplementReceived(1, "nested before complete")
        var returned: AgentEvent? = null
        var completed: Boolean? = null
        var eventsAfterTerminal = 0
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession("complete-broadcast", eventSink = { event ->
            if (session.isTerminal) eventsAfterTerminal++
            first += event
            if (event == initial) {
                returned = session.steer("nested before complete") { nested }
                completed = session.complete(AgentRuntimeWire.RunResult("complete-broadcast", true, "done")) {
                    order += "persist"
                }
            }
        }, resultSink = { order += "result-1" })
        assertTrue(session.attach({ event ->
            if (session.isTerminal) eventsAfterTerminal++
            second += event
            order += if (event == initial) "initial-2" else "nested-2"
        }, { order += "result-2" }))

        assertTrue(session.emit(initial))

        assertEquals(nested, returned)
        assertEquals(true, completed)
        assertEquals(listOf(initial, nested), first)
        assertEquals(first, second)
        assertEquals(listOf("initial-2", "nested-2", "persist", "result-1", "result-2"), order)
        assertEquals(0, eventsAfterTerminal)
        assertTrue(session.isTerminal)
        assertFalse(session.emit(initial))
    }

    @Test fun acceptedReplaySteeringSurvivesCompletionClaimUntilBoundaryAndResult() {
        val existing = mutableListOf<AgentEvent>()
        val attaching = mutableListOf<AgentEvent>()
        val order = mutableListOf<String>()
        val initial = AgentEvent.RoundStarted(1, 1)
        val nested = AgentEvent.UserSupplementReceived(1, "replay before complete")
        val session = AgentRuntimeSession("complete-replay", eventSink = existing::add)
        assertTrue(session.emit(initial))
        var returned: AgentEvent? = null
        var completed: Boolean? = null
        var eventsAfterTerminal = 0

        assertTrue(session.attach({ event ->
            if (session.isTerminal) eventsAfterTerminal++
            attaching += event
            order += if (event == initial) "replay" else "live"
            if (event == initial) {
                returned = session.steer("replay before complete") { nested }
                completed = session.complete(AgentRuntimeWire.RunResult("complete-replay", true, "done")) {
                    order += "persist"
                }
            }
        }, { order += "result" }, { order += "boundary" }))

        assertEquals(nested, returned)
        assertEquals(true, completed)
        assertEquals(listOf(initial, nested), existing)
        assertEquals(existing, attaching)
        assertEquals(listOf("replay", "boundary", "live", "persist", "result"), order)
        assertEquals(0, eventsAfterTerminal)
        assertTrue(session.isTerminal)
    }

    @Test fun acceptedEventsDrainThroughStopBeforeCleanupAndStoppedCompletion() {
        val first = mutableListOf<AgentEvent>()
        val second = mutableListOf<AgentEvent>()
        val order = mutableListOf<String>()
        val initial = AgentEvent.RoundStarted(1, 1)
        val nested = AgentEvent.UserSupplementReceived(1, "before stop")
        var nestedAccepted: Boolean? = null
        var stopped: Boolean? = null
        var persisted: AgentRuntimeWire.RunResult? = null
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession("stop-drain", eventSink = { event ->
            first += event
            if (event == initial) {
                nestedAccepted = session.emit(nested)
                stopped = session.requestStop()
            }
        }, resultSink = { results += it; order += "result" })
        assertTrue(session.attach({ event ->
            second += event
            order += if (event == initial) "initial" else "nested"
        }, {}))
        session.controller.register { order += "cleanup" }

        assertTrue(session.emit(initial))

        assertEquals(true, nestedAccepted)
        assertEquals(true, stopped)
        assertEquals(listOf(initial, nested), first)
        assertEquals(first, second)
        assertEquals(listOf("initial", "nested", "cleanup"), order)
        assertTrue(results.isEmpty())
        assertFalse(session.isTerminal)
        assertFalse(session.emit(initial))
        assertFalse(session.requestStop())
        assertTrue(session.complete(AgentRuntimeWire.RunResult("stop-drain", true, "done")) {
            persisted = it
            order += "persist"
        })
        assertEquals(listOf("initial", "nested", "cleanup", "persist", "result"), order)
        assertEquals(persisted, results.single())
        assertFalse(results.single().ok)
        assertEquals("已停止", results.single().error)
        assertFalse(session.complete(AgentRuntimeWire.RunResult("stop-drain", true, "late")))
    }

    @Test fun liveCancelCleansUpAndPublishesOnlyAfterOuterCallbackUnwinds() =
        assertCallbacksOutsideLock(replay = false, complete = false)

    @Test fun replayCancelCleansUpAndPublishesOnlyAfterOuterCallbackUnwinds() =
        assertCallbacksOutsideLock(replay = true, complete = false)

    @Test fun liveCompletePersistsAndCleansUpOnlyAfterOuterCallbackUnwinds() =
        assertCallbacksOutsideLock(replay = false, complete = true)

    @Test fun replayCompletePersistsAndCleansUpOnlyAfterOuterCallbackUnwinds() =
        assertCallbacksOutsideLock(replay = true, complete = true)

    private fun assertCallbacksOutsideLock(replay: Boolean, complete: Boolean) {
        val order = mutableListOf<String>()
        val lockProbes = mutableListOf<Boolean>()
        val readerFailure = AtomicReference<Throwable?>(null)
        val readers = mutableListOf<Thread>()
        val results = mutableListOf<AgentRuntimeWire.RunResult>()
        val session = AgentRuntimeSession("unwind")
        var accepted: Boolean? = null
        fun probeLock() {
            val readFinished = CountDownLatch(1)
            readers += Thread {
                try {
                    session.isTerminal
                } catch (failure: Throwable) {
                    readerFailure.compareAndSet(null, failure)
                } finally {
                    readFinished.countDown()
                }
            }.apply { isDaemon = true; name = "terminal-callback-lock-probe"; start() }
            lockProbes += readFinished.await(2, TimeUnit.SECONDS)
        }
        val onEvent: (AgentEvent) -> Unit = {
            order += "callback-enter"
            accepted = if (complete) {
                session.complete(AgentRuntimeWire.RunResult("unwind", true, "done")) {
                    probeLock()
                    order += "persist"
                }
            } else session.cancel("replaced")
            order += "callback-return"
        }
        val onResult: (AgentRuntimeWire.RunResult) -> Unit = {
            probeLock()
            results += it
            order += "result"
        }
        session.controller.register {
            probeLock()
            order += "cleanup"
        }
        val event = AgentEvent.RoundStarted(1, 1)

        try {
            if (replay) {
                assertTrue(session.emit(event))
                // Cancellation interrupts attach, but its provisional subscriber
                // still receives the single terminal result captured at cancellation.
                assertEquals(complete, session.attach(onEvent, onResult) { order += "boundary" })
            } else {
                assertTrue(session.attach(onEvent, onResult))
                assertTrue(session.emit(event))
            }
        } finally {
            readers.forEach { it.join(3_000) }
        }

        assertTrue(readers.none { it.isAlive })
        assertNull(readerFailure.get())
        assertEquals(List(if (complete) 3 else 2) { true }, lockProbes)
        assertEquals(true, accepted)
        val expected = mutableListOf("callback-enter", "callback-return")
        if (replay && complete) expected += "boundary"
        if (complete) expected += "persist"
        expected += listOf("cleanup", "result")
        assertEquals(expected, order)
        assertEquals(complete, results.single().ok)
        assertTrue(session.isTerminal)
        assertFalse(session.cancel("late"))
        assertFalse(session.complete(AgentRuntimeWire.RunResult("unwind", true, "late")))
        assertEquals(expected, order)
    }
}
