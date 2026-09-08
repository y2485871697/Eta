package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities

internal object AgentContextCompactor {
    const val DEFAULT_KEEP_RECENT = 4
    const val DEFAULT_TARGET_TOKENS = 2000
    private const val MAX_MESSAGES_PER_CHUNK = 256

    data class Config(
        val targetTokens: Int = DEFAULT_TARGET_TOKENS,
        val keepRecentMessages: Int = DEFAULT_KEEP_RECENT,
        val compressModelConfig: AgentModelClient.ModelConfig? = null,
    )

    fun shouldCompress(
        history: List<AgentModelClient.ConversationMessage>,
        contextWindow: Int,
        thresholdPercent: Int = 80,
    ): Boolean {
        if (history.size <= DEFAULT_KEEP_RECENT) return false
        val estimated = history.sumOf { AgentContextBudget.countMessage(it) }
        return estimated >= contextWindow * thresholdPercent / 100
    }

    fun compress(
        history: List<AgentModelClient.ConversationMessage>,
        config: Config,
        toolExecutor: AgentModelClient.ToolExecutor = NoOpToolExecutor,
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
    ): List<AgentModelClient.ConversationMessage> {
        if (history.isEmpty()) return history
        if (history.size <= config.keepRecentMessages.coerceAtLeast(0)) return history

        val messagesToCompress = history.dropLast(config.keepRecentMessages)
        val messagesToKeep = history.takeLast(config.keepRecentMessages)

        val chunks = splitMessages(messagesToCompress)
        val summaries = chunks.map { chunk ->
            compressChunk(chunk, config, toolExecutor, capabilitiesProvider)
        }

        val summaryMessages = summaries.map { summary ->
            AgentModelClient.ConversationMessage(
                role = "user",
                content = summary,
            )
        }

        return summaryMessages + messagesToKeep
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

        val modelConfig = config.compressModelConfig ?: throw IllegalStateException("未配置压缩模型")

        val response = AgentModelClient.complete(
            config = modelConfig,
            prompt = prompt,
            toolExecutor = toolExecutor,
            capabilitiesProvider = capabilitiesProvider,
        )

        return response.content.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("压缩历史时模型返回空")
    }

    private fun messageToSummaryText(message: AgentModelClient.ConversationMessage): String {
        val role = message.role
        val content = message.content.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""
        val reasoning = message.reasoningContent.takeIf { it.isNotBlank() }?.let { "\n[思考] $it" } ?: ""
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
6. Start the output with a clear indicator that this is a summary (e.g., "[Summary of previous conversation]" or equivalent in the target language).

<conversation>
$content
</conversation>""".trimIndent()
    }

    private object NoOpToolExecutor : AgentModelClient.ToolExecutor {
        override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
            throw UnsupportedOperationException("压缩历史时不应调用工具")
        }
    }
}
