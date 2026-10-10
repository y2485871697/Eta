package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object AnthropicMessagesProvider : AgentProviderClient {
    private const val DEFAULT_MAX_TOKENS = 4096
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    override val id: String = "anthropic_messages"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.ANTHROPIC_MESSAGES,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.config
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "text/event-stream")
            .add("anthropic-version", config.anthropicVersion)
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("x-api-key", config.apiKey)
                }
                ProviderRequestHeaders.mergeInto(this, config.baseUrl, config.customHeaders, request.sessionId)
            }
            .build()
        val requestJson = request.restrictReconnectPayload(buildRequestJson(config, request.messages, request.tools, request.sessionId), capabilities.endpoint)
        val serializedBody = requestJson.toString()
        val requestBody = serializedBody.toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(ProviderUrls.anthropicMessagesUrl(config.baseUrl))
            .headers(headers)
            .post(requestBody)
            .build()

        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            AgentWireRequestEstimate.publish(requestJson, capabilities.endpoint, request, onEvent, requestBody.contentLength(), serializedBody)
            val assistant = readStreamingAssistantMessage(httpRequest, runController, onEvent, request.requiresCompleteStream)
            onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
            return ProviderResponse(assistant)
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        }
    }

    internal fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
        sessionId: String = "",
    ): JSONObject {
        val systemParts = mutableListOf<String>()
        var stableSystemBlockIndex: Int? = null
        val anthropicMessages = JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "system" -> providerMessageText(message.opt("content"))
                    .takeIf { it.isNotBlank() }
                    ?.let { text ->
                        systemParts.add(text)
                        if (message.optBoolean(AnthropicPromptCaching.SYSTEM_BOUNDARY_KEY)) {
                            stableSystemBlockIndex = systemParts.lastIndex
                        }
                    }
                "user" -> anthropicMessages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", convertUserContent(message.opt("content")))
                )
                "assistant" -> anthropicMessages.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", convertAssistantContent(message))
                )
                "tool" -> anthropicMessages.put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", message.optString("tool_call_id"))
                                    .put("content", message.optString("content"))
                            )
                        )
                )
            }
        }

        return JSONObject()
            .put("model", config.model)
            .put("max_tokens", DEFAULT_MAX_TOKENS)
            .put("stream", true)
            // Anthropic 要求同一批 tool_result 合并在紧随 assistant 的一条 user 里。
            .put("messages", AnthropicMessageSequence.normalize(anthropicMessages))
            .also { request ->
                if (systemParts.isNotEmpty()) {
                    request.put("system", JSONArray().also { blocks ->
                        systemParts.forEach { text ->
                            blocks.put(JSONObject().put("type", "text").put("text", text))
                        }
                    })
                }
                convertTools(tools)?.let { request.put("tools", it) }
                RequestBodyMerge.mergeCustomBody(request, config.customBody)
                // Relay affinity reads native metadata, not the session-id HTTP header.
                // Use the durable conversation identity; never generate a per-run anchor.
                AnthropicSessionAffinity.applyDefault(request, sessionId)
                request.remove("eta_media_reasoning")
                request.remove(ImageRequestParameters.CONFIG_KEY) // Local image settings never enter text protocols.
                ProviderReasoning.applyAnthropicRequest(request, config)
                config.summaryOutputLimit?.let { request.put("max_tokens", it) }
                // customBody 可以覆盖 messages；覆盖后的正文同样不得绕过配对校验。
                AnthropicMessageSequence.validateFinalRequest(request)
                // Apply to the merged protocol body; explicit caller cache settings win.
                AnthropicPromptCaching.applyDefaults(
                    request,
                    stableSystemBlockIndex.takeUnless { config.customBody.any { it.key == "system" } },
                )
            }
    }

    private fun convertUserContent(content: Any?): JSONArray =
        when (content) {
            is JSONArray -> JSONArray().also { out ->
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    when (item.optString("type")) {
                        "text" -> item.optString("text")
                            .takeIf { it.isNotBlank() }
                            ?.let { out.put(JSONObject().put("type", "text").put("text", it)) }
                        "image_url" -> convertImageBlock(item)?.let(out::put)
                    }
                }
                if (out.length() == 0) out.put(JSONObject().put("type", "text").put("text", ""))
            }
            else -> JSONArray().put(JSONObject().put("type", "text").put("text", providerMessageText(content)))
        }

    private fun convertAssistantContent(message: JSONObject): JSONArray {
        val content = JSONArray()
        providerMessageText(message.opt("content"))
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { content.put(JSONObject().put("type", "text").put("text", it)) }
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(index) ?: continue
                val function = toolCall.optJSONObject("function") ?: continue
                content.put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", toolCall.optString("id").ifBlank { "tool_call_$index" })
                        .put("name", function.optString("name"))
                        .put("input", parseJsonObject(function.optString("arguments")))
                )
            }
        }
        if (content.length() == 0) {
            content.put(JSONObject().put("type", "text").put("text", ""))
        }
        return content
    }

    internal fun convertTools(tools: JSONArray): JSONArray? {
        if (tools.length() == 0) return null
        val converted = JSONArray()
        for (index in 0 until tools.length()) {
            val item = tools.optJSONObject(index) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isBlank()) continue
            converted.put(
                JSONObject()
                    .put("name", name)
                    .put("description", function.optString("description"))
                    .put("input_schema", function.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
            )
        }
        return converted.takeIf { it.length() > 0 }
    }

    private fun convertImageBlock(item: JSONObject): JSONObject? {
        val url = item.optJSONObject("image_url")?.optString("url").orEmpty()
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma <= 5) return null
        val meta = url.substring(5, comma)
        val mediaType = meta.substringBefore(';').ifBlank { "image/png" }
        val data = url.substring(comma + 1)
        return JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("type", "base64")
                    .put("media_type", mediaType)
                    .put("data", data)
            )
    }

    private fun readStreamingAssistantMessage(
        request: Request,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
        requireTerminal: Boolean,
    ): JSONObject {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val blocks = linkedMapOf<Int, AnthropicBlock>()
        var sawMessageStop = false
        var finishReason: String? = null
        var usage: AgentTokenUsage? = null
        var openingUsage: AgentTokenUsage? = null
        var publishedMeasurement = false

        fun dispatch(event: String, payload: String) {
            val result = processEvent(
                event = event,
                payload = payload,
                blocks = blocks,
                content = content,
                reasoning = reasoning,
                onEvent = onEvent
            )
            if (result.messageStop && (!requireTerminal || payload != "[DONE]")) sawMessageStop = true
            result.finishReason?.let { finishReason = it }
            result.usage?.let { incoming ->
                val opening = AgentInitialUsageReceipt.isOpening(incoming)
                // The opening receipt of a stream is a pre-cache estimate, not the request's
                // measurement; it is merged and published when the stream ends instead of
                // dropping the ring to it and moving it back one event later.
                if (opening) {
                    openingUsage = openingUsage?.let { AgentInitialUsageReceipt.merge(it, incoming) } ?: incoming
                } else {
                    val merged = openingUsage?.let { AgentInitialUsageReceipt.merge(it, incoming) }
                        ?: incoming
                    openingUsage = null
                    publishedMeasurement = true
                    usage = merged
                    onEvent(ProviderEvent.Usage(merged))
                }
            }
        }

        AgentSseClient.collect(
            request = request,
            runController = runController,
            onOpen = { code -> onEvent(ProviderEvent.ResponseHeaders(code)) },
            onJson = { payload ->
                val message = AgentResponseFormat.parseJsonObject(payload)
                if (message.has("error") || message.optString("type") == "error") {
                    parseUsage(message.optJSONObject("usage"))?.let { onEvent(ProviderEvent.Usage(it)) }
                    dispatch("error", JSONObject(message.toString()).put("type", "error").toString())
                }
                val messageBlocks = message.optJSONArray("content")
                if (message.optString("type") != "message" || messageBlocks == null) {
                    throw AgentModelFailure.unexpectedResponse(200, "application/json", "")
                }
                // Reuse the SSE block consumer rather than creating a second normalization path.
                dispatch("message_start", JSONObject().put("message", message).toString())
                for (index in 0 until messageBlocks.length()) {
                    val block = messageBlocks.optJSONObject(index) ?: continue
                    dispatch("content_block_start", JSONObject().put("index", index).put("content_block", block).toString())
                    val delta = when (block.optString("type")) {
                        "text" -> JSONObject().put("type", "text_delta").put("text", block.optString("text"))
                        "thinking" -> JSONObject().put("type", "thinking_delta").put("thinking", block.optString("thinking"))
                        // Nonempty input was already appended by content_block_start.
                        // Empty input needs one delta so BlockEnd matches the final tool call.
                        "tool_use" -> block.optJSONObject("input")?.takeIf { it.length() == 0 }
                            ?.let { JSONObject().put("type", "input_json_delta").put("partial_json", "{}") }
                        else -> null
                    }
                    delta?.let { dispatch("content_block_delta", JSONObject().put("index", index).put("delta", it).toString()) }
                    dispatch("content_block_stop", JSONObject().put("index", index).toString())
                }
                dispatch("message_delta", JSONObject()
                    .put("delta", JSONObject().put("stop_reason", message.opt("stop_reason")))
                    .put("usage", message.optJSONObject("usage"))
                    .toString())
                dispatch("message_stop", "{}")
            },
            onEvent = sseEvent@{ _, type, data ->
                val payload = data.trim()
                if (payload.isBlank()) return@sseEvent
                dispatch(type.orEmpty(), payload)
                if (sawMessageStop) finish()
            },
            shouldIgnoreFailure = {
                val hasToolCalls = blocks.values.any { it.type == "tool_use" && it.name.isNotBlank() }
                sawMessageStop || (!requireTerminal &&
                    (content.isNotBlank() || reasoning.isNotBlank() || hasToolCalls))
            },
        )

        // The request ended without a later receipt, so the deferred opening value is its only
        // bill; publish it unchanged instead of leaving the previous request's value on screen.
        if (!publishedMeasurement) openingUsage?.let { deferred ->
            usage = deferred
            onEvent(ProviderEvent.Usage(deferred))
        }

        if (!sawMessageStop && (runController.hasPendingSteering || runController.hasPausedInterrupt)) {
            return interruptedAssistantMessage(content.toString(), reasoning.toString())
        }

        if (requireTerminal && !sawMessageStop) {
            throw AgentModelFailure.incompleteStream("Anthropic 响应缺少结束事件；保留正文并按重连策略继续请求。")
        }

        if (!sawMessageStop) {
            val hasToolCalls = blocks.values.any { it.type == "tool_use" && it.name.isNotBlank() }
            if (content.isBlank() && reasoning.isBlank() && !hasToolCalls) {
                throw AgentModelFailure.incompleteStream("Anthropic SSE 流未正常结束")
            }
            if (finishReason.isNullOrBlank()) {
                finishReason = if (hasToolCalls) "tool_calls" else "end_turn"
            }
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoning.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
                val toolCalls = blocks.values
                    .filter { it.type == "tool_use" && it.name.isNotBlank() }
                    .sortedBy { it.index }
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.forEachIndexed { position, block ->
                                array.put(block.toToolCallJson(position))
                            }
                        }
                    )
                }
            }
    }

    private fun processEvent(
        event: String,
        payload: String,
        blocks: MutableMap<Int, AnthropicBlock>,
        content: StringBuilder,
        reasoning: StringBuilder,
        onEvent: (ProviderEvent) -> Unit
    ): EventResult {
        if (payload == "[DONE]") return EventResult(messageStop = true)
        val json = JSONObject(payload)
        val type = json.optString("type").ifBlank { event }
        return when (type) {
            "error" -> throw AgentModelFailure.stream(
                json.optJSONObject("error") ?: JSONObject(),
                "Anthropic SSE 返回错误",
            )
            "message_start" -> EventResult(usage = parseUsage(json.optJSONObject("message")?.optJSONObject("usage")))
            "content_block_start" -> {
                val index = json.optInt("index")
                val block = json.optJSONObject("content_block") ?: JSONObject()
                val item = AnthropicBlock(
                    index = index,
                    type = block.optString("type"),
                    id = block.optString("id"),
                    name = block.optString("name")
                )
                block.optJSONObject("input")
                    ?.takeIf { it.length() > 0 }
                    ?.let { item.arguments.append(it.toString()) }
                blocks[index] = item
                when (item.type) {
                    "text" -> onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, index))
                    "thinking" -> onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, index))
                    "tool_use" -> onEvent(
                        ProviderEvent.BlockStart(
                            kind = AssistantBlockKind.TOOL_CALL,
                            index = index,
                            blockId = item.id.ifBlank { null },
                            name = item.name.ifBlank { null },
                        )
                    )
                }
                EventResult()
            }
            "content_block_delta" -> {
                val index = json.optInt("index")
                val delta = json.optJSONObject("delta") ?: JSONObject()
                when (delta.optString("type")) {
                    "text_delta" -> {
                        val text = delta.optString("text")
                        if (text.isNotEmpty()) {
                            blocks.getOrPut(index) { AnthropicBlock(index = index, type = "text") }
                                .text
                                .append(text)
                            content.append(text)
                            onEvent(
                                ProviderEvent.BlockDelta(
                                    kind = AssistantBlockKind.TEXT,
                                    index = index,
                                    delta = text,
                                )
                            )
                        }
                    }
                    "thinking_delta" -> {
                        val text = delta.optString("thinking")
                        if (text.isNotEmpty()) {
                            blocks.getOrPut(index) { AnthropicBlock(index = index, type = "thinking") }
                                .thinking
                                .append(text)
                            reasoning.append(text)
                            onEvent(
                                ProviderEvent.BlockDelta(
                                    kind = AssistantBlockKind.THINKING,
                                    index = index,
                                    delta = text,
                                )
                            )
                        }
                    }
                    "input_json_delta" -> {
                        val partial = delta.optString("partial_json")
                        if (partial.isNotEmpty()) {
                            val block = blocks.getOrPut(index) { AnthropicBlock(index = index, type = "tool_use") }
                            block.arguments.append(partial)
                            onEvent(
                                ProviderEvent.BlockDelta(
                                    kind = AssistantBlockKind.TOOL_CALL,
                                    index = index,
                                    delta = partial,
                                )
                            )
                        }
                    }
                }
                EventResult()
            }
            "content_block_stop" -> {
                val index = json.optInt("index")
                val block = blocks[index] ?: return EventResult()
                val kind = when (block.type) {
                    "text" -> AssistantBlockKind.TEXT
                    "thinking" -> AssistantBlockKind.THINKING
                    "tool_use" -> AssistantBlockKind.TOOL_CALL
                    else -> return EventResult()
                }
                onEvent(
                    ProviderEvent.BlockEnd(
                        kind = kind,
                        index = index,
                        blockId = block.id.ifBlank { null },
                        name = block.name.ifBlank { null },
                        content = block.content(),
                    )
                )
                EventResult()
            }
            "message_delta" -> EventResult(
                finishReason = json.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() },
                usage = parseUsage(json.optJSONObject("usage"))
            )
            "message_stop" -> EventResult(messageStop = true)
            else -> EventResult()
        }
    }

    private data class AnthropicBlock(
        val index: Int,
        var type: String = "",
        var id: String = "",
        var name: String = "",
        val text: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun content(): String =
            when (type) {
                "text" -> text.toString()
                "thinking" -> thinking.toString()
                else -> arguments.toString()
            }

        fun toToolCallJson(position: Int): JSONObject =
            JSONObject()
                .put("id", id.ifBlank { "tool_call_$position" })
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("arguments", arguments.toString().ifBlank { "{}" })
                )
    }

    private data class EventResult(
        val messageStop: Boolean = false,
        val finishReason: String? = null,
        val usage: AgentTokenUsage? = null
    )

    // Anthropic keeps the cache subsets outside input_tokens; normalise to the
    // OpenAI-style total prompt so occupancy and billing share one meaning.
    private fun parseUsage(usage: JSONObject?): AgentTokenUsage? {
        usage ?: return null
        return AnthropicUsageTotals.parse { key -> usage.firstInt(key) }
    }

    private fun parseJsonObject(raw: String): JSONObject =
        runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            when (val raw = opt(key)) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
        }


}
