package io.github.mangi.eta.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: a streaming delta must not re-run the reveal gate decision.
 *
 * While a restore baseline is pending, animationsAllowed is false. When the message content was a
 * key of that effect, every delta re-entered the disallowed branch and caught the reveal up to the
 * newest text. The pending records drained, runFrameClock parked on its wakeup channel, and both
 * the typewriter and the haptics tied to onRevealAdvanced stopped for the rest of the message.
 */
class StreamingRevealGateKeyTest {
    private fun source(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("source not found: ${candidates.map { it.absolutePath }}")
    }

    private val documentRenderer by lazy {
        source("src/main/kotlin/io/github/mangi/eta/ui/markdown/DocumentStreamingMarkdown.kt").readText()
    }

    private fun launchedEffectContaining(marker: String): String {
        val head = documentRenderer.indexOf(marker)
        assertTrue("marker not found: $marker", head >= 0)
        assertTrue("marker is not unique: $marker", documentRenderer.indexOf(marker, head + 1) < 0)
        val start = documentRenderer.lastIndexOf("LaunchedEffect(", head)
        assertTrue("no enclosing LaunchedEffect for $marker", start >= 0)
        val effect = documentRenderer.substring(start, documentRenderer.indexOf('\n', head) + 1)
        assertTrue("marker escaped its effect: $effect", effect.count { it == '}' } <= effect.count { it == '{' })
        return effect
    }

    @Test
    fun catchUpBranchIsNotKeyedOnMessageContent() {
        val keys = launchedEffectContaining("else if (!isPaused) state.revealCoordinator.pauseAnimationsAndCatchUp()")
            .substringAfter("LaunchedEffect(")
            .substringBefore(')')
        assertFalse(keys, keys.contains("Content") || keys.contains("content"))
        assertTrue(keys, keys.contains("animationsAllowed"))
    }

    @Test
    fun coveredRestoreCannotBypassTheSettledCoverHold() {
        assertTrue(documentRenderer.contains("!settledCoverNow && state.restoreState.animationsAllowed(currentPaused)"))
        assertTrue(documentRenderer.contains("if (settledCoverNow) state.revealCoordinator.holdAnimations()"))
    }

    @Test
    fun explicitPauseStillFollowsNewText() {
        // The paused branch has nothing to animate later, so it must keep tracking content length.
        val effect = launchedEffectContaining("if (isPaused) state.revealCoordinator.restoreHistoryThrough(targetContent.length)")
        val keys = effect.substringAfter("LaunchedEffect(").substringBefore(')')
        assertTrue(keys, keys.contains("content", ignoreCase = true))
        assertTrue(effect, effect.contains("isPaused"))
        assertFalse(effect, effect.contains("pauseAnimationsAndCatchUp"))
    }
}
