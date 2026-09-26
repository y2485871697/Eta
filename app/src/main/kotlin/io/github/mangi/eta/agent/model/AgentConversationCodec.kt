package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.media.isVideoMedia

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Provider JSON 与 Eta 稳定会话 DTO 之间的唯一转换和容量边界。 */
internal object AgentConversationCodec {
    internal const val MAX_DRAIN_TRANSCRIPT_CHARS = 16_000
    internal const val MAX_STORAGE_TRANSCRIPT_CHARS = 1_000_000
    // 结果 transcript 走文件描述符，与归档/检查点对齐，不再受 Binder 事务大小限制。
    internal const val MAX_IPC_TRANSCRIPT_CHARS = MAX_STORAGE_TRANSCRIPT_CHARS
    // Room 检查点与运行归档同级，避免会话在远低于模型窗口时被截断。
    internal const val MAX_CONVERSATION_CHECKPOINT_CHARS = MAX_STORAGE_TRANSCRIPT_CHARS

    private const val MAX_CONTENT_CHARS = 64_000
    private const val MAX_REASONING_CHARS = 64_000
    private const val MAX_TOOL_ARGUMENT_CHARS = 32_000
    private const val MAX_TOOL_CALLS_PER_MESSAGE = 64
    private const val IMAGE_OMITTED_TEXT = "[图片观察已在当前回合使用，未写入持久会话]"
    internal const val IMAGE_FILE_TYPE = "image_file"
    internal const val VIDEO_FILE_TYPE = "video_file"
    private const val SENSITIVE_TOOL_OMITTED_TEXT =
        "[敏感工具参数与原始结果仅供当前回合使用，未写入持久会话]"
    private const val COMPACTION_NOTICE =
        "[Eta 上下文提示：此前部分 assistant/tool 记录因跨进程或持久化容量上限已压缩，请勿假定缺失步骤未执行。]"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encodeTranscriptForIpc(messages: List<AgentModelClient.ConversationMessage>): String =
        encodeBounded(messages, MAX_IPC_TRANSCRIPT_CHARS)

    fun encodeTranscriptForDrain(messages: List<AgentModelClient.ConversationMessage>): String =
        encodeBounded(messages.map { it.copy(turnId = "") }, MAX_DRAIN_TRANSCRIPT_CHARS)

    fun encodeTranscriptForStorage(messages: List<AgentModelClient.ConversationMessage>): String =
        encodeBounded(messages, MAX_STORAGE_TRANSCRIPT_CHARS)

    fun encodeConversationCheckpoint(messages: List<AgentModelClient.ConversationMessage>): String =
        encodeBounded(messages, MAX_CONVERSATION_CHECKPOINT_CHARS)

    fun messagesForIpc(
        messages: List<AgentModelClient.ConversationMessage>,
    ): List<AgentModelClient.ConversationMessage> =
        decodeTranscript(encodeTranscriptForIpc(messages))

