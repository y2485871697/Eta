package io.github.mangi.eta.agent.device

/** Diagnostics only. Nothing here is evidence permitting handoff, release, or retry. */
internal object VirtualDisplayHandoffDiagnostics {
    /** Bound the number of IDs, not Android's monotonically increasing task ID values. */
    const val MAX_TASK_IDS = 256
    const val MAX_PHASE_LENGTH = 80
    val PROGRESS_KEYS = setOf(
        "attemptedTaskIds", "relocatedTaskIds", "completedTaskIds",
    )

    data class Progress(
        val attemptedTaskIds: List<Int>,
        val relocatedTaskIds: List<Int>,
        val completedTaskIds: List<Int>,
    ) {
        fun fields(): Map<String, List<Int>> = linkedMapOf(
            "attemptedTaskIds" to attemptedTaskIds,
            "relocatedTaskIds" to relocatedTaskIds,
            "completedTaskIds" to completedTaskIds,
        )
    }

    data class Failure(
        val sideEffectsAttempted: Boolean?,
        val handoffPhase: String?,
        val progress: Progress?,
    ) {
        fun fields(): Map<String, Any> = linkedMapOf<String, Any>().apply {
            sideEffectsAttempted?.let { put("sideEffectsAttempted", it) }
            handoffPhase?.let { put("handoffPhase", it) }
            progress?.let { putAll(it.fields()) }
        }
    }

    /** Read only whitelisted fields. Never coerce booleans, phase text, or numeric IDs. */
    fun sanitizeFailure(field: (String) -> Any?): Failure = Failure(
        sideEffectsAttempted = field("sideEffectsAttempted") as? Boolean,
        handoffPhase = (field("handoffPhase") as? String)?.takeIf {
            it.length in 1..MAX_PHASE_LENGTH && it.matches(Regex("[A-Za-z][A-Za-z0-9_:#.\\-]*"))
        },
        progress = sanitizeProgress(PROGRESS_KEYS.associateWith(field)),
    )

    /**
     * Missing/legacy/null, extra keys, coercible numbers, duplicates, oversized arrays, and
     * inconsistent subsets invalidate the WHOLE optional progress object. Never truncate or
     * infer that an empty array means no task moved. Copy accepted historical checkpoints.
     */
    fun sanitizeProgress(raw: Any?): Progress? {
        val fields = raw as? Map<*, *> ?: return null
        if (fields.keys != PROGRESS_KEYS) return null
        fun ids(key: String): List<Int>? {
            val values = fields[key] as? List<*> ?: return null
            if (values.size > MAX_TASK_IDS) return null
            val result = ArrayList<Int>(values.size)
            val seen = HashSet<Int>()
            for (value in values) {
                if (value !is Int || value <= 0 || !seen.add(value)) return null
                result.add(value)
            }
            return result.toList()
        }
        val attempted = ids("attemptedTaskIds") ?: return null
        val relocated = ids("relocatedTaskIds") ?: return null
        val completed = ids("completedTaskIds") ?: return null
        if (!attempted.toSet().containsAll(relocated) || !relocated.toSet().containsAll(completed)) return null
        return Progress(attempted, relocated, completed)
    }

    /** Additive fields: deliberately cannot replace the receipt's ok, error, or safe message. */
    fun uncertainFields(failure: Failure?): Map<String, Any> {
        val progress = failure?.progress
        val checkpoints = if (progress == null) {
            "No valid handoff progress is available (legacy, absent, or rejected); movement progress is unknown."
        } else {
            "Historical owner checkpoints only: attemptedTaskIds=${progress.attemptedTaskIds}, " +
                "relocatedTaskIds=${progress.relocatedTaskIds}, completedTaskIds=${progress.completedTaskIds}. " +
                "Relocation is not completion; these checkpoints are not fresh task locations or proof of delivery/release."
        }
        return linkedMapOf<String, Any>().apply {
            failure?.let { putAll(it.fields()) }
            put("mutation_uncertain", true)
            put("automatic_retry_allowed", false)
            put("retryable", false)
            put("task_location", "unknown")
            put("location_evidence_fresh", false)
            put("handoff_progress_evidence", if (progress == null) "unknown" else "historical_checkpoints")
            put("handoff_diagnostic",
                RETAINED_MESSAGE + " Tasks may already have moved to the main display; current task location is unknown. " +
                    "moved=[] and empty progress arrays do not prove no move. " +
                    "Do not retry handoff/release, ask the user to reattempt, or interact with tasks on the source display; " +
                    "inspect read-only evidence only. " + checkpoints)
        }
    }

    /** Reentry returns history, not evidence of another mutation or a new location observation. */
    fun preservedFields(): Map<String, Any> = mapOf(
        "receipt_provenance" to "preserved_receipt",
        "fresh_attempt" to false,
        "location_evidence_fresh" to false,
        "receipt_note" to "Preserved failure receipt, not a new handoff/release attempt. Historical evidence may be stale.",
    )

    const val RETAINED_MESSAGE =
        "Delivery finalization failed or is unconfirmed. State retained; do not relaunch, clean up, or retry."

    const val AGENT_GUIDANCE =
        "HANDOFF_UNCERTAIN 或 mutation_uncertain=true 表示任务可能已经移到主屏，当前位置未知；moved=[] 不证明没有移动。" +
        "attemptedTaskIds、relocatedTaskIds、completedTaskIds 只记录历史检查点，relocated 不等于 completed，" +
        "空数组、缺失或旧版回执都不能证明仍在源副屏，不能据此重试或释放。状态已保留，不要重新启动、清理或重试。" +
        "重复失败可能是 preserved_receipt（缓存/可能过期），不是一次新尝试或新的位置观察。" +
        "仅经过认证且新鲜状态验证的 HANDOFF_PREFLIGHT_FAILED 焦点拒绝由运行时有限重试；不要把它与不确定的副作用失败混淆。"
}
