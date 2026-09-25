package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
