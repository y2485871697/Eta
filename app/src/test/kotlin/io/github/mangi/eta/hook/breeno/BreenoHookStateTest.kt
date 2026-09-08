package io.github.mangi.eta.hook.breeno

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreenoHookStateTest {
    @Test
    fun pendingAcksRemainBoundedAndRequestRescanOnOverflow() {
        val state = PendingAckState(capacity = 2, rescanAttempts = 2)

        assertEquals(PendingAckState.EnqueueResult.ADDED, state.enqueue("run-1"))
        assertEquals(PendingAckState.EnqueueResult.DUPLICATE, state.enqueue("run-1"))
        assertEquals(PendingAckState.EnqueueResult.ADDED, state.enqueue("run-2"))
        assertEquals(PendingAckState.EnqueueResult.OVERFLOW, state.enqueue("run-3"))
        assertEquals(2, state.pendingCount())

        assertEquals("run-1", state.poll())
        assertEquals("run-2", state.poll())
        assertTrue(state.takeRescanRequest())
        assertTrue(state.takeRescanRequest())
        assertFalse(state.takeRescanRequest())
        assertFalse(state.hasWork())
    }

    @Test
    fun handledRunIdsUseABoundedAccessOrderedWindow() {
        val runIds = BoundedRunIdSet(capacity = 2)

        assertTrue(runIds.add("run-1"))
        assertTrue(runIds.add("run-2"))
        assertFalse(runIds.add("run-1"))
        assertTrue(runIds.add("run-3"))

        assertTrue(runIds.contains("run-1"))
        assertFalse(runIds.contains("run-2"))
        assertTrue(runIds.contains("run-3"))
        assertEquals(2, runIds.size())
    }

    @Test
    fun handledRunIdsExpireAfterTheirDeliveryWindow() {
        var now = 1_000L
        val runIds = BoundedRunIdSet(
            capacity = 2,
            ttlMillis = 100L,
            nowMillis = { now },
        )

        assertTrue(runIds.add("run-1"))
        now = 1_099L
        assertTrue(runIds.contains("run-1"))
        now = 1_100L
        assertFalse(runIds.contains("run-1"))
        assertEquals(0, runIds.size())
    }

    @Test
    fun requestDedupKeyIsFixedLengthAndDoesNotRetainPromptText() {
        val prompt = "请总结这段包含敏感信息的请求"

        val key = breenoRequestDedupKey("room-42", prompt)

        assertEquals(64, key.length)
        assertFalse(key.contains(prompt))
        assertEquals(key, breenoRequestDedupKey("room-42", prompt))
        assertNotEquals(key, breenoRequestDedupKey("room-42", "$prompt!"))
        assertNotEquals(
            breenoRequestDedupKey("ab", "c"),
            breenoRequestDedupKey("a", "bc"),
        )
    }

    @Test
    fun retryBudgetStopsAndCanBeResetByANewDeliveryEvent() {
        val budget = BoundedRetryBudget(maxAttempts = 2)

        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())

        budget.reset()

        assertTrue(budget.tryAcquire())
    }

    @Test
    fun admittedTaskDoesNotRunBeforeActiveStateCanBePublished() {
        val gate = TaskAdmissionGate()
        val workerStarted = CountDownLatch(1)
        val admitted = AtomicReference<Boolean?>()
        val worker = Thread {
            workerStarted.countDown()
            admitted.set(gate.awaitAdmission())
        }.apply {
            isDaemon = true
            start()
        }

        assertTrue(workerStarted.await(1, TimeUnit.SECONDS))
        assertNull(admitted.get())

        gate.admit()
        worker.join(1_000L)

        assertFalse(worker.isAlive)
        assertEquals(true, admitted.get())
    }

    @Test
    fun cancelledAdmissionReleasesWaitingTaskWithoutRunningIt() {
        val gate = TaskAdmissionGate()
        val workerStarted = CountDownLatch(1)
        val admitted = AtomicReference<Boolean?>()
        val worker = Thread {
            workerStarted.countDown()
            admitted.set(gate.awaitAdmission())
        }.apply {
            isDaemon = true
            start()
        }

        assertTrue(workerStarted.await(1, TimeUnit.SECONDS))
        gate.cancel()
        worker.join(1_000L)

        assertFalse(worker.isAlive)
        assertEquals(false, admitted.get())
    }
}
