package io.github.mangi.eta.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogThrottleTest {

    @Test
    fun shouldLog_acceptsFirstCallAndWindowBoundary() {
        var now = 1_000L
        val throttle = LogThrottle { now }

        assertTrue(throttle.shouldLog("event", 100L))
        now = 1_099L
        assertFalse(throttle.shouldLog("event", 100L))
        now = 1_100L
        assertTrue(throttle.shouldLog("event", 100L))
    }

    @Test
    fun shouldLog_keepsKeysIndependentAndRecoversAfterClockReset() {
        var now = 5_000L
        val throttle = LogThrottle { now }

        assertTrue(throttle.shouldLog("first", 1_000L))
        assertTrue(throttle.shouldLog("second", 1_000L))
        now = 10L
        assertTrue(throttle.shouldLog("first", 1_000L))
    }

    @Test
    fun shouldLog_acceptsOnlyOneConcurrentCallForTheSameKey() {
        val workerCount = 12
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(workerCount)
        val accepted = AtomicInteger()
        val throttle = LogThrottle { 1_000L }

        repeat(workerCount) {
            Thread {
                ready.countDown()
                start.await()
                if (throttle.shouldLog("shared", 100L)) {
                    accepted.incrementAndGet()
                }
                finished.countDown()
            }.start()
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, accepted.get())
    }
}
