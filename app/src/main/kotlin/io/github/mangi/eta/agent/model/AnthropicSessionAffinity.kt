package io.github.mangi.eta.agent.model

import java.security.MessageDigest
import org.json.JSONObject

/**
 * Native, privacy-safe conversation affinity for Anthropic-compatible gateways.
 * The caller owns the durable session ID. This is not a cache handle or a TTL guarantee:
 * gateways may route on metadata.user_id only when their affinity policy is enabled.
 */
internal object AnthropicSessionAffinity {
    fun applyDefault(request: JSONObject, sessionId: String) {
        if (sessionId.isBlank()) return
        // Explicit null/string/array metadata remains caller-owned, just like custom user_id.
        val metadata = if (request.has("metadata")) {
            request.optJSONObject("metadata") ?: return
        } else {
            JSONObject().also { request.put("metadata", it) }
        }
        if (metadata.has("user_id")) return
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("eta-anthropic-session-v1\u0000" + sessionId).toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        // Do not put raw conversation IDs, account identifiers, credentials or PII on the wire.
        metadata.put("user_id", "eta-session-$hex")
    }
}
