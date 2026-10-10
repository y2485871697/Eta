package io.github.mangi.eta.ui.haptics

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression: streaming feedback must survive a second concurrent observer and must not require
 * window focus. A dialog or the notification shade takes focus while the text stays visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StreamingHapticsGateTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.CREATED }
    }

    /** Counts ticks by observing whether the gate allowed the advance for this view. */
    private fun ticked(block: () -> Unit): Boolean {
        val before = StreamingHaptics.allowedAdvances
        block()
        return StreamingHaptics.allowedAdvances > before
    }

    @Test
    fun secondObserverDisposalKeepsFeedbackForTheRemainingHost() {
        val owner = Owner().apply { resume() }
        var showSecond by mutableStateOf(true)
        lateinit var view: android.view.View
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                view = LocalView.current
                StreamingHaptics.Observe(enabled = true)
                if (showSecond) StreamingHaptics.Observe(enabled = true)
            }
        }
        compose.runOnIdle { assertTrue(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
        // The later observer disposes; the first one still owns the visible view.
        showSecond = false
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
    }

    @Test
    fun foregroundAdvanceStopsWhenHiddenButRuntimeOutputContinues() {
        val owner = Owner().apply { resume() }
        lateinit var view: android.view.View
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                view = LocalView.current
                StreamingHaptics.Observe(enabled = true, conversationId = "conv-current")
            }
        }
        // Visible ticks still follow the resumed host. Window focus is asserted structurally below.
        compose.runOnIdle { assertTrue(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
        compose.runOnIdle { owner.stop() }
        compose.runOnIdle { assertFalse(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
        compose.runOnIdle { assertFalse(ticked { StreamingHaptics.noteBackgroundOutput(4, "conv-other") }) }
        compose.runOnIdle { assertTrue(ticked { StreamingHaptics.noteBackgroundOutput(4, "conv-current") }) }
        compose.runOnIdle { assertEquals(4, backgroundPulseCount(4)) }
    }

    /** The gate must not read window focus at all: a dialog or shade steals focus mid-stream. */
    @Test
    fun gateSourceDoesNotConsultWindowFocus() {
        val relative = "src/main/kotlin/io/github/mangi/eta/ui/haptics/StreamingHaptics.kt"
        val candidates = listOf(java.io.File(relative), java.io.File("app/$relative"))
        val source = candidates.firstOrNull { it.isFile }
        assertTrue("gate source not found: ${candidates.map { it.absolutePath }}", source != null)
        val body = requireNotNull(source).readText()
            .substringAfter("fun onVisibleAdvance")
            .substringBefore("@Composable")
        assertFalse(body, body.contains("hasWindowFocus"))
    }

    @Test
    fun coveredResumedHostStopsOutputImmediately() {
        val owner = Owner().apply { resume() }
        var onChat by mutableStateOf(true)
        lateinit var view: android.view.View
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                view = LocalView.current
                StreamingHaptics.Observe(enabled = onChat, conversationId = "conv-current")
            }
        }
        compose.runOnIdle { assertTrue(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
        compose.runOnIdle { assertFalse(ticked { StreamingHaptics.noteBackgroundOutput(3, "conv-current") }) }
        onChat = false
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
        compose.runOnIdle { assertFalse(ticked { StreamingHaptics.noteBackgroundOutput(8, "conv-current") }) }
    }

    @Test
    fun disabledObserverAndUnknownViewNeverTick() {
        val owner = Owner().apply { resume() }
        lateinit var view: android.view.View
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                view = LocalView.current
                StreamingHaptics.Observe(enabled = false)
            }
        }
        compose.runOnIdle { assertFalse(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
        val other = android.view.View(org.robolectric.RuntimeEnvironment.getApplication())
        compose.runOnIdle { assertFalse(ticked { StreamingHaptics.onVisibleAdvance(other) }) }
    }
}
