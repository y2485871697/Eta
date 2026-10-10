package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentIncrementalList
import io.github.mangi.eta.ui.model.incrementalSnapshot
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.canAppendAssistantAfterTerminalOrdering
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.isSteerSupplement

/**
 * Cache only the last projection, not individual historical messages forever.
 *
 * The fast path accepts payload replacement of AgentMessageUi at the SAME source
 * slot and ID, with every other source object unchanged. Terminal ordering reads
 * an assistant's ID/type, not its body or streaming flags, and assistants are
 * individual Message entries rather than work-group members. Every structural
 * change uses the full projection except a certified unique assistant append
 * that cannot reorder a terminal run. Non-assistant replacements stay authoritative.
 * Duplicate source IDs disable the fast path: ordering may select a later payload
 * or synthesize a notice ID, so a map keyed only by ID would be unsafe.
 */
internal class AgentTimelineProjectionCache(
    private val fullProjection: (List<AgentChatMessageUi>) -> List<AgentTimelineEntry> = { it.toTimelineEntries() },
) {
    private var source: List<AgentChatMessageUi>? = null
    private var entries: List<AgentTimelineEntry> = emptyList()
    private var sourceToEntry: IntArray? = null
    private var workSlots: Map<Int, WorkSlot>? = null

    private class WorkSlot(val entry: Int, val member: Int)

    fun project(messages: List<AgentChatMessageUi>): List<AgentTimelineEntry> {
        val previous = source
        val mapping = sourceToEntry
        // Appending cannot live inside the equal-size replacement branch. Keep
        // authoritative terminal ordering for late bodies and ambiguous inputs.
        if (previous != null && mapping != null && previous.size + 1 == messages.size) {
            val appended = messages.last() as? AgentMessageUi
            if (appended != null &&
                previous.indices.all { previous[it] === messages[it] } &&
                previous.none { it.id == appended.id } &&
                previous.canAppendAssistantAfterTerminalOrdering(appended)
            ) {
                val projected = (entries + AgentTimelineEntry.Message(appended)).incrementalSnapshot()
                val nextMapping = mapping.copyOf(messages.size)
                nextMapping[previous.size] = entries.size
                source = messages.incrementalSnapshot()
                entries = projected
                sourceToEntry = nextMapping
                return projected
            }
        }
        if (previous != null && mapping != null && previous.size == messages.size) {
            val changed = (messages as? AgentIncrementalList<AgentChatMessageUi>)?.singleReplacementFrom(previous)
            if (changed != null) {
                val old = previous[changed]
                val current = messages[changed]
                if (old is AgentMessageUi && current is AgentMessageUi && old.id == current.id && old.isStreaming && current.isStreaming &&
                    current.content.startsWith(old.content) && mapping[changed] >= 0) {
                    entries = entries.incrementalSnapshot().replacing(mapping[changed], AgentTimelineEntry.Message(current))
                    source = messages
                    return entries
                }
                // Work payloads (thinking text, tool status) never feed terminal ordering or
                // grouping: both read only IDs and types. Patch the member inside its group.
                val slot = workSlots?.get(changed)
                if (slot != null && old.id == current.id && old.isWorkPayloadOf(current)) {
                    val group = entries[slot.entry] as? AgentTimelineEntry.WorkProcess
                    if (group != null && group.messages.getOrNull(slot.member) === old) {
                        val members = group.messages.toMutableList().also { it[slot.member] = current }
                        entries = entries.incrementalSnapshot()
                            .replacing(slot.entry, group.copy(messages = members))
                        source = messages
                        return entries
                    }
                }
            }
            if (changed == null) {
                val changedIndices = ArrayList<Int>(1)
                var compatible = true
                for (index in messages.indices) {
                    val old = previous[index]
                    val current = messages[index]
                    if (old === current) continue
                    if (old !is AgentMessageUi || current !is AgentMessageUi ||
                        old.id != current.id || mapping[index] < 0
                    ) {
                        compatible = false
                        break
                    }
                    changedIndices += index
                }
                if (compatible) {
                    if (changedIndices.isEmpty()) return entries
                    val updated = entries.toMutableList()
                    changedIndices.forEach { index ->
                        updated[mapping[index]] = AgentTimelineEntry.Message(messages[index])
                    }
                    // Neither the old input snapshot nor any previously returned list
                    // is mutated. Also tolerate a caller reusing its list container.
                    source = if (messages is AgentIncrementalList<AgentChatMessageUi>) messages else messages.toList()
                    entries = updated.incrementalSnapshot()
                    return entries
                }
            }
        }

        val input = messages.incrementalSnapshot()
        val projected = fullProjection(input).incrementalSnapshot()
        source = input
        entries = projected
        sourceToEntry = mapAssistantSlots(input, projected)
        workSlots = if (sourceToEntry == null) null else mapWorkSlots(input, projected)
        return projected
    }

    /** Source slot -> (entry, member) of a work-group step; null when ambiguous. */
    private fun mapWorkSlots(
        input: List<AgentChatMessageUi>,
        projected: List<AgentTimelineEntry>,
    ): Map<Int, WorkSlot>? {
        val byId = HashMap<String, Int>(input.size)
        input.forEachIndexed { index, message ->
            if (byId.put(message.id, index) != null) return null
        }
        val slots = HashMap<Int, WorkSlot>()
        projected.forEachIndexed { entryIndex, entry ->
            val group = entry as? AgentTimelineEntry.WorkProcess ?: return@forEachIndexed
            group.messages.forEachIndexed { member, message ->
                val sourceIndex = byId[message.id] ?: return null
                if (input[sourceIndex] !== message || slots.put(sourceIndex, WorkSlot(entryIndex, member)) != null) {
                    return null
                }
            }
        }
        return slots
    }

    private fun mapAssistantSlots(
        input: List<AgentChatMessageUi>,
        projected: List<AgentTimelineEntry>,
    ): IntArray? {
        val byId = HashMap<String, Int>(input.size)
        input.forEachIndexed { index, message ->
            if (byId.put(message.id, index) != null) return null
        }
        val mapping = IntArray(input.size) { -1 }
        projected.forEachIndexed { entryIndex, entry ->
            val message = (entry as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                ?: return@forEachIndexed
            val sourceIndex = byId[message.id] ?: return null
            if (input[sourceIndex] !== message || mapping[sourceIndex] >= 0) return null
            mapping[sourceIndex] = entryIndex
        }
        // Fail closed if projection changes ever stop preserving assistant refs.
        input.forEachIndexed { index, message ->
            if (message is AgentMessageUi && mapping[index] < 0) return null
        }
        return mapping
    }
}

/**
 * Prefaces are strings only; never cache a footer's message/callback owner here.
 * A selected owner depends on all earlier assistant bodies since its ordinary
 * user boundary. Precompute those dependency slots after each full traversal.
 * Unlike the entry projection, historical body edits are NOT a compatible delta.
 */
internal class AgentSpeechPrefaceCache(
    private val fullProjection: (List<AgentChatMessageUi>, Set<String>) -> Map<String, String> =
        ::visibleTurnSpeechPrefaces,
) {
    private var source: List<AgentChatMessageUi>? = null
    private var finalIds: Set<String> = emptySet()
    private var ownerDependentSlots: BooleanArray? = null
    private var prefaces: Map<String, String> = emptyMap()

    fun project(messages: List<AgentChatMessageUi>, selectedIds: Set<String>): Map<String, String> {
        val previous = source
        val dependent = ownerDependentSlots
        if (previous != null && dependent != null && previous.size == messages.size && finalIds == selectedIds) {
            var compatible = true
            for (index in messages.indices) {
                val old = previous[index]
                val current = messages[index]
                if (old === current) continue
                if (old !is AgentMessageUi || current !is AgentMessageUi || old.id != current.id ||
                    !old.isStreaming || !current.isStreaming ||
                    !current.content.startsWith(old.content) || dependent[index]
                ) {
                    compatible = false
                    break
                }
            }
            if (compatible) {
                source = messages.toList()
                return prefaces
            }
        }
        val input = messages.toList()
        val ids = selectedIds.toSet()
        val result = fullProjection(input, ids)
        source = input
        finalIds = ids
        prefaces = result
        ownerDependentSlots = ownerDependencies(input, ids)
        return result
    }

    private fun ownerDependencies(messages: List<AgentChatMessageUi>, ids: Set<String>): BooleanArray? {
        val seen = HashSet<String>(messages.size)
        messages.forEach { if (!seen.add(it.id)) return null }
        val dependent = BooleanArray(messages.size)
        var ownerAhead = false
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            // The legacy algorithm captures a selected user's preface BEFORE
            // clearing parts. Respect that order even for unusual owner sets.
            if (message is UserMessageUi && !message.isSteerSupplement()) ownerAhead = false
            if (message.id in ids) ownerAhead = true
            dependent[index] = ownerAhead
        }
        // Fail closed for an already selected owner behind a changed slot too:
        // no selected owner may lie between a delta and its ordinary user boundary.
        var ownerBehind = false
        for (index in messages.indices) {
            val message = messages[index]
            if (message is UserMessageUi && !message.isSteerSupplement()) ownerBehind = false
            if (message.id in ids) ownerBehind = true
            dependent[index] = dependent[index] || ownerBehind
        }
        return dependent
    }
}

/** Same class: resume/retry filtering and work classification see an identical kind. */
private fun AgentChatMessageUi.isWorkPayloadOf(current: AgentChatMessageUi): Boolean =
    (this is ThinkingMessageUi && current is ThinkingMessageUi) ||
        (this is ToolActivityMessageUi && current is ToolActivityMessageUi) ||
        (this is ToolSummaryMessageUi && current is ToolSummaryMessageUi)
