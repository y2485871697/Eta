package io.github.mangi.eta.agent.device

import org.junit.Assert.*
import org.junit.Test

/** v1 wire-shaped maps keep these contract tests JVM-only (no Android or org.json). */
class VirtualDisplayHandoffEvidenceTest {
    private val identity = VirtualDisplayHandoffRetry.OwnerIdentity("owner-socket", 123L, 2, "owner-unique")
    private val clean = VirtualDisplayRecoveryPolicy.Flags(false, false, false, false, false)
    private val selected = setOf(16, 17)
    private val retained = setOf(16, 17, 18)
    private val detail = "preflight:focus:Focus_BINDER_CHANGED;moved=[] removed=[]"
    private val code = VirtualDisplayHandoffRetry.ERROR_CODE

    // OwnerProtocol.fail + OwnerCommandDispatcher: no identity or sideEffectsAttempted field.
    private fun failure(): Map<String, Any?> = mapOf(
        "v" to 1, "op" to "handoff", "ok" to false, "error" to code, "message" to detail,
        "focusSamples" to listOf("Focus_BINDER_CHANGED", "Focus_FOCUS_UNSTABLE"),
    )

    // OwnerProtocol.ok("status", owner.status()) emits these fields at top level, not in a body.
    private fun status(): Map<String, Any?> = mapOf(
        "v" to 1, "op" to "status", "ok" to true, "ready" to true, "session" to "active",
        "displayId" to 2, "uniqueId" to "owner-unique", "retainedTaskIds" to listOf(16, 17, 18),
        "finishing" to false, "handoffComplete" to false, "releaseAttempted" to false,
        "mutationUncertain" to false, "sourceEmpty" to false,
        "sourceState" to "occupied", "sourceTaskCount" to 3,
    )

    private fun readState(
        fields: Map<String, Any?> = status(),
        authenticated: Boolean = true,
        ok: Boolean = true,
        owner: VirtualDisplayHandoffRetry.OwnerIdentity = identity,
    ): VirtualDisplayHandoffRetry.OwnerState? {
        val rawFlags = listOf("finishing", "handoffComplete", "releaseAttempted", "mutationUncertain", "sourceEmpty")
            .map { fields[it] }
        val flags = if (rawFlags.all { it is Boolean }) VirtualDisplayRecoveryPolicy.Flags(
            rawFlags[0] as Boolean, rawFlags[1] as Boolean, rawFlags[2] as Boolean,
            rawFlags[3] as Boolean, rawFlags[4] as Boolean) else null
        val ids = (fields["retainedTaskIds"] as? List<*>)?.let(VirtualDisplayRecoveryPolicy::taskIds)
        return VirtualDisplayHandoffEvidence.state(owner, authenticated, ok, flags, ids, fields::get)
    }

    private fun refusal(
        fields: Map<String, Any?> = failure(),
        authenticated: Boolean = true,
        ok: Boolean = false,
        safeDetail: String = detail,
    ) = VirtualDisplayHandoffEvidence.refused(authenticated, ok, code, safeDetail, fields::get)

