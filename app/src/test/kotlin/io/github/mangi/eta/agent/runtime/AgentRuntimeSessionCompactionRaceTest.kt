package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
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

    @Test fun recursiveChildCompactionRejectsWithoutDispatchOrMainFallback() {
        val session = AgentRuntimeSession("recursive-child")
        val targets = mutableListOf<String>()
        var nestedAccepted: Boolean? = null
        session.childCompactor = { id, _, _ ->
            targets += id
            if (id == "outer") {
                nestedAccepted = session.requestCompact(childTaskId = "nested")
            }
            true
        }

        assertTrue(session.requestCompact(childTaskId = "outer"))
        assertEquals(false, nestedAccepted)
        assertEquals(listOf("outer"), targets)
        assertFalse(session.controller.hasPendingCompact)
        // The guard must clear on unwind, not reject a later ordinary request.
        assertTrue(session.requestCompact(childTaskId = "later"))
        assertEquals(listOf("outer", "later"), targets)
        assertTrue(session.cancel("finished"))
        assertEquals(listOf("outer", "later"), targets)
    }

    @Test fun callbackCancelDoesNotWaitForChildWhileHoldingCoordinatorLock() =
        assertCoordinatorCallbackDoesNotWait(complete = false)

    @Test fun callbackCompleteDoesNotWaitForChildWhileHoldingCoordinatorLock() =
        assertCoordinatorCallbackDoesNotWait(complete = true)

    private fun assertCoordinatorCallbackDoesNotWait(complete: Boolean) {
        val coordinator = ReentrantLock()
        val childEntered = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val childAccepted = AtomicReference<Boolean?>(null)
        val terminalAccepted = AtomicReference<Boolean?>(null)
        val emitted = AtomicReference<Boolean?>(null)
        val terminalInCallback = AtomicReference<Boolean?>(null)
        val order = Collections.synchronizedList(mutableListOf<String>())
        lateinit var session: AgentRuntimeSession
        session = AgentRuntimeSession("coordinator-race", eventSink = {
            terminalAccepted.set(if (complete) {
                session.complete(AgentRuntimeWire.RunResult("coordinator-race", true, "done")) {
                    order += "persist"
                }
            } else session.cancel("replaced"))
            terminalInCallback.set(session.isTerminal)
            order += "claim"
        }, resultSink = { order += "result" })
        session.childCompactor = { _, _, _ ->
            childEntered.countDown()
            // Bound the failure path: waiting in the terminal callback would retain
            // this coordinator lock and prevent the admitted child from finishing.
            check(coordinator.tryLock(4, TimeUnit.SECONDS)) { "terminal callback retained coordinator lock" }
            try {
                order += "child"
                true
            } finally {
                coordinator.unlock()
            }
        }
        fun worker(name: String, action: () -> Unit) = Thread {
            try {
                action()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }.apply { isDaemon = true; this.name = name }
        val child = worker("coordinator-child") {
            childAccepted.set(session.requestCompact(childTaskId = "child"))
        }
        val callback = worker("coordinator-callback") {
            coordinator.lock()
            try {
                child.start()
                check(childEntered.await(2, TimeUnit.SECONDS))
                emitted.set(session.emit(AgentEvent.RoundStarted(1, 1)))
                order += "callback-return"
                callbackReturned.countDown()
            } finally {
                coordinator.unlock()
            }
        }

        callback.start()
        try {
            assertTrue("Callback waited for a child needing its coordinator lock",
                callbackReturned.await(3, TimeUnit.SECONDS))
        } finally {
            callback.join(5_000)
            child.join(5_000)
        }

        assertFalse(callback.isAlive)
        assertFalse(child.isAlive)
        assertNull(failure.get())
        assertEquals(true, childAccepted.get())
        assertEquals(true, terminalAccepted.get())
        assertEquals(true, emitted.get())
        assertEquals(false, terminalInCallback.get())
        assertEquals(
            if (complete) listOf("claim", "callback-return", "child", "persist", "result")
            else listOf("claim", "callback-return", "child", "result"),
            order,
        )
        assertTrue(session.isTerminal)
        assertFalse(session.cancel("loser"))
        assertFalse(session.complete(AgentRuntimeWire.RunResult("coordinator-race", true, "late")))
        assertFalse(session.requestCompact(childTaskId = "late"))
        assertFalse(session.controller.hasPendingCompact)
    }

    /**
     * No sleeps or assertions hidden inside isolated event callbacks. The terminal
     * thread announces itself before entering cancel/complete, and the observer
     * waits until main-compaction rejection proves that it has claimed COMMITTING.
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
