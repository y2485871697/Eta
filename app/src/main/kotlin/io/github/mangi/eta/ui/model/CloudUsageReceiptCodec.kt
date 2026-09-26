package io.github.mangi.eta.ui.model

import java.security.MessageDigest
import org.json.JSONObject

/** A last measured input, not a prediction for the next request. */
internal object CloudUsageReceiptCodec {
    fun encode(conversationId: String, providerId: String, modelId: String, history: String, inputTokens: Int?): String {
        if (inputTokens == null || inputTokens <= 0) return ""
        return JSONObject().put("version", 1).put("source", "cloud_actual")
            .put("conversation", conversationId).put("provider", providerId).put("model", modelId)
            .put("history", fingerprint(history)).put("input", inputTokens).toString()
    }

    fun decode(raw: String, conversationId: String, providerId: String, modelId: String, history: String): Int? =
        runCatching {
            val receipt = JSONObject(raw)
            if (receipt.optInt("version") != 1 || receipt.optString("source") != "cloud_actual" ||
                receipt.optString("conversation") != conversationId || receipt.optString("provider") != providerId ||
                receipt.optString("model") != modelId || receipt.optString("history") != fingerprint(history)) null
            else receipt.optInt("input").takeIf { it > 0 }
        }.getOrNull()

    private fun fingerprint(history: String): String = MessageDigest.getInstance("SHA-256")
        .digest(history.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