    @Test fun actualV1FailureAndIndependentStatusAuthorizeOnlyBoundedPending() {
        val wire = failure()
        assertFalse(wire.containsKey("sideEffectsAttempted"))
        assertFalse(wire.containsKey("displayId"))
        assertFalse(wire.containsKey("uniqueId"))
        val before = readState()!!
        assertEquals(VirtualDisplayHandoffRetry.OwnerState(identity, true, clean, retained, 3), before)
        var attempts = 0
        var reads = 0
        val result = VirtualDisplayHandoffRetry.run(VirtualDisplayHandoffRetry.Budget(), {
            attempts++
            refusal(wire)
        }, {
            reads++
            VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, readState(), selected)
        }, {}) as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(3, attempts)
        assertEquals(3, reads)
        assertEquals("handoff_pending", result.phase)
        assertEquals(VirtualDisplayHandoffRetry.Failure(code, detail), result.failure)
    }

    @Test fun missingAndCoercedFailureFieldsNeverAuthenticate() {
        for (field in listOf("v", "op", "ok", "error", "message"))
            assertFalse(field, refusal(failure() - field).authenticated)
        for ((field, value) in listOf(
            "v" to "1", "v" to 1L, "v" to 1.0, "v" to 2,
            "op" to "status", "ok" to "false", "ok" to true,
            "error" to "HANDOFF_UNCERTAIN", "message" to 42,
        )) assertFalse("$field=$value", refusal(failure() + (field to value)).authenticated)
        assertFalse(refusal(authenticated = false).authenticated)
        assertFalse(refusal(ok = true).authenticated)
    }

    @Test fun sanitizedDiagnosticsDoNotRepairMalformedWireEvidence() {
        for (raw in listOf(" $detail", "$detail\n", detail.replace(";", ";\t"), "$detail extra"))
            assertFalse(raw, refusal(failure() + ("message" to raw)).authenticated)
        val spaced = detail.replace(";", "; ")
        assertTrue(refusal(failure() + ("message" to spaced), safeDetail = spaced).authenticated)
    }

    @Test fun inventedSideEffectsFieldDoesNotAuthorizeRetry() {
        val wire = failure() + mapOf("error" to "HANDOFF_UNCERTAIN", "sideEffectsAttempted" to false)
        val attempt = VirtualDisplayHandoffEvidence.refused(true, false, "HANDOFF_UNCERTAIN", detail, wire::get)
        assertTrue(attempt.authenticated)
        var calls = 0
        val result = VirtualDisplayHandoffRetry.run(VirtualDisplayHandoffRetry.Budget(),
            { calls++; attempt }, { error("unknown outcome must not retry") }, {})
            as VirtualDisplayHandoffRetry.Outcome.Stopped
        assertEquals(1, calls)
        assertEquals("uncertain", result.phase)
    }

    @Test fun missingMalformedAndUnknownStatusFieldsFailClosed() {
        for (field in status().keys) assertNull(field, readState(status() - field))
        for ((field, value) in listOf(
            "v" to "1", "v" to 1L, "v" to 1.0, "v" to 2,
            "op" to "handoff", "ok" to "true", "ok" to false,
            "ready" to false, "session" to "released",
            "displayId" to "2", "displayId" to 2L, "displayId" to 0, "displayId" to 3,
            "uniqueId" to "other", "uniqueId" to "",
            "sourceTaskCount" to -1, "sourceTaskCount" to "3", "sourceTaskCount" to 3L,
            "sourceTaskCount" to 3.0, "sourceTaskCount" to 0,
            "sourceState" to "unknown", "sourceState" to "empty", "sourceEmpty" to true,
            "finishing" to "false", "handoffComplete" to 0, "releaseAttempted" to null,
            "mutationUncertain" to "false",
            "retainedTaskIds" to "[16,17,18]", "retainedTaskIds" to listOf(16, 16),
            "retainedTaskIds" to listOf(0), "retainedTaskIds" to listOf(-1),
            "retainedTaskIds" to listOf("16"), "retainedTaskIds" to listOf(16L),
        )) assertNull("$field=$value", readState(status() + (field to value)))
        assertNull(readState(authenticated = false))
        assertNull(readState(ok = false))
        assertNull(readState(owner = identity.copy(socketName = "")))
        assertNull(readState(owner = identity.copy(pid = 0)))
        assertNull(readState(owner = identity.copy(displayId = 0)))
        assertNull(readState(owner = identity.copy(uniqueId = "")))
    }

    @Test fun parsedFlagsCannotContradictWireFlags() {
        assertNull(VirtualDisplayHandoffEvidence.state(identity, true, true,
            clean.copy(finishing = true), retained, status()::get))
    }

    @Test fun sourceCountAndUnselectedInventoryDriftDefeatRetry() {
        val before = readState()!!
        for (fields in listOf(
            status() + ("sourceTaskCount" to 4),
            status() + ("retainedTaskIds" to listOf(16, 17)),
            status() + ("retainedTaskIds" to listOf(16, 17, 19)),
            status() + mapOf("finishing" to true, "mutationUncertain" to true),
        )) assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, readState(fields), selected))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, readState(owner = identity.copy(pid = 124L)), selected))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, readState(owner = identity.copy(socketName = "other")), selected))
        val inconsistent = readState(status() + ("sourceTaskCount" to 2))!!
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(inconsistent, inconsistent, selected))
    }

    @Test fun frameAndDiagnosticChangesAreNotMutationEvidence() {
        val before = readState(status() + mapOf("frameCount" to 10L, "focusSamples" to emptyList<String>()))
        val after = readState(status() + mapOf("frameCount" to 11L, "frameTimestampNs" to 999L,
            "focusSamples" to listOf("Focus_FOCUS_UNSTABLE"), "rootTaskDiagnostic" to "changed"))
        assertTrue(VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, after, selected))
    }

    @Test fun completedHandoffRequiresActualV1TaskTalliesNotInventedIdentityFields() {
        val wire = mapOf<String, Any?>("v" to 1, "op" to "handoff", "ok" to true,
            "handedOff" to true, "sourceEmpty" to true)
        fun completed(fields: Map<String, Any?> = wire, kept: Set<Int>? = selected,
            removed: Set<Int>? = setOf(18), authenticated: Boolean = true, ok: Boolean = true) =
            VirtualDisplayHandoffEvidence.completed(authenticated, ok, selected, retained, kept, removed, fields::get)
        assertTrue(completed())
        assertFalse(completed(authenticated = false))
        assertFalse(completed(ok = false))
        assertFalse(completed(kept = null))
        assertFalse(completed(kept = setOf(16)))
        assertFalse(completed(removed = null))
        assertFalse(completed(removed = emptySet()))
        assertFalse(completed(removed = setOf(17, 18)))
        for (field in wire.keys) assertFalse(field, completed(wire - field))
        assertFalse(completed(wire + ("ok" to "true")))
    }

    @Test fun postHandoffStatusMayRetainOwnedIdsButCannotAuthorizeReplay() {
        // VirtualDisplayOwner keeps owned IDs after moving/removing them; source count is now zero.
        val post = readState(status() + mapOf("sourceTaskCount" to 0, "sourceEmpty" to true,
            "sourceState" to "empty", "finishing" to true, "handoffComplete" to true))!!
        assertEquals(retained, post.retainedTaskIds)
        assertEquals(0, post.sourceTaskCount)
        assertEquals(VirtualDisplayRecoveryPolicy.Action.RELEASE_ONLY, VirtualDisplayRecoveryPolicy.finishAction(post.flags))
        assertFalse(VirtualDisplayHandoffRetry.freshStateAllowsRetry(post, post, selected))
    }

    @Test fun releaseReceiptRequiresExactV1DisplayIdentity() {
        val wire = mapOf<String, Any?>("v" to 1, "op" to "release", "ok" to true,
            "released" to true, "displayId" to 2, "uniqueId" to "owner-unique")
        assertTrue(VirtualDisplayHandoffEvidence.released(identity, true, wire::get))
        for (field in wire.keys)
            assertFalse(field, VirtualDisplayHandoffEvidence.released(identity, true, (wire - field)::get))
        assertFalse(VirtualDisplayHandoffEvidence.released(identity.copy(displayId = 3), true, wire::get))
        assertFalse(VirtualDisplayHandoffEvidence.released(identity.copy(uniqueId = "other"), true, wire::get))
        assertFalse(VirtualDisplayHandoffEvidence.released(identity, false, wire::get))
    }
}
