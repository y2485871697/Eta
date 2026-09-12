package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities

internal object AgentContextCompactor {
    const val DEFAULT_KEEP_RECENT = 4
    const val DEFAULT_TARGET_TOKENS = 2000
    internal const val SUMMARY_PREFIX = "[Conversation summary]"
    internal const val SUMMARY_PREFIX_ZH = "[\u5bf9\u8bdd\u6458\u8981]"
    private const val MAX_MESSAGES_PER_CHUNK = 256

    data class Config(
        val targetTokens: Int = DEFAULT_TARGET_TOKENS,
        val keepRecentMessages: Int = DEFAULT_KEEP_RECENT,
        val compressModelConfig: AgentModelClient.ModelConfig? = null,
    )

    fun shouldCompress(
        history: List<AgentModelClient.ConversationMessage>,
        contextWindow: Int,
        keepRecentMessages: Int = DEFAULT_KEEP_RECENT,
        thresholdPercent: Int = 90,
        estimatedTokens: Int? = null,
    ): Boolean {
        if (recentKeepStartIndex(history, keepRecentMessages) <= 0) return false
        val estimated = estimatedTokens ?: history.sumOf { AgentContextBudget.countMessage(it) }
        return estimated >= contextWindow * thresholdPercent / 100
    }

    /**
     * 压缩 [messages] 中系统提示之后的对话，原地替换。
     * 成功返回压缩后的对话历史；未达阈值或失败返回 null。
     */
    fun compactMessages(
        messages: org.json.JSONArray,
        systemCount: Int,
        contextWindow: Int,
        config: Config,
        estimatedTokens: Int? = null,
        toolExecutor: AgentModelClient.ToolExecutor = NoOpToolExecutor,
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
    ): List<AgentModelClient.ConversationMessage>? {
        val historyStart = systemCount.coerceIn(0, messages.length())
        val history = AgentConversationCodec.transcript(messages, historyStart)
        val estimated = estimatedTokens ?: AgentContextBudget.estimate(messages)
        if (!shouldCompress(history, contextWindow, config.keepRecentMessages, estimatedTokens = estimated)) {
            return null
        }
        val compressed = compress(history, config, toolExecutor, capabilitiesProvider)
        if (compressed == history) return null
        rebuildConversation(messages, historyStart, compressed)
        return compressed
    }

    internal fun rebuildConversation(
        messages: org.json.JSONArray,
        systemCount: Int,
        history: List<AgentModelClient.ConversationMessage>,
    ) {
        val prefix = (0 until systemCount.coerceIn(0, messages.length())).map { index ->
            messages.getJSONObject(index)
        }
        while (messages.length() > 0) {
            messages.remove(messages.length() - 1)
        }
        prefix.forEach { messages.put(it) }
        history.forEach { message ->
            messages.put(AgentConversationCodec.toJsonObject(message))
        }
    }

    fun compress(
        history: List<AgentModelClient.ConversationMessage>,
        config: Config,
        toolExecutor: AgentModelClient.ToolExecutor = NoOpToolExecutor,
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
    ): List<AgentModelClient.ConversationMessage> {
        if (history.isEmpty()) return history
        val keepStart = recentKeepStartIndex(history, config.keepRecentMessages)
        if (keepStart <= 0) return history

        val messagesToCompress = history.subList(0, keepStart).toList()
        val messagesToKeep = history.subList(keepStart, history.size).toList()

        val chunks = splitMessages(messagesToCompress)
        val summaries = chunks.map { chunk ->
            compressChunk(chunk, config, toolExecutor, capabilitiesProvider)
        }

        val summaryMessages = summaries.map { summary ->
            AgentModelClient.ConversationMessage(
                role = "system",
                content = normalizeSummary(summary),
            )
        }

        return summaryMessages + messagesToKeep
    }

    /**
     * Index of the first message that must stay uncompressed.
     *
     * "Keep recent N" is N user turns: the last N user messages plus every
     * assistant/tool record that belongs to those turns. Summaries do not
     * consume the quota.
     */
    fun recentKeepStartIndex(
        history: List<AgentModelClient.ConversationMessage>,
        keepRecentMessages: Int,
    ): Int {
        val keep = keepRecentMessages.coerceAtLeast(0)
        if (keep == 0) return history.size
        var remaining = keep
        var start: Int? = null
        for (index in history.indices.reversed()) {
            if (!isKeepCountedUserMessage(history[index])) continue
            remaining--
            start = index
            if (remaining == 0) break
        }
        if (remaining > 0 || start == null) return 0
        return start
    }

    private fun isKeepCountedUserMessage(
        message: AgentModelClient.ConversationMessage,
    ): Boolean {
        if (isCompressionSummary(message)) return false
        return message.role.equals("user", ignoreCase = true)
    }

