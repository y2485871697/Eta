package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONObject

/** Small telemetry only: never contains prompts, tool bodies or credentials. */
internal data class SubAgentContextStats(
    val taskId: String,
    val worker: Int,
    val role: String,
    val model: String,
    val modelName: String,
    val providerName: String,
    val contextWindow: Int?,
    val contextTokens: Int? = null,
    val projected: Boolean = false,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val isCompacting: Boolean = false,
    val compactionCount: Int = 0,
    val beforeCompactionTokens: Int? = null,
    val afterCompactionTokens: Int? = null,
    val status: String = "running",
    val slot: Int? = null,
    val providerId: String = "",
    val modelId: String = "",
    val agentId: String = "",
    val agentName: String = "",
    val manualCompactionState: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("task_id", taskId).put("worker", worker)
        .put("role", role).put("model", model).put("model_name", modelName).put("provider_name", providerName)
        .put("context_window", contextWindow ?: JSONObject.NULL).put("context_tokens", contextTokens ?: JSONObject.NULL)
        .put("context_percent", if (contextTokens != null && contextWindow != null && contextWindow > 0)
            contextTokens.toDouble() / contextWindow * 100 else JSONObject.NULL)
        .put("projected", projected).put("input_tokens", inputTokens).put("output_tokens", outputTokens)
        .put("is_compacting", isCompacting).put("compaction_count", compactionCount)
        .put("before_compaction_tokens", beforeCompactionTokens ?: JSONObject.NULL)
        .put("after_compaction_tokens", afterCompactionTokens ?: JSONObject.NULL).put("status", status)
        .put("slot", slot ?: JSONObject.NULL).put("provider_id", providerId).put("model_id", modelId).put("agent_id", agentId).put("agent_name", agentName).put("manual_compaction_state", manualCompactionState)

    companion object {
        fun fromJson(j: JSONObject) = SubAgentContextStats(
            j.getString("task_id"), j.getInt("worker"), j.getString("role"), j.getString("model"),
            j.getString("model_name"), j.getString("provider_name"), j.intOrNull("context_window"),
            j.intOrNull("context_tokens"), j.optBoolean("projected", true), j.optLong("input_tokens"),
            j.optLong("output_tokens"), j.optBoolean("is_compacting"), j.optInt("compaction_count"),
            j.intOrNull("before_compaction_tokens"), j.intOrNull("after_compaction_tokens"), j.optString("status", "running"),
            j.intOrNull("slot"), j.optString("provider_id"), j.optString("model_id"), j.optString("agent_id"), j.optString("agent_name"), j.optString("manual_compaction_state"))
        private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)
    }
}

internal class SubAgentContextTracker(initial: SubAgentContextStats) {
    var value: SubAgentContextStats = initial.copy(
        contextTokens = initial.contextTokens.takeUnless { initial.projected }, projected = false,
        afterCompactionTokens = initial.afterCompactionTokens.takeUnless { initial.projected })
        private set
    private val billedRounds = mutableMapOf<Int, AgentTokenUsage>()
    private var awaitingCompactedUsage = false

    @Synchronized fun start(): SubAgentContextStats {
        value = value.copy(status = "running")
        return value
    }

    @Synchronized fun awaitDecision(): SubAgentContextStats {
        value = value.copy(status = "awaiting_decision")
        return value
    }

    @Synchronized fun manualRequest(state: String): SubAgentContextStats {
        value = value.copy(manualCompactionState = state)
        return value
    }

    @Synchronized fun accept(event: AgentEvent): SubAgentContextStats? {
        if (value.status !in setOf("running", "awaiting_decision")) return null
        if (event is AgentEvent.UsageReceived && event.projected) return null
        value = when (event) {
            is AgentEvent.UsageReceived -> {
                if (!event.projected) billedRounds[event.round] = event.usage
                val tokens = event.usage.occupancyTokens()
                value.copy(contextTokens = tokens ?: value.contextTokens, projected = false,
                    inputTokens = billedRounds.values.sumOf { (it.inputTokens ?: 0).toLong() },
                    outputTokens = billedRounds.values.sumOf { (it.outputTokens ?: 0).toLong() },
                    afterCompactionTokens = if (awaitingCompactedUsage) tokens else value.afterCompactionTokens)
                    .also { if (tokens != null) awaitingCompactedUsage = false }
            }
            is AgentEvent.ContextCompactionStarted -> value.copy(isCompacting = true,
                manualCompactionState = if (value.manualCompactionState == "pending") "compressing" else value.manualCompactionState,
                beforeCompactionTokens = if (value.isCompacting) value.beforeCompactionTokens else value.contextTokens,
                afterCompactionTokens = null)
            is AgentEvent.ContextCompacted -> {
                awaitingCompactedUsage = event.applied
                value.copy(isCompacting = false,
                    manualCompactionState = if (value.manualCompactionState in setOf("pending", "compressing"))
                        (if (event.applied) "completed" else "skipped") else value.manualCompactionState,
                    compactionCount = value.compactionCount + if (event.applied) 1 else 0,
                    contextTokens = if (event.applied) null else value.contextTokens)
            }
            else -> return null
        }
        return value
    }

    @Synchronized fun finish(status: String): SubAgentContextStats {
        value = value.copy(status = status, isCompacting = false,
            manualCompactionState = if (value.manualCompactionState in setOf("pending", "compressing")) "ended" else value.manualCompactionState)
        return value
    }
}
