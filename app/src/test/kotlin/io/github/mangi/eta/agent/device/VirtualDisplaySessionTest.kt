package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplaySessionTest {
    @Test
    fun sessionOperationsFailClosedWithoutReportingSuccess() {
        val packageName = "com.example.shop"
        listOf(
            { VirtualDisplaySession.onRunStarted() },
            { VirtualDisplaySession.onRunFinished() },
            { VirtualDisplaySession.engage() },
            { VirtualDisplaySession.keep(packageName) },
        ).forEach { call ->
            val error = assertThrows(VirtualDisplayHandoffNotReadyException::class.java, call)
            assertEquals(VirtualDisplaySession.NOT_READY, error.message)
            assertTrue(error is IllegalStateException)
            assertFalse(error.message.orEmpty().contains(packageName))
        }
    }
}
