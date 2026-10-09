package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Local invalidation regression. LiveOutput is a state probe, not the production
 * typewriter; this does not verify swipe gestures, lifecycle ordering or device jank. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatRouteCoveredLocalTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Reader(onRead: (Boolean) -> Unit) {
        val covered = LocalChatRouteCovered.current
        SideEffect { onRead(covered) }
    }

    @Composable
    private fun Unrelated(onCommit: () -> Unit) {
        SideEffect(onCommit)
    }

    @Composable
    private fun LiveOutput(text: State<String>) {
        Text(text.value, Modifier.testTag("live"))
    }

    @Test fun navigationInvalidatesReadersButNotUnrelatedContentAndDoesNotGateLiveText() {
        val covered = mutableStateOf(false)
        val live = mutableStateOf("first")
        val reads = mutableListOf<Boolean>()
        var unrelatedCommits = 0
        val onRead: (Boolean) -> Unit = { reads.add(it) }
        val onUnrelated: () -> Unit = { unrelatedCommits++ }
        compose.setContent {
            CompositionLocalProvider(LocalChatRouteCovered provides covered.value) {
                Column {
                    Reader(onRead)
                    Unrelated(onUnrelated)
                    LiveOutput(live)
                }
            }
        }
        compose.waitForIdle()
        val baseline = unrelatedCommits
        assertTrue(baseline > 0)
        assertEquals(false, reads.last())
        for (value in listOf(true, false, true)) {
            val previousReads = reads.size
            compose.runOnIdle { covered.value = value }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(reads.size > previousReads)
                assertEquals(value, reads.last())
                assertEquals("non-reader must not be invalidated by coverage", baseline, unrelatedCommits)
            }
        }
        compose.runOnIdle { live.value = "first plus streamed tail" }
        compose.onNodeWithTag("live").assertTextEquals("first plus streamed tail")
        compose.runOnIdle { assertEquals(true, reads.last()) }
    }
}
