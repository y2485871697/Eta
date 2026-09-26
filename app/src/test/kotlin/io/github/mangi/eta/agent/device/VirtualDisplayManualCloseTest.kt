package io.github.mangi.eta.agent.device

import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayManualCloseTest {
    private fun evidence() = VirtualDisplayManualClose.Evidence(
        VirtualDisplayManualClose.Key(7, "virtual:test", "boot", 42, "socket"),
        "boot", true, false, "empty", 0, true, 0, false, false, false, false, false)
    private inner class Fake : VirtualDisplayManualClose.Backend {
        var state = evidence()
        var marks = 0; var releases = 0; var clears = 0
        var writable = true; var gone = true; var releaseThrows = false
        override fun evidence() = state
        override fun markAttempt(): Boolean { marks++; if (writable) state = state.copy(journalBlocked = true); return writable }
        override fun release(): Boolean { releases++; if (releaseThrows) error("lost reply"); return true }
        override fun confirmGone() = gone
        override fun clearConfirmed(): Boolean { clears++; return true }
    }
    @Test fun prepareReadsOnlyThenClosesOnceAfterConfirmation() {
        val b = Fake(); val c = VirtualDisplayManualClose(); val p = c.prepare(b)
        assertEquals("prepared", p.outcome); assertEquals(0, b.marks); assertEquals(0, b.releases)
        assertEquals("closed_confirmed", c.close(p.nonce!!, b).outcome)
        assertEquals(1, b.releases); assertEquals(1, b.clears)
        assertEquals("blocked", c.close(p.nonce, b).outcome); assertEquals(1, b.releases)
    }
    @Test fun unsafeEvidenceNeverAuthorizesRelease() {
        val base = evidence()
        val bad = listOf(base.copy(activeAgent=true), base.copy(authenticated=false),
            base.copy(taskCount=1,sourceEmpty=false,sourceState="occupied"),
            base.copy(sourceState=null),base.copy(sourceEmpty=null),base.copy(retainedCount=null),
            base.copy(mutationUncertain=true),base.copy(releaseAttempted=true),
            base.copy(journalBlocked=true),base.copy(currentBoot="other"),
            base.copy(finishing=true),base.copy(retainedCount=1))
        bad.forEach { val b=Fake(); b.state=it; assertEquals("blocked",VirtualDisplayManualClose().prepare(b).outcome); assertEquals(0,b.releases) }
    }
    @Test fun identityAndStateAreRecheckedAtCommit() {
        val b=Fake();val c=VirtualDisplayManualClose();val p=c.prepare(b)
        b.state=b.state.copy(key=b.state.key.copy(uniqueId="replacement"))
        assertEquals("CLOSE_STATE_CHANGED",c.close(p.nonce!!,b).reason);assertEquals(0,b.releases)
    }
    @Test fun nonceExpiresAndCannotBeReused() {
        var now=0L;val b=Fake();val c=VirtualDisplayManualClose(clock={now});val p=c.prepare(b)
        now=VirtualDisplayManualClose.TTL_MS
        assertEquals("CLOSE_NONCE_EXPIRED",c.close(p.nonce!!,b).reason)
        assertEquals("CLOSE_NONCE_INVALID",c.close(p.nonce,b).reason);assertEquals(0,b.releases)
    }
    @Test fun unwritableBarrierPreventsRelease() {
        val b=Fake();val c=VirtualDisplayManualClose();val p=c.prepare(b);b.writable=false
        assertEquals("RECOVERY_STATE_UNWRITABLE",c.close(p.nonce!!,b).reason);assertEquals(0,b.releases)
    }
    @Test fun uncertainResultRetainsBarrierAndCannotBeReprepared() {
        for (exception in listOf(false,true)) {
            val b=Fake();val c=VirtualDisplayManualClose();val p=c.prepare(b);b.gone=false;b.releaseThrows=exception
            assertEquals("closed_unconfirmed",c.close(p.nonce!!,b).outcome)
            assertEquals("MUTATION_UNCERTAIN_NO_REPLAY",c.prepare(b).reason)
            assertEquals(0,b.clears);assertEquals(1,b.releases)
        }
    }
    @Test fun completedHandoffMayReleaseEmptySource() {
        val b=Fake();b.state=b.state.copy(finishing=true,handoffComplete=true,retainedCount=2)
        val c=VirtualDisplayManualClose();val p=c.prepare(b)
        assertEquals("closed_confirmed",c.close(p.nonce!!,b).outcome)
    }
    @Test fun resultDiagnosticDoesNotLeakNonce() {
        assertFalse(VirtualDisplayManualClose.Result("prepared",nonce="secret").toString().contains("secret"))
    }
}
