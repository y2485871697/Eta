package io.github.mangi.eta.agent.device

import org.json.JSONArray
import org.json.JSONObject

/** Diagnostics only. Nothing here is evidence permitting handoff, release, or retry. */
internal object VirtualDisplayHandoffDiagnostics {
    /** Bound the number of IDs, not Android's monotonically increasing task ID values. */
    const val MAX_TASK_IDS = 256
    const val MAX_PHASE_LENGTH = 80
    const val PROGRESS_FIELD = "handoffProgress"
    val PROGRESS_KEYS = setOf(
        "mutationAttemptedTaskIds", "relocatedTaskIds", "completedTaskIds",
    )
    private val PHASE = Regex(
        "preflight:(display|selection|inventory|focus|cancelled)|anchor:(launch|observe)|" +
            "focus|remove-anchor|unknown|(hide|move|park|restore|remove):[1-9][0-9]{0,9}",
    )

    data class Progress(
        val mutationAttemptedTaskIds: List<Int>,
        val relocatedTaskIds: List<Int>,
        val completedTaskIds: List<Int>,
    ) {
        fun fields(): Map<String, List<Int>> = linkedMapOf(
            "mutationAttemptedTaskIds" to mutationAttemptedTaskIds,
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
            progress?.let { put(PROGRESS_FIELD, it.fields()) }
        }
    }

    /** Production JSON adapter: use opt, never coercing optInt/optBoolean/optString. */
    fun sanitizeFailure(owner: JSONObject?): Failure = sanitizeFailure { owner?.opt(it) }

    /** Read only whitelisted fields, never the owner's message, error, token or URI. */
    fun sanitizeFailure(field: (String) -> Any?): Failure = Failure(
        sideEffectsAttempted = field("sideEffectsAttempted") as? Boolean,
        handoffPhase = (field("handoffPhase") as? String)?.takeIf {
            it.length in 1..MAX_PHASE_LENGTH && PHASE.matches(it) &&
                (it.substringAfter(':').firstOrNull()?.isDigit() != true ||
                    it.substringAfter(':').toIntOrNull() != null)
        },
        progress = sanitizeProgress(field(PROGRESS_FIELD)),
    )

    /**
     * Missing/legacy/null, extra keys, coercible numbers, duplicates, oversized arrays, and
     * inconsistent subsets invalidate the WHOLE optional progress object. Never truncate or
     * infer that an empty array means no task moved. Copy accepted historical checkpoints.
     */
    fun sanitizeProgress(raw: Any?): Progress? {
        val fields = when (raw) {
            is JSONObject -> {
                if (raw.length() != PROGRESS_KEYS.size || PROGRESS_KEYS.any { !raw.has(it) }) return null
                PROGRESS_KEYS.associateWith { raw.opt(it) }
            }
            is Map<*, *> -> raw
            else -> return null
        }
        if (fields.keys != PROGRESS_KEYS) return null
        fun ids(key: String): List<Int>? {
            val values = when (val value = fields[key]) {
                is JSONArray -> {
                    if (value.length() > MAX_TASK_IDS) return null
                    List(value.length()) { value.opt(it) }
                }
                is List<*> -> value
                else -> return null
            }
            if (values.size > MAX_TASK_IDS) return null
            val result = ArrayList<Int>(values.size)
            val seen = HashSet<Int>()
            for (value in values) {
                if (value !is Int || value <= 0 || !seen.add(value)) return null
                result.add(value)
            }
            return result.toList()
        }
        val attempted = ids("mutationAttemptedTaskIds") ?: return null
        val relocated = ids("relocatedTaskIds") ?: return null
        val completed = ids("completedTaskIds") ?: return null
        if (!attempted.toSet().containsAll(relocated) || !relocated.toSet().containsAll(completed)) return null
        return Progress(attempted, relocated, completed)
    }

    /** Receipt-only whitelist. Retry classification still consumes its original inputs. */
    fun safeHandoffError(code: String): String = when (code) {
        "HANDOFF_PREFLIGHT_FAILED", "HANDOFF_UNCERTAIN" -> code
        else -> "HANDOFF_UNCERTAIN"
    }

    /** Additive fields: deliberately cannot replace the receipt's ok, error, or safe message. */
    fun uncertainFields(failure: Failure?): Map<String, Any> {
        val progress = failure?.progress
        val checkpoints = if (progress == null) {
            "No valid handoff progress is available (legacy, absent, or rejected); movement progress is unknown."
        } else {
            "Historical owner checkpoints only: mutationAttemptedTaskIds=${progress.mutationAttemptedTaskIds}, " +
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
                RETAINED_MESSAGE + " Tasks MAY already have moved to the main display; current task location is unknown. " +
                    "moved=[] and empty progress arrays do not prove no move. " +
                    "Do not retry handoff/release, ask the user to reattempt, or interact with tasks on the source display; " +
                    "inspect read-only evidence only. " + checkpoints)
        }
    }

    /** JSON boundary shared by Session and contract tests; never overwrite factual fields. */
    fun annotate(receipt: JSONObject, failure: Failure?, uncertain: Boolean = false): JSONObject {
        val fields = if (uncertain && receipt.opt("ok") == false) uncertainFields(failure)
            else linkedMapOf<String, Any>().apply {
                failure?.let { putAll(it.fields()) }
                put("handoff_progress_evidence", if (failure?.progress == null) "unknown" else "historical_checkpoints")
                put("location_evidence_fresh", false)
            }
        return addFields(receipt, fields)
    }

    /** Reentry returns a copy of history, not another mutation or a fresh location observation. */
    fun preservedReceipt(receipt: JSONObject): JSONObject = JSONObject(receipt.toString()).apply {
        for ((key, value) in preservedFields()) put(key, value)
    }

    private fun addFields(receipt: JSONObject, fields: Map<String, Any>): JSONObject = receipt.apply {
        val json = JSONObject(fields)
        for (key in fields.keys) if (!has(key)) put(key, json.get(key))
    }

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
        "mutationAttemptedTaskIds、relocatedTaskIds、completedTaskIds 只记录历史检查点，relocated 不等于 completed，" +
        "空数组、缺失或旧版回执都不能证明仍在源副屏，不能据此重试或释放。状态已保留，不要重新启动、清理或重试。" +
        "重复失败可能是 preserved_receipt（缓存/可能过期），不是一次新尝试或新的位置观察。" +
        "仅经过认证且新鲜状态验证的 HANDOFF_PREFLIGHT_FAILED 焦点拒绝由运行时有限重试；不要把它与不确定的副作用失败混淆。"
}