    internal fun isVisibleConversationMessage(
        message: AgentModelClient.ConversationMessage,
    ): Boolean {
        if (isCompressionSummary(message)) return false
        return when (message.role.lowercase()) {
            "user" -> true
            "assistant" ->
                message.content.isNotBlank() || message.reasoningContent.isNotBlank()
            else -> false
        }
    }

    internal fun displaySummary(content: String): String {
        val trimmed = content.trim()
        val withoutPrefix = when {
            trimmed.startsWith(SUMMARY_PREFIX) -> trimmed.removePrefix(SUMMARY_PREFIX)
            trimmed.startsWith(SUMMARY_PREFIX_ZH) -> trimmed.removePrefix(SUMMARY_PREFIX_ZH)
            trimmed.startsWith("[Summary of previous conversation]") ->
                trimmed.removePrefix("[Summary of previous conversation]")
            else -> trimmed
        }.trim()
        return withoutPrefix.removePrefix(":").trim()
    }

    internal fun isCompressionSummary(message: AgentModelClient.ConversationMessage): Boolean {
        if (message.role.equals("system", ignoreCase = true)) {
            val content = message.content.trimStart()
            return content.startsWith(SUMMARY_PREFIX) ||
                content.startsWith(SUMMARY_PREFIX_ZH) ||
                content.startsWith("[Summary") ||
                content.contains("previous conversation")
        }
        val content = message.content.trimStart()
        return content.startsWith(SUMMARY_PREFIX) ||
            content.startsWith(SUMMARY_PREFIX_ZH) ||
            content.startsWith("[Summary of previous conversation]")
    }

    private fun normalizeSummary(summary: String): String {
        val trimmed = summary.trim()
        return if (
            trimmed.startsWith(SUMMARY_PREFIX) ||
            trimmed.startsWith(SUMMARY_PREFIX_ZH) ||
            trimmed.startsWith("[Summary")
        ) {
            trimmed
        } else {
            "$SUMMARY_PREFIX_ZH\n$trimmed"
        }
    }

    private fun splitMessages(
        messages: List<AgentModelClient.ConversationMessage>,
    ): List<List<AgentModelClient.ConversationMessage>> {
        if (messages.size <= MAX_MESSAGES_PER_CHUNK) return listOf(messages)
        val mid = messages.size / 2
        val left = splitMessages(messages.subList(0, mid))
        val right = splitMessages(messages.subList(mid, messages.size))
        return left + right
    }

    private fun compressChunk(
        messages: List<AgentModelClient.ConversationMessage>,
        config: Config,
        toolExecutor: AgentModelClient.ToolExecutor,
        capabilitiesProvider: () -> AgentToolCapabilities,
    ): String {
        val contentToCompress = messages.joinToString("\n\n") { messageToSummaryText(it) }
        val prompt = buildCompressPrompt(contentToCompress, config.targetTokens)

        val modelConfig = config.compressModelConfig ?: throw IllegalStateException("\u672a\u914d\u7f6e\u538b\u7f29\u6a21\u578b")

        val response = AgentModelClient.complete(
            config = modelConfig,
            prompt = prompt,
            toolExecutor = toolExecutor,
            capabilitiesProvider = capabilitiesProvider,
        )

        return response.content.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("\u538b\u7f29\u5386\u53f2\u65f6\u6a21\u578b\u8fd4\u56de\u7a7a")
    }

    private fun messageToSummaryText(message: AgentModelClient.ConversationMessage): String {
        val role = message.role
        val content = message.content.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""
        val reasoning = message.reasoningContent.takeIf { it.isNotBlank() }?.let { "\n[thinking] $it" } ?: ""
        return "[$role]$content$reasoning".trim()
    }

    private fun buildCompressPrompt(content: String, targetTokens: Int): String {
        return """You are a conversation compression assistant. Compress the following conversation into a concise summary.

Requirements:
1. Preserve key facts, decisions, and important context that would be needed to continue the conversation.
2. Keep the summary in the same language as the original conversation.
3. Target approximately $targetTokens tokens.
4. Output the summary directly without any explanations or meta-commentary.
5. Format the summary as context information that can be used to continue the conversation.
6. Start the output with $SUMMARY_PREFIX_ZH or "$SUMMARY_PREFIX".

<conversation>
$content
</conversation>""".trimIndent()
    }

    private object NoOpToolExecutor : AgentModelClient.ToolExecutor {
        override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
            throw UnsupportedOperationException("\u538b\u7f29\u5386\u53f2\u65f6\u4e0d\u5e94\u8c03\u7528\u5de5\u5177")
        }
    }
}
