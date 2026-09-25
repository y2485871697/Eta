package io.github.mangi.eta.ui.haptics

import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import io.github.mangi.eta.ui.markdown.ChatSelectableText
import android.app.Application
import android.widget.Magnifier
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], shadows = [SelectionTestMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HapticSelectionGestureTest {
    @get:Rule val compose = createComposeRule()
    private val toolbar = RecordingToolbar()
    private val selection = SelectionState()
    private lateinit var layout: TextLayoutResult
    private var oldContextMenu = false

    @Before fun setup() {
        oldContextMenu = ComposeFoundationFlags.isNewContextMenuEnabled
        ComposeFoundationFlags.isNewContextMenuEnabled = false
    }
    @After fun restore() { ComposeFoundationFlags.isNewContextMenuEnabled = oldContextMenu }

    private fun show(text: String = "alpha bravo charlie delta") {
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                HapticSelectionContainer(selectionState = selection) {
                    Column(Modifier.width(320.dp)) {
                        BasicText(text, style = TextStyle(fontSize = 20.sp), onTextLayout = { layout = it }, modifier = Modifier.testTag("text"))
                        BasicText("second paragraph remains unselected")
                    }
                }
            }
        }
    }

    @Test fun longPressKeepsPartialSelectionAndOffersCopy() {
        show()
        compose.onNodeWithTag("text").performTouchInput { longClick(layout.getBoundingBox(7).center) }
        compose.runOnIdle {
            assertEquals("bravo", selection.selectedTexts.joinToString("") { it.text })
            assertNotNull("Partial selection must offer Copy, not only Select all", toolbar.copy)
            assertEquals(TextToolbarStatus.Shown, toolbar.status)
        }
    }

    @Test fun longPressDragExtendsOnlyRequestedText() {
        show()
        compose.onNodeWithTag("text").performTouchInput {
            down(layout.getBoundingBox(7).center)
            advanceEventTime(800)
            moveTo(layout.getBoundingBox(17).center, delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            val text = selection.selectedTexts.joinToString("") { it.text }
            assertTrue(text, text.contains("bravo") && text.contains("charlie"))
            assertFalse(text, text.contains("second paragraph"))
            assertNotNull(toolbar.copy)
        }
    }

    @Test fun selectionReleaseDoesNotOpenLinkButShortTapDoes() {
        val opened = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }, LocalTextToolbar provides toolbar) {
                HapticSelectionContainer(selectionState = selection) {
                    ChatSelectableText(buildAnnotatedString {
                        append("before ")
                        withLink(LinkAnnotation.Url("https://example.com")) { append("linked") }
                        append(" after")
                    }, style = TextStyle(fontSize = 20.sp), onTextLayout = { layout = it }, modifier = Modifier.testTag("text"))
                }
            }
        }
        compose.onNodeWithTag("text").performTouchInput { longClick(layout.getBoundingBox(9).center) }
        compose.runOnIdle {
            assertTrue(opened.isEmpty())
            assertEquals("linked", selection.selectedTexts.joinToString("") { it.text })
            assertNotNull("Link selection must offer Copy", toolbar.copy)
            selection.clear()
        }
        compose.onNodeWithTag("text").performTouchInput { click(layout.getBoundingBox(9).center) }
        compose.runOnIdle { assertEquals(listOf("https://example.com"), opened) }
    }

    @Test fun markdownLocalFileLinkCanBeSelectedAndCopied() {
        val source = "[下载 APK](/storage/emulated/0/Download/daiyu-5.3.0.apk)"
        val node = org.intellij.markdown.parser.MarkdownParser(
            org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor(),
        ).buildMarkdownTreeFromString(source).children.first()
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                HapticSelectionContainer(selectionState = selection) {
                    com.mikepenz.markdown.m3.Markdown(content = source, success = { _, _, _ ->
                        val settings = com.mikepenz.markdown.annotator.annotatorSettings()
                        val text = buildAnnotatedString { buildMarkdownAnnotatedString(source, node, settings) }
                        ChatSelectableText(
                            text = text, style = TextStyle(fontSize = 20.sp), modifier = Modifier.testTag("text"),
                            onTextLayout = { layout = it },
                        )
                    })
                }
            }
        }
        compose.onNodeWithTag("text").performTouchInput { longClick(layout.getBoundingBox(4).center) }
        compose.runOnIdle {
            assertEquals("APK", selection.selectedTexts.joinToString("") { it.text })
            assertNotNull(toolbar.copy)
        }
    }

    private class RecordingToolbar : TextToolbar {
        override var status = TextToolbarStatus.Hidden
        var copy: (() -> Unit)? = null
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy = onCopyRequested
            status = TextToolbarStatus.Shown
        }
        override fun hide() { status = TextToolbarStatus.Hidden }
    }
}

/** Robolectric has no real Surface for Android's magnifier popup. Keep native selection intact. */
@Implements(Magnifier::class)
class SelectionTestMagnifier {
    @Implementation fun show(sourceX: Float, sourceY: Float) = Unit
    @Implementation fun show(sourceX: Float, sourceY: Float, windowX: Float, windowY: Float) = Unit
    @Implementation fun update() = Unit
    @Implementation fun dismiss() = Unit
}
