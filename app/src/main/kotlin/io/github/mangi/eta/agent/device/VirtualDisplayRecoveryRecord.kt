package io.github.mangi.eta.agent.device

/** Contains an IPC capability: never use a generated data-class toString. */
internal class VirtualDisplayRecoveryRecord private constructor(
    val socket: String, val pid: Long, val displayId: Int, val uniqueId: String,
    val token: String, val run: String, val boot: String?, val kept: String?,
    val mutationBarrier: String?,
) {
    override fun toString() = "RecoveryRecord(displayId=$displayId, credential=redacted)"
    fun key() = VirtualDisplayManualClose.Key(displayId, uniqueId, boot.orEmpty(), pid, socket)

    companion object {
        const val BARRIER = "mutation_barrier"
        fun decode(fields: Map<String, *>): VirtualDisplayRecoveryRecord {
            fun string(key: String): String = (fields[key] as? String)?.takeIf { it.isNotBlank() }
                ?: throw VirtualDisplayRecoveryException("record_decode", "RECOVERY_RECORD_FIELD_INVALID", key)
            fun optionalString(key: String): String? {
                if (!fields.containsKey(key)) return null
                return fields[key] as? String
                    ?: throw VirtualDisplayRecoveryException("record_decode", "RECOVERY_RECORD_FIELD_INVALID", key)
            }
            val pid = fields["pid"] as? Long
                ?: throw VirtualDisplayRecoveryException("record_decode", "RECOVERY_RECORD_FIELD_INVALID", "pid")
            val display = fields["display"] as? Int
                ?: throw VirtualDisplayRecoveryException("record_decode", "RECOVERY_RECORD_FIELD_INVALID", "display")
            val socket = string("socket")
            if (pid <= 0 || pid > Int.MAX_VALUE || display <= 0 ||
                !socket.matches(Regex("eta[.]vd[.]owner[.][0-9a-f]{32}")))
                throw VirtualDisplayRecoveryException("record_decode", "RECOVERY_RECORD_IDENTITY_INVALID")
            val boot = optionalString("boot")
            if (boot != null && !validBoot(boot))
                throw VirtualDisplayRecoveryException("record_decode", "RECOVERY_RECORD_FIELD_INVALID", "boot")
            return VirtualDisplayRecoveryRecord(socket, pid, display, string("unique"), string("token"),
                string("run"), boot, optionalString("kept"), optionalString(BARRIER))
        }
        fun validBoot(value: String): Boolean =
            value.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
        /** Missing/malformed boot is not evidence that an old owner disappeared. */
        fun previousBoot(saved: Any?, current: String): Boolean = saved is String &&
            validBoot(saved) && validBoot(current) && saved != current
    }
}

/** Only symbolic fields/types cross this boundary. Never retain an exception message or token. */
internal class VirtualDisplayRecoveryException(
    val stage: String, val code: String, val field: String = "", val exceptionType: String = "",
) : IllegalStateException(code) {
    companion object {
        fun classify(stage: String, error: Exception): VirtualDisplayRecoveryException {
            if (error is VirtualDisplayRecoveryException) return error
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val code = when (stage) {
                "record_read" -> "RECOVERY_STATE_UNREADABLE"
                "record_decode" -> "RECOVERY_RECORD_FIELD_INVALID"
                "connect" -> "RECOVERY_CONNECT_FAILED"
                "peer" -> "RECOVERY_PEER_UNAVAILABLE"
                "status" -> "RECOVERY_STATUS_FAILED"
                "handoff_state" -> "RECOVERY_HANDOFF_STATE_INVALID"
                "selection" -> "RECOVERY_SELECTION_INVALID"
                "record_write" -> "RECOVERY_STATE_UNWRITABLE"
                else -> "RECOVERY_VERIFICATION_FAILED"
            }
            return VirtualDisplayRecoveryException(stage, code, exceptionType = error.javaClass.simpleName
                .takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,80}")) }.orEmpty())
        }
    }
}
