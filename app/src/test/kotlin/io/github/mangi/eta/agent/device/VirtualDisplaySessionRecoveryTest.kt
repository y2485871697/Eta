package io.github.mangi.eta.agent.device

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Metadata-only fixtures: no live owner, root process, socket, or virtual display. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class VirtualDisplaySessionRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("virtual_display_owner_recovery", Context.MODE_PRIVATE)

    @Suppress("UNCHECKED_CAST")
    private fun sessions(): MutableMap<String, Any> = VirtualDisplaySession::class.java
        .getDeclaredField("sessions").apply { isAccessible = true }
        .get(VirtualDisplaySession) as MutableMap<String, Any>

    private fun fixture(run: String, phase: String, closed: Boolean): Any {
        val type = Class.forName("io.github.mangi.eta.agent.device.VirtualDisplaySession\$Session")
        val session = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        type.getDeclaredField("phase").apply { isAccessible = true }.set(session, phase)
        type.getDeclaredField("closedRun").apply { isAccessible = true }.setBoolean(session, closed)
        sessions()[run] = session
        return session
    }

    @Before fun before() { reset() }
    @After fun after() { reset() }
    private fun reset() {
        sessions().clear()
        prefs.edit().clear().commit()
        VirtualDisplaySession::class.java.getDeclaredField("recoveryContext")
            .apply { isAccessible = true }.set(VirtualDisplaySession, null)
    }

    @Test fun emptyMetadataDoesNotCreateAnOwner() {
        assertFalse(VirtualDisplaySession.recoveryStatus(context).getBoolean("present"))
        val start = VirtualDisplaySession.start(context, "manual-test", createIfMissing = false)
        assertEquals("NO_VIRTUAL_SESSION", start.getString("error"))
        assertEquals("NO_VIRTUAL_SESSION",
            VirtualDisplaySession.recoverAndFinishManually(context).getString("error"))
        assertTrue(sessions().isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun statusReportsAppRecordWithoutExposingCapabilities() {
        prefs.edit().putInt("display", 2).putString("token", "private-test-token")
            .putString("socket", "private-test-socket").putLong("pid", 7654321).commit()
        val status = VirtualDisplaySession.recoveryStatus(context)
        assertTrue(status.getBoolean("present"))
        assertEquals(2, status.getInt("displayId"))
        assertEquals("eta_owner", status.getString("manager"))
        assertEquals("recovery_pending", status.getString("phase"))
        assertTrue(status.getBoolean("recoverable"))
        for (secret in listOf("private-test-token", "private-test-socket", "7654321"))
            assertFalse(status.toString().contains(secret))
    }

    @Test fun ongoingRunCannotBeManuallyTakenOver() {
        fixture("ongoing", "active", false)
        assertTrue(VirtualDisplaySession.recoveryStatus(context).getBoolean("busy"))
        assertEquals("VIRTUAL_SESSION_BUSY",
            VirtualDisplaySession.recoverAndFinishManually(context).getString("error"))
        assertEquals(setOf("ongoing"), sessions().keys)
    }

    @Test fun multiplePendingSessionsAreNotSilentlySelected() {
        fixture("one", "held", true)
        fixture("two", "held", true)
        assertFalse(VirtualDisplaySession.recoveryStatus(context).getBoolean("recoverable"))
        assertEquals("VIRTUAL_SESSION_BUSY",
            VirtualDisplaySession.recoverAndFinishManually(context).getString("error"))
        assertEquals(2, sessions().size)
    }

    @Test fun cancelledRunCanRecoverCleanupButNotGuiAccess() {
        val session = fixture("cancelled", "held", true)
        val start = VirtualDisplaySession.start(context, "cancelled", createIfMissing = false)
        assertTrue(start.getBoolean("ok"))
        assertTrue(start.getBoolean("cleanup_only"))
        assertEquals("held", start.getString("phase"))
        assertTrue(session.javaClass.getDeclaredField("cleanupOnly")
            .apply { isAccessible = true }.getBoolean(session))
        assertSame(session, sessions()["cancelled"])
    }

    @Test fun failedManualReconnectRemainsRetryableAndPreservesRecord() {
        // Missing socket/pid rejects before LocalSocket construction, on any host.
        prefs.edit().putInt("display", 2).putString("token", "private-test-token").commit()
        repeat(2) {
            val result = VirtualDisplaySession.recoverAndFinishManually(context)
            assertFalse(result.getBoolean("ok"))
            assertNotEquals("VIRTUAL_SESSION_BUSY", result.getString("error"))
            val status = VirtualDisplaySession.recoveryStatus(context)
            assertFalse(status.getBoolean("busy"))
            assertTrue(status.getBoolean("recoverable"))
            assertEquals("private-test-token", prefs.getString("token", null))
            assertEquals(1, sessions().size)
        }
    }

    @Test fun malformedRecordIsNotReportedAsAnEmptyScreen() {
        prefs.edit().putString("display", "invalid").commit()
        val status = VirtualDisplaySession.recoveryStatus(context)
        assertFalse(status.getBoolean("ok"))
        assertEquals("RECOVERY_STATE_UNREADABLE", status.getString("error"))
        assertFalse(status.has("present"))
    }
}
