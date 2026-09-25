package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeSessionCompactionRaceTest {
    @Test fun ordinaryCancelWaitsOutsideLockAndReturnsAfterResult() =
        assertSynchronousTerminal(complete = false)

    @Test fun ordinaryCompleteWaitsOutsideLockAndReturnsAfterPersistenceAndResult() =
        assertSynchronousTerminal(complete = true)

    /**
     * No sleeps or assertions hidden inside isolated event callbacks. The terminal
     * thread announces itself before entering cancel/complete, and the observer
     * waits until steering rejection proves that it has claimed COMMITTING.
     */
    private fun assertSynchronousTerminal(complete: Boolean) {
        val childEntered = CountDownLatch(1)
        val terminalStarted = CountDownLatch(1)
        val observedClaim = CountDownLatch(1)
        val releaseChild = CountDownLatch(1)
        val terminalReturned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val childAccepted = AtomicReference<Boolean?>(null)
        val terminalAccepted = AtomicReference<Boolean?>(null)
        val observerSawTerminal = AtomicReference<Boolean?>(null)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val session = AgentRuntimeSession("sync-race", resultSink = { order += "result" })
        session.childCompactor = { _, _, _ ->
            order += "child-enter"
            childEntered.countDown()
            check(releaseChild.await(4, TimeUnit.SECONDS)) { "child was not released" }
            order += "child-return"
            true
        }
        fun worker(name: String, action: () -> Unit) = Thread {
            try {
                action()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }.apply { isDaemon = true; this.name = name }
        val child = worker("sync-child") {
            childAccepted.set(session.requestCompact(childTaskId = "child"))
        }
        val terminal = worker("sync-terminal") {
            terminalStarted.countDown()
            terminalAccepted.set(if (complete) {
                session.complete(AgentRuntimeWire.RunResult("sync-race", true, "done")) {
                    order += "persist"
                }
            } else session.cancel("replaced"))
            order += "terminal-return"
            terminalReturned.countDown()
        }
        val observer = worker("sync-claim-observer") {
            check(terminalStarted.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            // Main compaction has no external callbacks and exposes admission closure.
            while (session.requestCompact()) {
                check(System.nanoTime() < deadline) { "terminal never closed admission" }
                Thread.yield()
            }
            observerSawTerminal.set(session.isTerminal)
            observedClaim.countDown()
        }

        child.start()
        try {
            assertTrue(childEntered.await(2, TimeUnit.SECONDS))
            terminal.start()
            observer.start()
            assertTrue("Terminal waiter held the session lock", observedClaim.await(3, TimeUnit.SECONDS))
            assertEquals(false, observerSawTerminal.get())
            assertEquals(1L, terminalReturned.count)
            assertEquals(listOf("child-enter"), order.toList())
            assertFalse(session.requestCompact(childTaskId = "rejected-after-claim"))
        } finally {
            releaseChild.countDown()
            child.join(3_000)
            terminal.join(3_000)
            observer.join(3_000)
        }

        assertFalse(child.isAlive)
        assertFalse(terminal.isAlive)
        assertFalse(observer.isAlive)
        assertNull(failure.get())
        assertEquals(true, childAccepted.get())
        assertEquals(true, terminalAccepted.get())
        assertEquals(0L, terminalReturned.count)
        assertTrue(session.isTerminal)
        assertEquals(
            if (complete) listOf("child-enter", "child-return", "persist", "result", "terminal-return")
            else listOf("child-enter", "child-return", "result", "terminal-return"),
            order,
        )
    }
}
