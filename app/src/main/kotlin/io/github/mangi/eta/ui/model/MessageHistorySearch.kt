package io.github.mangi.eta.ui.model

data class MessageSearchHit(
    val conversationId: String,
    val conversationTitle: String,
    val messageId: String,
    val snippet: String,
    val roleLabel: String,
)

data class MessageSearchRoleLabels(
    val user: String,
    val assistant: String,
    val thinking: String,
    val tool: String,
)

internal fun searchConversationMessages(
    conversations: Map<String, AgentChatUiState>,
    titles: Map<String, String>,
    updatedAt: Map<String, Long>,
    query: String,
    unnamedTitle: String,
    roleLabels: MessageSearchRoleLabels,
): List<MessageSearchHit> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()

    return conversations.entries
        .sortedWith(
            compareBy<Map.Entry<String, AgentChatUiState>> { updatedAt[it.key] ?: 0L }
                .thenBy { it.key },
        )
        .flatMap { (conversationId, state) ->
            val title = titles[conversationId].orEmpty().ifBlank { unnamedTitle }
            state.messages.mapIndexedNotNull { index, message ->
                val haystack = message.searchableText() ?: return@mapIndexedNotNull null
                if (!haystack.contains(needle, ignoreCase = true)) return@mapIndexedNotNull null
                val roleLabel = when (message) {
                    is UserMessageUi -> roleLabels.user
                    is AgentMessageUi -> roleLabels.assistant
                    is ThinkingMessageUi -> roleLabels.thinking
                    is ToolActivityMessageUi -> roleLabels.tool
                    else -> return@mapIndexedNotNull null
                }
                index to MessageSearchHit(
                    conversationId = conversationId,
                    conversationTitle = title,
                    messageId = message.id,
                    snippet = excerptAround(haystack, needle),
                    roleLabel = roleLabel,
                )
            }
        }
        .sortedWith(compareBy({ updatedAt[it.second.conversationId] ?: 0L }, { it.first }))
        .map { it.second }
}

private fun AgentChatMessageUi.searchableText(): String? = when (this) {
    is UserMessageUi -> content
    is AgentMessageUi -> content
    is ThinkingMessageUi -> content
    is ToolActivityMessageUi -> listOfNotNull(
        toolName,
        argumentsSummary,
        command,
        resultSummary,
    ).joinToString(" ")
    else -> null
}

internal fun excerptAround(text: String, query: String, radius: Int = 36): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    val index = collapsed.indexOf(query, ignoreCase = true)
    if (index < 0) return collapsed.take(80)
    val start = (index - radius).coerceAtLeast(0)
    val end = (index + query.length + radius).coerceAtMost(collapsed.length)
    return buildString {
        if (start > 0) append("…")
        append(collapsed.substring(start, end))
        if (end < collapsed.length) append("…")
    }
}
