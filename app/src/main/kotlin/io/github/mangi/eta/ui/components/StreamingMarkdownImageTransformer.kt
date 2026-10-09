package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import io.github.mangi.eta.ui.markdown.StreamingGfmSnapshot

/**
 * Reuse the stateless default for plain documents. Once bracket syntax is seen,
 * refresh the static-local value on each published snapshot, including terminal
 * parses and corrections that remove brackets: frozen blocks may still need
 * current reference definitions. Re-entering composition with the SAME snapshot
 * must not manufacture another static-local change. Identity, not content or
 * structural equality, defines the publication boundary; no handler is frozen.
 */
internal class StreamingMarkdownImageTransformerPolicy {
    private val retained = NoOpImageTransformerImpl()
    private var bracketSyntaxSeen = false
    private var lastSnapshot: StreamingGfmSnapshot? = null
    private var lastTransformer: ImageTransformer = retained

    fun forSnapshot(snapshot: StreamingGfmSnapshot): ImageTransformer {
        if (lastSnapshot === snapshot) return lastTransformer
        if (!bracketSyntaxSeen && '[' in snapshot.state.content) bracketSyntaxSeen = true
        lastTransformer = if (bracketSyntaxSeen) NoOpImageTransformerImpl() else retained
        lastSnapshot = snapshot
        return lastTransformer
    }
}

@Composable
internal fun rememberStreamingMarkdownImageTransformer(snapshot: StreamingGfmSnapshot): ImageTransformer {
    val policy = remember { StreamingMarkdownImageTransformerPolicy() }
    return policy.forSnapshot(snapshot)
}
