package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** A rejected generation, not an unknown tool execution result. Never repair its JSON. */
internal object ResponsesToolEnvelopeRecovery {
    const val CODE = "RESPONSES_TOOL_ENVELOPE_REJECTED"
    const val MAX_RETRIES = 2

    private val rejection = Regex(
        "basispoints tool transport code must contain one JSON client-tool envelope; " +
            "OfficeJS and multiple calls are unsupported \\(format=json_object; bytes=[0-9]+; " +
            "json_offset=[0-9]+; json_failure=missing-separator\\)",
    )
    private val validationCodes = setOf(
        "", "400", "422", "500", "invalid_request_error", "validation_error",
        "server_error", "internal_error", "api_error",
    )

    // Full-string matching deliberately excludes quoted errors, execution failures, and
    // unknown validator versions. Dynamic byte/offset values carry no execution authority.
    fun matches(error: JSONObject?, status: Int? = null): Boolean {
        if (error == null || (status != null && status !in setOf(400, 422, 500))) return false
        val codes = listOf(
            error.optString("code"), error.optString("type").takeUnless { it == "error" }.orEmpty(),
            error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
        )
        return codes.all { it in validationCodes } &&
            rejection.matches(error.optString("message"))
    }

    const val CORRECTION =
        "The preceding generation failed the client-tool JSON validator. Generate one fresh " +
            "tool call using the supplied tool catalog and its required serialization. " +
            "Use only one call with short arguments. Do not emit OfficeJS, JavaScript wrappers, " +
            "Markdown fences, or multiple envelopes. Escape string contents as valid JSON. " +
            "Split long scripts into small file writes followed by a short execution command. " +
            "Do not reconstruct or execute the rejected JSON."

    fun corrected(request: ProviderRequest): ProviderRequest {
        // Deep-copy only this attempt's history. Do not persist the hint or a rejected call.
        val messages = JSONArray(request.messages.toString())
        if (OpenAiRequestMessages.responsesInstructions(messages).isBlank() && request.config.systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", request.config.systemPrompt))
        }
        messages.put(JSONObject().put("role", "developer").put("content", CORRECTION))
        return request.copy(messages = messages)
    }

    /** One instance per HTTP request. Inspect raw frames before callbacks or parsing tools. */
    class DeliveryGuard(private val allowCorrection: Boolean = true) {
        var toolDeliveryPossible = false
            private set

        fun observe(event: JSONObject) {
            inspectEnvelope(event)
            val type = event.optString("type")
            when (type) {
                "response.output_item.added", "response.output_item.done" ->
                    inspectItem(event.optJSONObject("item"))
                "response.output_text.delta", "response.output_text.done",
                "response.refusal.delta", "response.refusal.done" -> {
                    if (listOf("delta", "text", "refusal").any { event.optString(it).isNotEmpty() }) toolDeliveryPossible = true
                }
                "response.content_part.added", "response.content_part.done" -> inspectPart(event.optJSONObject("part"))
                in nonToolEvents -> Unit
                // Argument deltas/done without an item, custom/hosted tools, and unknown
                // event types all fail closed, even when the normal parser ignores them.
                else -> toolDeliveryPossible = true
            }
        }

        private fun inspectOutput(response: JSONObject) {
            if (!response.has("output")) return
            val output = response.optJSONArray("output")
            if (output == null) {
                toolDeliveryPossible = true
                return
            }
            for (index in 0 until output.length()) inspectItem(output.optJSONObject(index))
        }

        fun inspectEnvelope(envelope: JSONObject) {
            inspectOutput(envelope)
            if (envelope.has("response")) {
                val response = envelope.optJSONObject("response")
                if (response == null) toolDeliveryPossible = true else inspectOutput(response)
            }
        }

        private fun inspectItem(item: JSONObject?) {
            when (item?.optString("type")) {
                "reasoning" -> Unit
                "message" -> {
                    val parts = item.optJSONArray("content")
                    if (parts == null) toolDeliveryPossible = true
                    else for (i in 0 until parts.length()) inspectPart(parts.optJSONObject(i))
                }
                else -> toolDeliveryPossible = true
            }
        }

        private fun inspectPart(part: JSONObject?) {
            when (part?.optString("type")) {
                "output_text" -> if (part.optString("text").isNotEmpty()) toolDeliveryPossible = true
                "refusal" -> toolDeliveryPossible = true
                else -> toolDeliveryPossible = true
            }
        }

        fun protect(failure: Throwable): Throwable {
            if (failure !is Exception) return failure
            val classified = AgentModelFailure.transport(failure) ?: return failure
            val correction = classified.code == CODE
            // A generic 500 or failed stream does not prove that a generation was
            // rejected before execution. Do not promote it to either kind of replay.
            val ambiguous = classified.code in setOf("HTTP_500", "PROVIDER_STREAM_ERROR")
            if (!correction && !toolDeliveryPossible && !ambiguous) return failure
            return AgentModelFailure(
                code = classified.code,
                retryable = false,
                message = classified.message.orEmpty(),
                cause = classified,
                diagnostic = classified.diagnostic,
                envelopeCorrectionAllowed = correction && classified.envelopeCorrectionAllowed && allowCorrection && !toolDeliveryPossible,
            )
        }
    }

    private val nonToolEvents = setOf(
        "response.created", "response.queued", "response.in_progress",
        "response.completed", "response.incomplete", "response.failed", "error",
        "response.output_text.delta", "response.output_text.done",
        "response.output_text.annotation.added", "response.refusal.delta", "response.refusal.done",
        "response.content_part.added", "response.content_part.done",
        "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
        "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
        "response.reasoning_text.delta", "response.reasoning_text.done",
    )
}
