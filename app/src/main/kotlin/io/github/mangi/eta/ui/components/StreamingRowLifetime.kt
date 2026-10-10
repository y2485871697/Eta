package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LocalPinnableContainer

/** Keep composition alive, not scroll position or row height. Only unfinished output is pinned. */
@Composable
internal fun KeepActiveStreamingRow(active: Boolean) {
    val container = LocalPinnableContainer.current
    DisposableEffect(container, active) {
        val handle = if (active) container?.pin() else null
        onDispose { handle?.release() }
    }
}

internal fun shouldKeepStreamingRow(
    content: String,
    isStreaming: Boolean,
    isPaused: Boolean,
    completedRevealSource: String?,
): Boolean = !isPaused && (isStreaming || completedRevealSource != content)

/** Composition progress belongs to a retained message, never to a disposable lazy row. */
internal class ProgressiveMarkdownCompositionState {
    private var source = ""
    private var composedLimit = 0
    var composedSource by mutableStateOf<String?>(null)
        private set
    var publication = 0
        private set
    var generation = 0
        private set

    fun acceptSource(next: String) {
        // The final parse can expose more AST blocks without changing raw source.
        // Every publication needs a fresh composition acknowledgement, but not a
        // reset of the already composed append-only prefix.
        composedSource = null
        publication++
        if (!next.startsWith(source)) {
            composedLimit = 0
            generation++
        }
        source = next
    }

    fun initialLimit(lengths: List<Int>, budget: Int, mustAdvance: Boolean): Int =
        maxOf(composedLimit.coerceAtMost(lengths.size),
            nextProgressiveBlockLimit(lengths, 0, budget, mustAdvance))

    fun recordLimit(limit: Int, blockCount: Int? = null, revision: String? = null, published: Int = publication) {
        if (published != publication) return
        if (revision != null && revision != source) return
        composedLimit = maxOf(composedLimit, limit)
        if (blockCount != null && limit >= blockCount && revision == source) {
            composedSource = source
        }
    }
}
