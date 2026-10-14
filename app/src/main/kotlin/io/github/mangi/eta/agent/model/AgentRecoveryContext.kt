package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Recovery restrictions belong to one run; unknown operation outcomes remain protected across runs. */
internal object AgentRecoveryContext {
    private const val PREFIX = "[Eta safe recovery context v1]"
    private const val LEGACY_INSTRUCTION = PREFIX + " Continue only unfinished work from committed history. " +
        "Preserve completed tool results; never repeat completed operations or failed call fragments. " +
        "Never reuse a historical tool_call_id, including calls with an unknown outcome. " +
        "Hosted/remote tools and opaque remote continuation are unavailable during this recovery. " +
        "A remote operation whose result is unknown may already have executed: do not retry it, " +
        "recreate it, or replace it with an equivalent local/browser/HTTP operation. " +
        "Only read-only verification or independent remaining work is allowed for that uncertainty. " +
        "Never claim an unknown operation succeeded. New, complete local calls for other unfinished work remain available."

    private val INSTRUCTION = LEGACY_INSTRUCTION.replace(
        "Hosted/remote tools and opaque remote continuation are unavailable during this recovery.",
        "Provider-hosted tools and opaque provider continuation are unavailable during this recovery. " +
            "New, independent client function calls, including delegate_task for unrelated unfinished work, " +
            "remain available under the current run configuration. This does not authorize replay, " +
            "replacement or automatic resumption of an uncertain historical task.",
    )
    private const val HISTORY_NOTICE = "[Eta previous recovery safety] " +
        "A previous run used transport recovery; that run's tool restrictions are not active in this new run. " +
        "Use the current run's available tools and child configuration for new, independent work. " +
        "Preserve committed results and never repeat completed operations or historical tool_call_id values. " +
        "Historical remote operations with unknown outcomes may already have executed: verify read-only, " +
        "do not retry, recreate, replace with an equivalent local/browser/HTTP operation, " +
        "or automatically resume those tasks. Never claim an unknown operation succeeded."

    fun isActive(messages: JSONArray): Boolean = (0 until messages.length()).any { index ->
        messages.optJSONObject(index)?.let(::isRecoveryMessage) == true
    }

    /** Only called while building a NEW user run, not a retry, supplement or compaction. */
    fun forNewRun(message: JSONObject): JSONObject {
        if (!isRecoveryMessage(message)) return message
        return JSONObject(message.toString()).put("content", HISTORY_NOTICE)
    }

    private fun isRecoveryMessage(message: JSONObject): Boolean =
        message.optString("role") == "system" &&
            message.optString("content") in setOf(LEGACY_INSTRUCTION, INSTRUCTION)

    fun append(messages: JSONArray) {
        if (!isActive(messages)) messages.put(JSONObject().put("role", "system").put("content", INSTRUCTION))
    }
}
