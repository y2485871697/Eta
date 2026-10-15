package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeResultWorkerTest {
    @Test fun blockedDrainDoesNotBlockCallerAndAckKeepsItsOrder() {
        val executor = Executors.newSingleThreadExecutor()
        val failures = Collections.synchronizedList(mutableListOf<Exception>())
        val worker = AgentRuntimeResultWorker(executor) { failures.add(it) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acked = CountDownLatch(1)
        val actions = Collections.synchronizedList(mutableListOf<String>())
        val workerThread = AtomicReference<Thread>()
        try {
            assertTrue(worker.submit {
                workerThread.set(Thread.currentThread())
                entered.countDown()
                release.await()
                actions.add("drain")
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNotEquals(Thread.currentThread(), workerThread.get())
            assertTrue(worker.submit { actions.add("ack"); acked.countDown() })
            assertEquals(1L, release.count)
            assertEquals(1L, acked.count)
            release.countDown()
            assertTrue(acked.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("drain", "ack"), actions)
            assertTrue(failures.isEmpty())
        } finally {
            release.countDown()
            worker.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun closeDrainsAcceptedAckAndRejectsNewWork() {
        val executor = Executors.newSingleThreadExecutor()
        val worker = AgentRuntimeResultWorker(executor) { }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acked = CountDownLatch(1)
        try {
            worker.submit { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.submit { acked.countDown() }
            worker.close()
            assertFalse(worker.submit { error("Closed worker accepted work") })
            assertEquals(1L, acked.count)
            release.countDown()
            assertTrue(acked.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            worker.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun failedDrainIsReportedAndDoesNotLoseTheNextAck() {
        val executor = Executors.newSingleThreadExecutor()
        val failure = IllegalStateException("database unavailable")
        val reported = AtomicReference<Exception>()
        val acked = CountDownLatch(1)
        val worker = AgentRuntimeResultWorker(executor) { reported.set(it) }
        try {
            worker.submit { throw failure }
            worker.submit { acked.countDown() }
            assertTrue(acked.await(2, TimeUnit.SECONDS))
            assertEquals(failure, reported.get())
        } finally {
            worker.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
