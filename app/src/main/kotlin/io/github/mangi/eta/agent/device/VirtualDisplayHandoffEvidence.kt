package io.github.mangi.eta.agent.device

/** Pure adapter for the existing v1 owner wire contract; never opens a connection. */
internal object VirtualDisplayHandoffEvidence {
    /**
     * Authentication comes from the peer-verified client and its token-authenticated requests,
     * NOT an invented response field. OwnerProtocol.fail includes v/op/ok/error/message but no
     * displayId, uniqueId, or sideEffectsAttempted. The error code is the owner's side-effect
     * boundary signal; a fresh authenticated status must independently corroborate it.
     */
    fun refused(
        authenticatedConnection: Boolean,
        responseOk: Boolean,
        code: String,
        safeDetail: String,
        field: (String) -> Any?,
    ): VirtualDisplayHandoffRetry.Attempt.Refused = VirtualDisplayHandoffRetry.Attempt.Refused(
        code, safeDetail,
        authenticated = authenticatedConnection && !responseOk && envelope(field, "handoff", false) &&
            field("error") == code &&
            // Sanitization is for diagnostics only; malformed wire detail never gains authority.
            field("message") == safeDetail,
    )

    /** Handoff success has task tallies, but no display identity fields in v1. */
    fun completed(
        authenticatedConnection: Boolean,
        responseOk: Boolean,
        selectedIds: Set<Int>,
        retainedIds: Set<Int>,
        keptIds: Set<Int>?,
        removedIds: Set<Int>?,
        field: (String) -> Any?,
    ): Boolean = authenticatedConnection && responseOk && envelope(field, "handoff", true) &&
        field("handedOff") == true && field("sourceEmpty") == true &&
        selectedIds.isNotEmpty() && retainedIds.all { it > 0 } && retainedIds.containsAll(selectedIds) &&
        keptIds == selectedIds && removedIds == retainedIds - selectedIds

    fun state(
        identity: VirtualDisplayHandoffRetry.OwnerIdentity,
        authenticatedConnection: Boolean,
        statusOk: Boolean,
        flags: VirtualDisplayRecoveryPolicy.Flags?,
        retainedTaskIds: Set<Int>?,
        field: (String) -> Any?,
    ): VirtualDisplayHandoffRetry.OwnerState? {
        if (!authenticatedConnection || !statusOk || !identity.isValid() ||
            flags == null || retainedTaskIds == null || retainedTaskIds.any { it <= 0 }) return null
        if (!envelope(field, "status", true) || field("ready") != true || field("session") != "active" ||
            field("displayId") != identity.displayId || field("uniqueId") != identity.uniqueId) return null
        if (field("finishing") != flags.finishing || field("handoffComplete") != flags.handoffComplete ||
            field("releaseAttempted") != flags.releaseAttempted || field("mutationUncertain") != flags.mutationUncertain ||
            field("sourceEmpty") != flags.sourceEmpty) return null
        val count = field("sourceTaskCount") as? Int ?: return null
        if (count < 0 || flags.sourceEmpty != (count == 0) ||
            field("sourceState") != (if (count == 0) "empty" else "occupied")) return null
        return VirtualDisplayHandoffRetry.OwnerState(identity, true, flags, retainedTaskIds.toSet(), count)
    }

    /** The owner exits after release, so authenticate the request connection, not post-reply liveness. */
    fun released(
        identity: VirtualDisplayHandoffRetry.OwnerIdentity,
        authenticatedConnection: Boolean,
        responseOk: Boolean,
        field: (String) -> Any?,
    ): Boolean = authenticatedConnection && responseOk && identity.isValid() && envelope(field, "release", true) &&
        field("released") == true && field("displayId") == identity.displayId && field("uniqueId") == identity.uniqueId

    private fun envelope(field: (String) -> Any?, op: String, ok: Boolean): Boolean =
        field("v") == 1 && field("op") == op && field("ok") == ok
}
