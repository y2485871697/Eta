package io.github.mangi.eta.ui.model

/**
 * Move a run's recorded body before its terminal notice. The original body slot
 * wins for repeated IDs, while the latest snapshot supplies its full payload.
 * User turns and messages belonging to other runs retain their order.
 */
internal fun normalizeTerminalRunMessages(
    runId: String,
    messages: List<AgentChatMessageUi>,
): List<AgentChatMessageUi> {
    if (runId.isBlank()) return messages
    return orderTerminalBodies(messages, runId)
}

/** Infer ownership from explicit run anchors, never from neighboring messages. */
internal fun List<AgentChatMessageUi>.withTerminalBodiesInOrder(): List<AgentChatMessageUi> =
    orderTerminalBodies(this, onlyRunId = null)

/** Reuse the exact ownership grammar; an assistant append cannot add an owner. */
internal fun List<AgentChatMessageUi>.canAppendAssistantAfterTerminalOrdering(
    appended: AgentMessageUi,
): Boolean {
    if (none { it is SystemNoticeMessageUi && it.code.isTerminal() }) return true
    val owners = knownTerminalRunIds()
    val owner = appended.ownerAmong(owners) ?: return true
    return none { it is SystemNoticeMessageUi && it.code.isTerminal() && it.ownerAmong(owners) == owner }
}

private class TerminalBodyOrder(
    val firstNoticeIndex: Int,
    val firstNoticeId: String,
    var latestNotice: SystemNoticeMessageUi,
) {
    val body = linkedMapOf<String, AgentChatMessageUi>()
    val firstBodyIndices = mutableMapOf<String, Int>()
}

/**
 * Resolve ownership once, then collect and emit all runs together. Previously every
 * terminal run rescanned the history, and every ownership check scanned every run.
 * For bounded message IDs this is O(messages), not O(messages * runs * runs).
 * Only list slots/IDs are indexed; message text, images and tool payloads are shared.
 */
private fun orderTerminalBodies(
    messages: List<AgentChatMessageUi>,
    onlyRunId: String?,
): List<AgentChatMessageUi> {
    if (messages.none { it is SystemNoticeMessageUi && it.code.isTerminal() }) return messages
    val owners = messages.knownTerminalRunIds()
    // A bare run ID cannot resolve an assistant ID that is also a longer run's ID.
    if (owners.isEmpty() || (onlyRunId != null && onlyRunId !in owners)) return messages
    // Most live events belong to a run that has no terminal notice yet. Historic
    // notices alone must not make us parse every body ID on each tool boundary.
    // Discover the applicable notices first, using the same ownership grammar.
    val runs = linkedMapOf<String, TerminalBodyOrder>()
    messages.forEachIndexed { index, message ->
        if (message !is SystemNoticeMessageUi || !message.code.isTerminal()) return@forEachIndexed
        val owner = message.ownerAmong(owners)
        if (owner != null && owner.isNotBlank() && (onlyRunId == null || owner == onlyRunId)) {
            val run = runs[owner]
            if (run == null) runs[owner] = TerminalBodyOrder(index, message.id, message)
            else run.latestNotice = message
        }
    }
    if (runs.isEmpty()) return messages
    val messageOwners = messages.map { it.ownerAmong(owners) }
    messages.forEachIndexed { index, message ->
        val run = runs[messageOwners[index]]
        if (run != null && message.isRunBody()) {
            run.firstBodyIndices.putIfAbsent(message.id, index)
            run.body[message.id] = message
        }
    }
    val ordered = ArrayList<AgentChatMessageUi>(messages.size)
    messages.forEachIndexed { index, message ->
        val run = runs[messageOwners[index]]
        when {
            run == null -> ordered.add(message)
            index == run.firstNoticeIndex -> {
                run.body.forEach { (id, value) ->
                    if (run.firstBodyIndices.getValue(id) > index) ordered.add(value)
                }
                ordered.add(run.latestNotice.copy(id = run.firstNoticeId))
            }
            message is SystemNoticeMessageUi && message.code.isTerminal() -> Unit
            message.isRunBody() -> {
                if (run.firstBodyIndices[message.id] == index && index < run.firstNoticeIndex) {
                    ordered.add(run.body.getValue(message.id))
                }
            }
            else -> ordered.add(message)
        }
    }
    return if (ordered == messages) messages else ordered
}

private fun List<AgentChatMessageUi>.knownTerminalRunIds(): Set<String> = buildSet {
    this@knownTerminalRunIds.forEach { message ->
        when (message) {
            is ThinkingMessageUi -> message.id.thinkingAnchor()?.let { add(it) }
            is ToolActivityMessageUi -> message.id.toolAnchor()?.let { add(it) }
            is UserMessageUi -> if (message.id.startsWith("user-") && !message.isSteerSupplement()) {
                add(message.id.removePrefix("user-"))
            }
            is SystemNoticeMessageUi -> when {
                message.id.startsWith("interrupted-") -> add(message.id.removePrefix("interrupted-"))
                message.id.startsWith("virtual-completed-") -> add(message.id.removePrefix("virtual-completed-"))
            }
            else -> Unit
        }
    }
    remove("")
}

