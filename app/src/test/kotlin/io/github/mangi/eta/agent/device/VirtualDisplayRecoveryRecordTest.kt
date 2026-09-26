package io.github.mangi.eta.agent.device
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayRecoveryRecordTest {
    private fun fields(): Map<String,Any> = mapOf(
        "socket" to ("eta.vd.owner."+"a".repeat(32)),"pid" to 42L,"display" to 7,
        "unique" to "virtual:test","token" to "private-credential","run" to "test",
        "boot" to "3a5ee0c0-1d82-4e80-95a6-99bb927d7393")
    @Test fun acceptsExistingTypedPreferenceRecordWithoutKept() {
        val r=VirtualDisplayRecoveryRecord.decode(fields());assertEquals(42L,r.pid);assertNull(r.kept);assertNull(r.mutationBarrier)
        assertFalse(r.toString().contains("private-credential"))
    }
    @Test fun rejectsWrongPreferenceTypeWithoutExposingValue() {
        val e=runCatching { VirtualDisplayRecoveryRecord.decode(fields()+mapOf("pid" to "private-credential")) }.exceptionOrNull() as VirtualDisplayRecoveryException
        assertEquals("record_decode",e.stage);assertEquals("pid",e.field);assertFalse(e.toString().contains("private-credential"))
    }
    @Test fun acceptsSocketFormatAndRejectsMalformedIdentity() {
        assertNotNull(VirtualDisplayRecoveryRecord.decode(fields()))
        assertTrue(runCatching { VirtualDisplayRecoveryRecord.decode(fields()+mapOf("socket" to "etaXvdXownerX"+"a".repeat(32))) }.isFailure)
    }
    @Test fun bootChangeRequiresTwoValidUuids() {
        val a=fields()["boot"] as String;val b="f7f1a8ed-0abc-4a3d-bf0a-64cce82f69c8"
        assertTrue(VirtualDisplayRecoveryRecord.previousBoot(a,b));assertFalse(VirtualDisplayRecoveryRecord.previousBoot(null,b))
        assertFalse(VirtualDisplayRecoveryRecord.previousBoot("corrupt",b));assertFalse(VirtualDisplayRecoveryRecord.previousBoot(a,a))
    }
    @Test fun connectFailureIsNotMisreportedAsPreferencesFailure() {
        val e=VirtualDisplayRecoveryException.classify("connect",java.io.IOException("private-credential"))
        assertEquals("RECOVERY_CONNECT_FAILED",e.code);assertEquals("IOException",e.exceptionType);assertFalse(e.toString().contains("private-credential"))
    }
}
