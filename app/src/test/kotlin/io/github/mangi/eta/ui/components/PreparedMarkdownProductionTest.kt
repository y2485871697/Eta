package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.model.AgentMessageUi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Executes ChatMessageItem -> target -> Default parser -> prepared block -> actual text host.
 * CI correctness/layout coverage, not device performance or a frame-rate claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreparedMarkdownProductionTest {
    @get:Rule val compose = createComposeRule()
    @Before fun initPrefs() { Prefs.initLocal(RuntimeEnvironment.getApplication()) }

    @Test fun realWorkerReusesPlainPrefixAndRefreshesThemeCorrectionAndTerminal() {
        val retained = StreamingMarkdownState()
        val prefix = "Stable **bold** and `code`.\n\n"
        // Match upstream inline-code background padding (thin spaces).
        val expectedPrefix = "Stable bold and \u2009code\u2009."
        val message = mutableStateOf(AgentMessageUi("prepared", prefix + "Tail", isStreaming = true))
        val paused = mutableStateOf(true)
        val dark = mutableStateOf(false)
        val width = mutableStateOf(340.dp)
        val scale = mutableStateOf(1f)
        compose.setContent {
            MiuixTheme(colors = if (dark.value) darkColorScheme() else lightColorScheme()) {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                    // A stable, non-zero viewport allows Robolectric to perform
                    // an Android measure/draw after asynchronous publications.
                    Box(Modifier.width(width.value).height(240.dp).testTag("prepared-host")) {
                        Column {
                            ChatMessageItem(
                                message.value, remember { ChatMessageActions() }, false,
                                retainedStreamingState = retained, showCopyAction = false,
                                isPaused = paused.value,
                            )
                        }
                    }
                }
            }
        }
        fun await(source: String, terminal: Boolean = false) {
            awaitProductionState("publication source=$source terminal=$terminal", retained) {
                retained.documentState.snapshot?.let { it.originalSource == source && it.isComplete == terminal &&
                    it.document.blocks.isNotEmpty() } == true
            }
            compose.waitForIdle()
        }
        await(message.value.content)
        val firstStyle = requireNotNull(retained.documentState.snapshot).document.inlineStyle
        assertEquals(lightColorScheme().primary, firstStyle.linkColor)
        assertEquals(lightColorScheme().onSurface.copy(alpha = 0.07f), firstStyle.codeBackground)
        val first = requireNotNull(retained.documentState.snapshot).document.blocks.first()
        assertEquals(expectedPrefix, (first as io.github.mangi.eta.ui.markdown.MarkdownTextBlock).text.text)
        compose.onNodeWithText(expectedPrefix, useUnmergedTree = true).assertExists()
        compose.runOnIdle { message.value = message.value.copy(content = prefix + "Tail grows") }
        await(message.value.content)
        assertSame(first, requireNotNull(retained.documentState.snapshot).document.blocks.first())
        compose.onNodeWithText("Tail grows", useUnmergedTree = true).assertExists()

        compose.runOnIdle { dark.value = true }
        awaitProductionState("theme publication", retained) {
            retained.documentState.snapshot?.document?.inlineStyle != firstStyle
        }
        compose.waitForIdle()
        val themedDocument = requireNotNull(retained.documentState.snapshot).document
        assertEquals(darkColorScheme().primary, themedDocument.inlineStyle.linkColor)
        assertEquals(darkColorScheme().onSurface.copy(alpha = 0.07f), themedDocument.inlineStyle.codeBackground)
        val themed = themedDocument.blocks.first()
        assertNotSame(first, themed)
        assertEquals(expectedPrefix, (themed as io.github.mangi.eta.ui.markdown.MarkdownTextBlock).text.text)
        compose.runOnIdle { width.value = 220.dp; scale.value = 1.3f }
        compose.waitForIdle()
        compose.onNodeWithText(expectedPrefix, useUnmergedTree = true).assertExists()

        compose.runOnIdle { message.value = message.value.copy(content = "Corrected **prefix**.\n\nNew tail") }
        await(message.value.content)
        assertNotSame(themed, requireNotNull(retained.documentState.snapshot).document.blocks.first())
        compose.onNodeWithText("Corrected prefix.", useUnmergedTree = true).assertExists()
        val pending = requireNotNull(retained.documentState.snapshot)
        compose.runOnIdle { message.value = message.value.copy(isStreaming = false); paused.value = false }
        await(message.value.content, terminal = true)
        val final = requireNotNull(retained.documentState.snapshot)
        assertNotSame(pending.document.blocks.first(), final.document.blocks.first())
        assertEquals(message.value.content, final.renderedSource)
        compose.onNodeWithText("New tail", useUnmergedTree = true).assertExists()
    }

    private fun awaitProductionState(
        stage: String,
        retained: StreamingMarkdownState,
        condition: () -> Boolean,
    ) {
        val previousAutoAdvance = compose.mainClock.autoAdvance
        try {
            compose.mainClock.autoAdvance = false
            compose.waitUntil(15_000) {
                // Drive only the real production parser/composition/frame runner.
                // Never force completion, invoke another runner, or extend timeouts.
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.onNodeWithTag("prepared-host").captureToImage()
                compose.runOnIdle(condition)
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "$stage timed out: snapshotSource=${retained.documentState.snapshot?.originalSource} " +
                    "snapshotComplete=${retained.documentState.snapshot?.isComplete} " +
                    "composedSource=${retained.documentState.composedSource} " +
                    "clock=${compose.mainClock.currentTime}",
                timeout,
            )
        } finally {
            compose.mainClock.autoAdvance = previousAutoAdvance
        }
    }

}
