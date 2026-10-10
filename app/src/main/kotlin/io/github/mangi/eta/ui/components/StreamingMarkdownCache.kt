package io.github.mangi.eta.ui.components

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf

/** Root-owned and bounded: changing a conversation key must not reset its reveal clock. */
internal class StreamingMarkdownCache(private val capacity: Int = 4) {
    private val conversations = LinkedHashMap<String, MutableMap<String, StreamingMarkdownState>>(4, .75f, true)

    fun forConversation(id: String): MutableMap<String, StreamingMarkdownState> {
        val states = conversations.getOrPut(id) { mutableStateMapOf() }
        while (conversations.size > capacity.coerceAtLeast(1)) {
            val oldest = conversations.entries.iterator()
            oldest.next().value.values.forEach { it.documentState.revealCoordinator.pauseAnimationsAndCatchUp() }
            oldest.remove()
        }
        return states
    }
}

internal val LocalStreamingMarkdownStates =
    staticCompositionLocalOf<MutableMap<String, StreamingMarkdownState>?> { null }
