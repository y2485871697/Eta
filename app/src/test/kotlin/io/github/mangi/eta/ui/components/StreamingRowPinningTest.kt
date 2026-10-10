package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.model.AgentMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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

/** Runs the real ChatMessageItem parser/reveal inside a real virtualized LazyColumn. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingRowPinningTest {
    @get:Rule val compose = createComposeRule()
    @Before fun initPrefs() { Prefs.initLocal(RuntimeEnvironment.getApplication()) }

    @Test fun liveRowSurvivesScrollReceivesNewTextAndReleasesAfterTerminalDrain() {
        val retained = StreamingMarkdownState()
        val message = mutableStateOf(AgentMessageUi("live", "First live words", isStreaming = true))
        var mounts = 0
        var disposals = 0
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            MiuixTheme {
                list = rememberLazyListState()
                scope = rememberCoroutineScope()
                LazyColumn(state = list, modifier = Modifier.height(240.dp)) {
                    item(key = "live") {
                        DisposableEffect(Unit) {
                            mounts++
                            onDispose { disposals++ }
                        }
                        ChatMessageItem(message.value, remember { ChatMessageActions() }, false,
                            retainedStreamingState = retained, showCopyAction = false)
                    }
                    items((1..40).toList(), key = { "history-$it" }) {
                        Text("History $it", Modifier.height(60.dp))
                    }
                }
            }
        }
        compose.waitUntil(15_000) { retained.snapshot?.originalSource == message.value.content }
        compose.runOnIdle { scope.launch { list.scrollToItem(30) } }
        compose.waitUntil(15_000) { list.firstVisibleItemIndex >= 25 }
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertEquals("A live row may not be disposed by scrolling", 0, disposals)
            assertFalse(list.layoutInfo.visibleItemsInfo.any { it.key == "live" })
            message.value = message.value.copy(content = "First live words\n\nNew words while offscreen")
        }
        compose.waitUntil(15_000) { retained.snapshot?.originalSource == message.value.content }
        compose.runOnIdle {
            assertFalse(retained.revealCoordinator.isAnimationPaused)
            assertEquals(0, disposals)
            message.value = message.value.copy(isStreaming = false)
        }
        compose.waitUntil(15_000) { retained.completedRevealSource == message.value.content }
        // Releasing a pin permits disposal on the next lazy measurement.
        compose.runOnIdle { scope.launch { list.scrollToItem(31) } }
        compose.waitUntil(15_000) { disposals == 1 }
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertTrue(retained.revealCoordinator.drained.value)
        }
    }
    @Test fun completedMultiBlockDocumentReentryHasItsFullHeightOnTheFirstLayout() {
        val retained = StreamingMarkdownState()
        val source = (1..18).joinToString("\n\n") { "Paragraph $it " + "steady text ".repeat(25) }
        val message = AgentMessageUi("long-completed", source, isStreaming = false)
        val shown = mutableStateOf(true)
        val heights = java.util.concurrent.CopyOnWriteArrayList<Int>()
        compose.setContent {
            MiuixTheme {
                Column(Modifier.width(340.dp)) {
                    if (shown.value) {
                        ChatMessageItem(message, remember { ChatMessageActions() }, false,
                            retainedStreamingState = retained, showCopyAction = false,
                            modifier = Modifier.onSizeChanged { if (it.height > 0) heights.add(it.height) })
                    }
                }
            }
        }
        compose.waitUntil(30_000) { retained.completedRevealSource == source }
        val fullHeight = compose.runOnIdle { heights.last() }
        assertTrue("Test must cover a multi-screen document", fullHeight > 900)
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { heights.clear(); shown.value = true }
        compose.waitUntil(15_000) { heights.isNotEmpty() }
        compose.runOnIdle {
            assertEquals("First reentry layout may not shrink to the 480-char batch", fullHeight, heights.first())
            assertEquals(source, retained.compositionProgress.composedSource)
        }
    }

}
