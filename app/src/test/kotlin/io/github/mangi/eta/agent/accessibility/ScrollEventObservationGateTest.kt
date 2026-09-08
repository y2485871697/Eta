package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollEventObservationGateTest {
    private var now = 1_000L
    private val gate = ScrollEventObservationGate { now }

    @Test
    fun `event does not enter expensive path without pending scroll`() {
        var invoked = false

        val matched = gate.withMatchingObservation("target.package", 7, eventTimeMillis = now) {
            invoked = true
        }

        assertFalse(matched)
        assertFalse(invoked)
    }

    @Test
    fun `only matching package and window enter expensive path`() {
        gate.begin("target.package", 7, validForMillis = 520L)
        var invocations = 0

        assertFalse(
            gate.withMatchingObservation("other.package", 7, eventTimeMillis = now) {
                invocations++
            },
        )
        assertFalse(
            gate.withMatchingObservation("target.package", 8, eventTimeMillis = now) {
                invocations++
            },
        )
        assertTrue(
            gate.withMatchingObservation("target.package", 7, eventTimeMillis = now) {
                invocations++
            },
        )
        assertEquals(1, invocations)
    }

    @Test
    fun `event created before observation is ignored`() {
        val staleEventTime = now - 1L
        gate.begin("target.package", 7, validForMillis = 520L)

        assertFalse(
            gate.withMatchingObservation("target.package", 7, staleEventTime) {},
        )
    }

    @Test
    fun `expired observation cannot resolve or publish`() {
        val observation = gate.begin("target.package", 7, validForMillis = 520L)

        now += 521L

        assertFalse(gate.isActive(observation))
        assertFalse(gate.withMatchingObservation("target.package", 7, eventTimeMillis = now) {})
    }

    @Test
    fun `replaced observation rejects late work from previous scroll`() {
        val previous = gate.begin("target.package", 7, validForMillis = 520L)
        val current = gate.begin("target.package", 7, validForMillis = 520L)

        assertFalse(gate.isActive(previous))
        assertTrue(gate.isActive(current))

        gate.end(previous)

        assertTrue(gate.isActive(current))
    }

    @Test
    fun `ending current observation closes its event window`() {
        val observation = gate.begin("target.package", 7, validForMillis = 520L)

        gate.end(observation)

        assertFalse(gate.isActive(observation))
        assertFalse(gate.withMatchingObservation("target.package", 7, eventTimeMillis = now) {})
    }

    @Test
    fun `clearing service state invalidates pending observation`() {
        val observation = gate.begin("target.package", 7, validForMillis = 520L)

        gate.clear()

        assertFalse(gate.isActive(observation))
    }
}