private fun SystemNoticeCode.isTerminal(): Boolean = this != SystemNoticeCode.ModelRetry

private fun AgentChatMessageUi.isRunBody(): Boolean =
    this is AgentMessageUi || this is ThinkingMessageUi || this is ToolActivityMessageUi

private fun AgentChatMessageUi.ownerAmong(owners: Set<String>): String? = when (this) {
    is AgentMessageUi -> id.assistantOwnerAmong(owners)
    is SystemNoticeMessageUi -> when {
        !code.isTerminal() -> null
        id.startsWith("interrupted-") -> id.removePrefix("interrupted-").takeIf { it in owners }
        id.startsWith("virtual-completed-") -> id.removePrefix("virtual-completed-").takeIf { it in owners }
        else -> id.assistantOwnerAmong(owners)
    }
    is ThinkingMessageUi -> {
        val marker = id.lastIndexOf("-thinking-")
        if (marker >= 0 && id.validNumberSuffix(marker + THINKING_MARKER_LENGTH, thinking = true)) {
            id.substring(0, marker).takeIf { it in owners }
        } else null
    }
    is ToolActivityMessageUi -> id.toolOwnerAmong(owners)
    else -> null
}

/** Try only the ID's possible owners, longest first, rather than all historical runs. */
private fun String.assistantOwnerAmong(owners: Set<String>): String? {
    if (!startsWith("assistant-")) return null
    val value = removePrefix("assistant-")
    if (value in owners) return value
    var separator = value.lastIndexOf('-')
    repeat(2) {
        if (separator < 0) return null
        if (value.validNumberSuffix(separator + 1, thinking = false)) {
            val candidate = value.substring(0, separator)
            if (candidate in owners) return candidate
        }
        separator = value.lastIndexOf('-', separator - 1)
    }
    return null
}

private fun String.thinkingAnchor(): String? {
    val marker = lastIndexOf("-thinking-")
    return if (marker > 0 && none { it.isIdLineTerminator() } &&
        validNumberSuffix(marker + THINKING_MARKER_LENGTH, thinking = true)) substring(0, marker) else null
}

private fun String.toolAnchor(): String? {
    if (any { it.isIdLineTerminator() }) return null
    // Preserve the former non-greedy (.+?)-tool- anchor, including tool IDs which
    // themselves contain another -tool- marker.
    var marker = indexOf("-tool-", startIndex = 1)
    while (marker > 0) {
        if (validToolSuffix(marker + TOOL_MARKER_LENGTH)) return substring(0, marker)
        marker = indexOf("-tool-", startIndex = marker + 1)
    }
    return null
}

private fun String.toolOwnerAmong(owners: Set<String>): String? {
    var marker = lastIndexOf("-tool-")
    while (marker >= 0) {
        if (validToolSuffix(marker + TOOL_MARKER_LENGTH)) {
            val candidate = substring(0, marker)
            if (candidate in owners) return candidate
        }
        marker = lastIndexOf("-tool-", startIndex = marker - 1)
    }
    return null
}

// Equivalent to the old [0-9]+(?:-(?:[0-9]+|fallback/result/usage))? suffixes,
// without allocating a regex Matcher (and native Matcher input) for each message.
private fun String.validNumberSuffix(start: Int, thinking: Boolean): Boolean {
    var end = start
    while (end < length && this[end] in '0'..'9') end++
    if (end == start) return false
    if (end == length) return true
    if (this[end] != '-') return false
    val tail = end + 1
    if (thinking && regionMatches(tail, "fallback", 0, 8) && length - tail == 8) return true
    if (!thinking && ((regionMatches(tail, "result", 0, 6) && length - tail == 6) ||
            (regionMatches(tail, "usage", 0, 5) && length - tail == 5))) return true
    return tail < length && (tail until length).all { this[it] in '0'..'9' }
}

private fun String.validToolSuffix(start: Int): Boolean {
    var end = start
    while (end < length && this[end] in '0'..'9') end++
    if (end == start || end >= length || this[end] != '-' || end + 1 == length) return false
    return (end + 1 until length).none { this[it].isIdLineTerminator() }
}

private fun Char.isIdLineTerminator(): Boolean =
    this == '\n' || this == '\r' || this == '\u0085' || this == '\u2028' || this == '\u2029'

private const val THINKING_MARKER_LENGTH = 10
private const val TOOL_MARKER_LENGTH = 6
