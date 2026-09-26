package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.oauth.OpenAiCodexOAuth
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject

internal object ResponsesRequestBuilder {
    fun build(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
        sessionId: String = "",
        singleToolCall: Boolean = false,
    ): JSONObject {
        val input = buildInput(messages, config)
        val responseTools = buildTools(tools, config.hostedWebSearchEnabled)
        val instructions = OpenAiRequestMessages.responsesInstructions(messages)
            .ifBlank { config.systemPrompt }
        val request = JSONObject()
        mergeExtraBody(request, config.extraBodyJson)
        RequestBodyMerge.mergeCustomBody(request, config.customBody)
        request.remove("eta_media_reasoning")
        request.remove(ImageRequestParameters.CONFIG_KEY) // Local image settings never enter text protocols.

        // 这些字段决定协议正确性、隐私边界和 Eta 本轮行为，必须由运行时最终写入。
        request.put("model", config.model)
        request.put("instructions", instructions)
        request.put("input", input)
        request.put("stream", true)
        request.put("store", false)
        if (responseTools.length() > 0) {
            request.put("tools", responseTools)
            request.put("tool_choice", "auto")
        } else {
            request.remove("tools")
            request.remove("tool_choice")
        }
        request.remove("previous_response_id")
        request.remove("reasoning")
        ProviderReasoning.applyResponsesRequest(request, config)
        config.summaryOutputLimit?.let { request.put("max_output_tokens", it) }
        if (OpenAiCodexOAuth.isCodexEndpoint(config.baseUrl)) {
            request.put("include", JSONArray().put("reasoning.encrypted_content"))
            request.remove("max_output_tokens")
            val effort = when (config.effectiveReasoningEffort) {
                ReasoningEffort.OFF, ReasoningEffort.DEFAULT -> "medium"
                ReasoningEffort.MINIMAL -> "low"
                ReasoningEffort.MAX -> "high"
                else -> config.effectiveReasoningEffort.wireValue
            }
            request.put(
                "reasoning",
                JSONObject()
                    .put("effort", effort)
                    .put("summary", "auto"),
            )
            if (sessionId.isNotBlank()) {
                request.put("prompt_cache_key", sessionId)
            }
            if (responseTools.length() > 0 && !request.has("parallel_tool_calls")) {
                request.put("parallel_tool_calls", true)
            }
        }
        // Apply after custom-body and Codex defaults: a correction must never request parallel calls.
        if (singleToolCall && responseTools.length() > 0) request.put("parallel_tool_calls", false)
        return request
    }

    private fun buildInput(messages: JSONArray, config: AgentModelClient.ModelConfig): JSONArray = JSONArray().also { input ->
        val legacyCalls = mutableSetOf<String>()
        val needsReasoningReplay = config.model.contains("deepseek", ignoreCase = true) &&
            config.effectiveReasoningEffort != ReasoningEffort.OFF
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            ResponsesEphemeralState.outputItems(message)?.let { items ->
                for (itemIndex in 0 until items.length()) input.put(deepCopy(items.opt(itemIndex)))
                continue
            }
            val reasoning = ResponsesReasoningState.items(message, config)
            val calls = message.optJSONArray("tool_calls")
            // Old checkpoints cannot recreate the provider's original reasoning. Keep their
            // evidence as quoted history, not a fabricated valid thinking/tool-call exchange.
            if (needsReasoningReplay && message.optString("role") == "assistant" &&
                calls != null && calls.length() > 0 && reasoning == null
            ) {
                for (i in 0 until calls.length()) {
                    calls.optJSONObject(i)?.optString("id")?.let(legacyCalls::add)
                }
                input.put(JSONObject().put("type", "message").put("role", "assistant").put(
                    "content", message.optString("content").takeUnless { it == "null" }.orEmpty() +
                        "\n[历史工具调用记录：原始推理数据不可恢复，仅供参考，不代表新的执行请求，不要自动重放]\n" + calls.toString(),
                ))
                continue
            }
            if (message.optString("role") == "tool" && message.optString("tool_call_id") in legacyCalls) {
                input.put(JSONObject().put("type", "message").put("role", "assistant").put(
                    "content", "[历史工具结果 " + message.optString("tool_call_id") + "]\n" + message.optString("content"),
                ))
                continue
            }
            if (message.optString("role") == "assistant" && reasoning != null) {
                for (i in 0 until reasoning.length()) input.put(deepCopy(reasoning.getJSONObject(i)))
            }
            when (message.optString("role")) {
                "tool" -> input.put(
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", message.optString("tool_call_id"))
                        .put("output", message.optString("content")),
                )
                "assistant" -> appendAssistantInput(input, message)
                "system", "developer" -> Unit
                else -> input.put(
                    JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put("content", convertUserContent(message.opt("content"))),
                )
            }
        }
    }

    private fun appendAssistantInput(input: JSONArray, message: JSONObject) {
        val content = message.optString("content")
        if (content.isNotBlank() && content != "null") {
            input.put(
                JSONObject()
                    .put("type", "message")
                    .put("role", "assistant")
                    .put("content", content),
            )
        }
        val calls = message.optJSONArray("tool_calls") ?: return
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val function = call.optJSONObject("function") ?: continue
            input.put(
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", call.optString("id").ifBlank { "tool_call_$index" })
                    .put("name", function.optString("name"))
                    .put("arguments", function.optString("arguments").ifBlank { "{}" }),
            )
        }
    }

    private fun convertUserContent(raw: Any?): Any {
        if (raw is String) return raw
        val source = raw as? JSONArray ?: return ""
        return JSONArray().also { content ->
            for (index in 0 until source.length()) {
                val part = source.optJSONObject(index) ?: continue
                when (part.optString("type")) {
                    "text", "input_text" -> content.put(
                        JSONObject().put("type", "input_text").put("text", part.optString("text")),
                    )
                    "image_url", "input_image" -> {
                        val url = part.optJSONObject("image_url")?.optString("url")
                            ?: part.optString("image_url")
                        if (url.isNotBlank()) {
                            content.put(JSONObject().put("type", "input_image").put("image_url", url))
                        }
                    }
                    "video_url", "input_video" -> {
                        val url = part.optJSONObject("video_url")?.optString("url")
                            ?: part.optString("video_url")
                        if (url.isNotBlank()) {
                            content.put(JSONObject().put("type", "input_video").put("video_url", url))
                        }
                    }
                }
            }
        }
    }

    private fun buildTools(tools: JSONArray, hostedWebSearchEnabled: Boolean): JSONArray =
        JSONArray().also { result ->
            for (index in 0 until tools.length()) {
                val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
                result.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", function.optString("name"))
                        .put("description", function.optString("description"))
                        .put("parameters", deepCopy(function.opt("parameters") ?: JSONObject()))
                        .put("strict", false),
                )
            }
            if (hostedWebSearchEnabled) result.put(JSONObject().put("type", "web_search"))
        }

    private fun mergeExtraBody(request: JSONObject, extraBodyJson: String) {
        if (extraBodyJson.isBlank()) return
        val extra = JSONObject(extraBodyJson)
        extra.keys().forEach { key -> request.put(key, extra.get(key)) }
    }

    private fun deepCopy(value: Any?): Any = when (value) {
        is JSONObject -> JSONObject(value.toString())
        is JSONArray -> JSONArray(value.toString())
        null -> JSONObject.NULL
        else -> JSONObject.wrap(value) ?: JSONObject.NULL
    }
}
