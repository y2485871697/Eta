package io.github.mangi.eta.ui.model

/** A revision changes whenever the model or the committed context is invalidated.
 * Keep it with the context checkpoint; never reconstruct it from message bills. */
internal data class ContextUsageScope(
    val conversationId: String,
    val providerId: String,
    val modelId: String,
    val revision: Long,
)

/** Only this versioned, scoped receipt is eligible for occupancy restoration.
 * Billing totals and compaction afterTokens are deliberately not part of it. */
internal data class CloudContextUsageReceipt(
    val scope: ContextUsageScope,
    val inputTokens: Int,
    val source: String = "cloud_actual",
    val version: Int = 1,
)

/** Pure occupancy reducer, independent of cumulative billing.
 * Callers capture the scope when the request starts, not when its event arrives. */
internal data class CloudContextUsageState(
    val scope: ContextUsageScope,
    val inputTokens: Int? = null,
) {
    fun receive(
        requestScope: ContextUsageScope,
        usage: TokenUsageUi?,
        projected: Boolean = false,
    ): CloudContextUsageState {
        if (requestScope != scope || projected) return this
        // Preserve input across same-request partials; invalidate() starts a fresh request.
        // Assignment intentionally permits a real measurement to decrease.
        return copy(inputTokens = windowTokensFromUsage(usage) ?: inputTokens)
    }

    /** Use for committed pruning, compaction, history edits and model changes.
     * Advancing the revision also rejects delayed events from the old context. */
    fun invalidate(
        conversationId: String = scope.conversationId,
        providerId: String = scope.providerId,
        modelId: String = scope.modelId,
    ): CloudContextUsageState = CloudContextUsageState(
        scope.copy(
            conversationId = conversationId,
            providerId = providerId,
            modelId = modelId,
            revision = scope.revision + 1,
        ),
    )

    fun snapshot(): CloudContextUsageReceipt? = inputTokens
        ?.takeIf { it > 0 }
        ?.let { CloudContextUsageReceipt(scope, it) }

    /** Missing provenance (including legacy snapshots) must remain unknown. */
    fun restore(receipt: CloudContextUsageReceipt?): CloudContextUsageState = copy(
        inputTokens = receipt?.takeIf {
            it.version == 1 && it.source == "cloud_actual" &&
                it.scope == scope && it.inputTokens > 0
        }?.inputTokens,
    )
}