    fun decodeTranscript(raw: String?): List<AgentModelClient.ConversationMessage> =
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString<List<AgentModelClient.ConversationMessage>>(raw)
            }.getOrDefault(emptyList())
        }

    fun toJsonObject(message: AgentModelClient.ConversationMessage): JSONObject =
        JSONObject()
            .put("role", message.role)
            .also { target ->
                if (message.turnId.isNotBlank()) target.put(AgentTurnIdentity.JSON_KEY, message.turnId)
                when {
                    message.contentJson.isNotBlank() ->
                        target.put("content", JSONTokener(message.contentJson).nextValue())
                    else -> target.put("content", message.content)
                }
                if (message.toolCallId.isNotBlank()) {
                    target.put("tool_call_id", message.toolCallId)
                }
                if (message.reasoningContent.isNotBlank()) {
                    target.put("reasoning_content", message.reasoningContent)
                }
                ResponsesReasoningState.sanitize(message.responsesReasoningJson).takeIf { it.isNotBlank() }?.let {
                    target.put(ResponsesReasoningState.KEY, JSONObject(it))
                }
                if (message.toolCallsJson.isNotBlank()) {
                    target.put("tool_calls", JSONTokener(message.toolCallsJson).nextValue())
                }
            }

    fun fromJsonObject(message: JSONObject): AgentModelClient.ConversationMessage {
        val contentValue = message.opt("content")
        return AgentModelClient.ConversationMessage(
            role = message.optString("role"),
            turnId = message.optString(AgentTurnIdentity.JSON_KEY).take(128),
            content = (contentValue as? String).orEmpty(),
            contentJson = if (
                contentValue == null ||
                contentValue == JSONObject.NULL ||
                contentValue is String
            ) {
                ""
            } else {
                contentValue.toString()
            },
            toolCallId = message.optString("tool_call_id"),
            reasoningContent = message.optReasoningContent(),
            responsesReasoningJson = ResponsesReasoningState.sanitize(message.optJSONObject(ResponsesReasoningState.KEY)?.toString().orEmpty()),
            toolCallsJson = message.optJSONArray("tool_calls")?.toString().orEmpty(),
        )
    }

    fun userTextMessage(text: String): JSONObject =
        JSONObject()
            .put("role", "user")
            .put("content", text)

    fun userMessage(
        text: String,
        images: List<AgentModelClient.ModelImage>,
    ): JSONObject {
        if (images.isEmpty()) return userTextMessage(text)

        val content = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text)
        )
        images.forEach { image ->
            require(image.reference.isProviderMediaReference()) {
                "模型图片尚未在 Agent Runtime 中物化"
            }
            if (image.isVideoMedia()) {
                content.put(
                    JSONObject()
                        .put("type", "video_url")
                        .put("video_url", JSONObject().put("url", image.reference)),
                )
            } else {
                content.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", image.reference)),
                )
            }
        }
        return JSONObject()
            .put("role", "user")
            .put("content", content)
    }

    data class PersistedImage(
        val path: String,
        val mimeType: String,
        val displayName: String,
    )

    fun userPersistedImageMessage(
        text: String,
        images: List<PersistedImage>,
    ): JSONObject {
        if (images.isEmpty()) return userTextMessage(text)
        val content = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text),
        )
        images.forEach { image ->
            val type = if (image.mimeType.startsWith("video/", ignoreCase = true)) {
                VIDEO_FILE_TYPE
            } else {
                IMAGE_FILE_TYPE
            }
            content.put(
                JSONObject()
                    .put("type", type)
                    .put("path", image.path)
                    .put("mime", image.mimeType)
                    .put("name", image.displayName),
            )
        }
        return JSONObject()
            .put("role", "user")
            .put("content", content)
    }

    fun persistedImageSources(message: AgentModelClient.ConversationMessage): List<String> {
        if (!message.role.equals("user", ignoreCase = true) || message.contentJson.isBlank()) {
            return emptyList()
        }
        val content = runCatching { JSONTokener(message.contentJson).nextValue() }.getOrNull() as? JSONArray
            ?: return emptyList()
        return buildList {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    IMAGE_FILE_TYPE, VIDEO_FILE_TYPE -> {
                        item.optString("path").trim()
                            .takeIf { it.startsWith("/") }
                            ?.let(::add)
                    }
                    "image_url" -> {
                        item.optJSONObject("image_url")
                            ?.optString("url")
                            ?.trim()
                            ?.takeIf { it.isNotBlank() && it.isDirectPreviewSource() }
                            ?.let(::add)
                    }
                }
            }
        }
    }

    fun userVisibleText(message: AgentModelClient.ConversationMessage): String {
        if (message.content.isNotBlank()) return message.content.trim()
        if (message.contentJson.isBlank()) return ""
        val value = runCatching { JSONTokener(message.contentJson).nextValue() }.getOrNull() ?: return ""
        return extractUserText(value).trim()
    }

    private fun extractUserText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val text = extractUserText(value.opt(index)).trim()
                if (text.isEmpty()) continue
                if (isNotEmpty()) append('\n')
                append(text)
            }
        }
        is JSONObject -> {
            val type = value.optString("type")
            if (type.isEmpty() || type == "text") value.optString("text") else ""
        }
        else -> ""
    }

    private fun String.isDirectPreviewSource(): Boolean =
        startsWith("https://", ignoreCase = true) ||
            startsWith("http://", ignoreCase = true) ||
            startsWith("data:image/", ignoreCase = true) ||
            startsWith("/")

    private fun String.isProviderImageReference(): Boolean =
        startsWith("https://", ignoreCase = true) ||
            startsWith("http://", ignoreCase = true) ||
            startsWith("data:image/", ignoreCase = true)

    private fun String.isProviderMediaReference(): Boolean =
        isProviderImageReference() || startsWith("data:video/", ignoreCase = true)

    fun assistantHistoryMessage(
        source: JSONObject,
        toolCalls: List<AgentModelClient.ToolCall>,
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", source.opt("content") ?: JSONObject.NULL)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.forEach { call -> array.put(call.toHistoryJson()) }
                        },
                    )
                }
                source.optReasoningContent().takeIf { it.isNotEmpty() }?.let { reasoning ->
                    message.put("reasoning_content", reasoning)
                }
                ResponsesEphemeralState.copyOutputItems(source, message)
                ResponsesReasoningState.copy(source, message)
            }

    fun toolResultMessage(
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ): JSONObject =
        JSONObject()
            .put("role", "tool")
            .put("tool_call_id", toolCall.id)
            .put("content", result.content)

    fun parseToolCalls(message: JSONObject): List<AgentModelClient.ToolCall> {
        val rawCalls = message.optJSONArray("tool_calls") ?: return emptyList()
        val usedIds = mutableSetOf<String>()
        return buildList {
            for (index in 0 until rawCalls.length()) {
                val rawCall = rawCalls.optJSONObject(index) ?: JSONObject()
                val function = rawCall.optJSONObject("function")
                val arguments = function?.opt("arguments")
                val candidateId = rawCall.optString("id").ifBlank { "tool_call_$index" }
                val stableId = if (usedIds.add(candidateId)) {
                    candidateId
                } else {
                    generateSequence(1) { it + 1 }
                        .map { suffix -> "${candidateId}_$suffix" }
                        .first(usedIds::add)
                }
                add(
                    AgentModelClient.ToolCall(
                        id = stableId,
                        name = function?.optString("name")?.trim().orEmpty().ifBlank { "unknown_tool" },
                        argumentsJson = when (arguments) {
                            is JSONObject -> arguments.toString()
                            is String -> arguments.ifBlank { "{}" }
                            else -> "{}"
                        },
                    )
                )
            }
        }
    }

    private fun AgentModelClient.ToolCall.toHistoryJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", argumentsJson),
            )

    fun transcript(
        messages: JSONArray,
        startIndex: Int,
        sensitiveToolCallIds: Set<String> = emptySet(),
    ): List<AgentModelClient.ConversationMessage> =
        buildList {
            for (index in startIndex until messages.length()) {
                messages.optJSONObject(index)
                    ?.let { redactSensitiveToolData(it, sensitiveToolCallIds) }
                    ?.let(::fromJsonObject)
                    ?.let(::sanitizeMessage)
                    ?.let(::add)
            }
        }

    fun redactSensitiveMessages(
        messages: List<AgentModelClient.ConversationMessage>,
        sensitiveToolCallIds: Set<String>,
    ): List<AgentModelClient.ConversationMessage> {
        if (sensitiveToolCallIds.isEmpty()) return messages
        return transcript(
            JSONArray().also { array -> messages.forEach { array.put(toJsonObject(it)) } },
            0,
            sensitiveToolCallIds,
        )
    }

    private fun redactSensitiveToolData(
        source: JSONObject,
        sensitiveToolCallIds: Set<String>,
    ): JSONObject {
        if (sensitiveToolCallIds.isEmpty()) return source
        val copy = JSONObject(source.toString())
        if (
            copy.optString("role") == "tool" &&
            copy.optString("tool_call_id") in sensitiveToolCallIds
        ) {
            copy.put("content", SENSITIVE_TOOL_OMITTED_TEXT)
        }
        val calls = copy.optJSONArray("tool_calls") ?: return copy
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            if (call.optString("id") !in sensitiveToolCallIds) continue
            call.optJSONObject("function")
                ?.put("arguments", JSONObject().put("redacted", true).toString())
        }
        return copy
    }

    fun durableMessage(message: JSONObject): AgentModelClient.ConversationMessage =
        sanitizeMessage(fromJsonObject(message))

    private fun encodeBounded(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
    ): String {
        val bounded = messages.map(::sanitizeMessage)
        // Store lengths only; discarded histories never need a combined JSON string.
        // Json uses compact output, so arrays add two brackets and n-1 commas.
        val prefix = LongArray(bounded.size + 1)
        bounded.forEachIndexed { index, message ->
            prefix[index + 1] = prefix[index] + json.encodeToString(message).length + 1L
        }
        fun suffixLength(start: Int): Long =
            if (start == bounded.size) 2L else prefix.last() - prefix[start] + 1L
        if (suffixLength(0) <= maxChars) return json.encodeToString(bounded)
        val protectedTurnId = bounded.lastOrNull { it.turnId.isNotBlank() }?.turnId.orEmpty()
        val protectedCount = if (protectedTurnId.isBlank()) 0 else bounded.indexOfFirst { it.turnId == protectedTurnId }
            .let { start -> if (start < 0) 0 else bounded.size - start }
        if (protectedCount > 0) {
            require(suffixLength(bounded.size - protectedCount) <= maxChars) {
                "受保护会话超过持久化容量上限；未截断或丢弃原消息，请压缩历史后重试"
            }
        }

        val notice = AgentModelClient.ConversationMessage(role = "system", content = COMPACTION_NOTICE)
        val noticeLength = json.encodeToString(notice).length.toLong()
        val keep = protectedCount.coerceAtLeast(1)
        var start = 0
        while (bounded.size - start > keep) {
            start++
            while (bounded.size - start > keep && bounded[start].role == "tool") start++
            if (suffixLength(start) + noticeLength + 1L <= maxChars) {
                return json.encodeToString(listOf(notice) + bounded.subList(start, bounded.size))
            }
        }
        if (protectedCount > 0) {
            if (suffixLength(start) <= maxChars) return json.encodeToString(bounded.subList(start, bounded.size))
            error("受保护会话超过持久化容量上限；未截断或丢弃原消息，请压缩历史后重试")
        }

        val last = bounded.lastOrNull() ?: return "[]"
        val compacted = last.copy(
            content = last.content.take(maxChars / 4),
            contentJson = "",
            reasoningContent = last.reasoningContent.take(maxChars / 4),
            toolCallsJson = "",
            responsesReasoningJson = "",
        )
        return json.encodeToString(listOf(notice, compacted))
            .takeIf { it.length <= maxChars }
            ?: json.encodeToString(listOf(notice)).takeIf { it.length <= maxChars }
            ?: "[]"
    }

    private fun sanitizeMessage(
        message: AgentModelClient.ConversationMessage,
    ): AgentModelClient.ConversationMessage =
        message.copy(
            role = message.role.take(32),
            content = when {
                message.turnId.isNotBlank() -> message.content
                AgentFileReferencePromptCodec.isModelEnvelope(message.content) ->
                    message.content.take(AgentFileReferencePromptCodec.MAX_ENVELOPE_CHARS)
                else -> message.content.take(MAX_CONTENT_CHARS)
            },
            contentJson = sanitizeContentJson(message.contentJson, message.turnId.isNotBlank()),
            toolCallId = if (message.turnId.isNotBlank()) message.toolCallId else message.toolCallId.take(256),
            reasoningContent = if (message.turnId.isNotBlank()) message.reasoningContent else message.reasoningContent.take(MAX_REASONING_CHARS),
            toolCallsJson = sanitizeToolCallsJson(message.toolCallsJson, message.turnId.isNotBlank()),
            responsesReasoningJson = ResponsesReasoningState.sanitize(message.responsesReasoningJson),
            turnId = message.turnId.take(128),
        )

    private fun sanitizeContentJson(raw: String, preserveText: Boolean = false): String {
        if (raw.isBlank()) return ""
        val content = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val sanitized = when (content) {
            is JSONArray -> sanitizeContentArray(content, preserveText)
            is JSONObject -> sanitizeContentObject(content, preserveText)
            else -> return ""
        }
        return sanitized.toString().takeIf { preserveText || it.length <= MAX_CONTENT_CHARS }
            ?: JSONArray()
                .put(JSONObject().put("type", "text").put("text", IMAGE_OMITTED_TEXT))
                .toString()
    }

    private fun sanitizeContentArray(source: JSONArray, preserveText: Boolean = false): JSONArray {
        val target = JSONArray()
        var omittedImage = false
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            when {
                item.optString("type") == IMAGE_FILE_TYPE ||
                    item.optString("type") == VIDEO_FILE_TYPE -> {
                    val path = item.optString("path")
                    val type = item.optString("type")
                    if (path.startsWith("/") && path.length <= 1_024) {
                        target.put(
                            JSONObject()
                                .put("type", type)
                                .put("path", path)
                                .put("mime", item.optString("mime").take(128))
                                .put("name", item.optString("name").take(80)),
                        )
                    }
                }
                item.optString("type") == "image_url" ||
                    item.optString("type") == "video_url" ||
                    item.has("source") -> {
                    omittedImage = true
                }
                else -> target.put(sanitizeContentObject(item, preserveText))
            }
        }
        if (omittedImage) {
            target.put(JSONObject().put("type", "text").put("text", IMAGE_OMITTED_TEXT))
        }
        return target
    }

    private fun sanitizeContentObject(source: JSONObject, preserveText: Boolean = false): JSONObject =
        JSONObject(source.toString()).also { target ->
            target.remove("image_url")
            target.remove("source")
            if (!preserveText && target.has("text")) {
                target.put("text", target.optString("text").take(MAX_CONTENT_CHARS / 2))
            }
        }

    private fun sanitizeToolCallsJson(raw: String, preserveText: Boolean = false): String {
        if (raw.isBlank()) return ""
        if (preserveText) {
            if (runCatching { JSONArray(raw) }.isSuccess) return raw
        }
        val source = runCatching { JSONArray(raw) }.getOrNull() ?: return ""
        val target = JSONArray()
        for (index in 0 until minOf(source.length(), MAX_TOOL_CALLS_PER_MESSAGE)) {
            val call = source.optJSONObject(index) ?: continue
            val function = call.optJSONObject("function")
            val arguments = function?.optString("arguments").orEmpty()
            target.put(
                JSONObject()
                    .put("id", call.optString("id").take(256))
                    .put("type", call.optString("type").ifBlank { "function" })
                    .put(
                        "function",
                        JSONObject()
                            .put("name", function?.optString("name").orEmpty().take(128))
                            .put(
                                "arguments",
                                if (arguments.length <= MAX_TOOL_ARGUMENT_CHARS) {
                                    arguments
                                } else {
                                    JSONObject().put("_eta_truncated", true).toString()
                                },
                            ),
                    ),
            )
        }
        return target.toString()
    }
}

internal fun JSONObject.optReasoningContent(): String {
    optString("reasoning_content").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
    when (val reasoning = opt("reasoning")) {
        is String -> if (reasoning.isNotBlank() && reasoning != "null") return reasoning
        is JSONObject -> {
            reasoning.optString("content").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            reasoning.optString("text").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        }
    }
    return ""
}
