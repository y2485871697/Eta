package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ProviderEventDeliveryGateTest {
    @Test(timeout = 20000)
    fun closeWaitsForInFlightDeliveryThenDropsLaterEvents() {
        val gate = ProviderEventDeliveryGate()
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val closed = AtomicBoolean()
        val worker = thread(name = "gate-in-flight", isDaemon = true) {
            try {
                gate.deliver {
                    deliveries.incrementAndGet()
                    inFlight.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            } catch (t: Throwable) { failure.set(t) }
        }
        var closer: Thread? = null
        try {
            assertTrue(inFlight.await(5, TimeUnit.SECONDS))
            val closing = thread(name = "gate-close", isDaemon = true) {
                gate.close()
                closed.set(true)
            }
            closer = closing
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closing.isAlive && closing.state != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield()
            }
            // close's lock acquisition is the only blocking operation in this thread.
            assertEquals(Thread.State.WAITING, closing.state)
            assertFalse(closed.get())
            assertEquals(1, deliveries.get())
        } finally {
            release.countDown()
            worker.join(5000)
            closer?.join(5000)
        }
        assertFalse(worker.isAlive)
        assertFalse(closer?.isAlive ?: false)
        assertNull(failure.get())
        assertTrue(closed.get())
        assertTrue(gate.isClosed)
        gate.deliver { deliveries.incrementAndGet() }
        assertEquals(1, deliveries.get())
    }

    @Test fun deliversInOrderUntilClosed() {
        val gate = ProviderEventDeliveryGate()
        val order = mutableListOf<Int>()
        gate.deliver { order += 1 }
        gate.deliver { order += 2 }
        gate.close()
        gate.deliver { order += 3 }
        assertEquals(listOf(1, 2), order)
    }
}
