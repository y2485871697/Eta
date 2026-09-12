package io.github.mangi.eta.ui.components

import android.app.Application
import android.util.Base64
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatImageSupportTest {
    @Test
    fun extractsMarkdownImageDestination() {
        val source = "see ![cat](https://example.com/cat.png) please"
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val image = collectMarkdownImageNodes(root).single()
        assertEquals("https://example.com/cat.png", markdownImageDestination(source, image))
        assertEquals(
            "https://example.com/cat.png",
            resolveChatImageSource(source, image),
        )
    }

    @Test
    fun extractsDataUrlMarkdownImage() {
        val source = "![shot](data:image/png;base64,aaa)"
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val image = collectMarkdownImageNodes(root).single()
        assertEquals("data:image/png;base64,aaa", markdownImageDestination(source, image))
    }

    @Test
    fun usesOverrideAsDirectSource() {
        assertEquals(
            "https://cdn.example/a.jpg",
            resolveChatImageSource(
                content = "https://cdn.example/a.jpg",
                node = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString("text"),
                sourceOverride = "https://cdn.example/a.jpg",
            ),
        )
        assertTrue(isDirectChatImageSource("data:image/jpeg;base64,xx"))
        assertTrue(isDirectChatImageSource("content://media/external/images/1"))
        assertFalse(isDirectChatImageSource("not-an-image"))
    }

    @Test
    fun mimeTypeAndFileNameFollowSource() {
        assertEquals("image/png", chatImageMimeType("data:image/png;base64,xx"))
        assertEquals("png", chatImageFileExtension("image/png"))
        assertEquals("jpg", chatImageFileExtension("image/jpeg"))
        assertTrue(chatImageFileName("image/webp").startsWith("Eta-"))
        assertTrue(chatImageFileName("image/webp").endsWith(".webp"))
    }

    @Test
    fun readsDataUrlBytes() {
        val context = RuntimeEnvironment.getApplication()
        val png = tinyPng()
        val dataUrl = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        val payload = ChatImageBytes.readBytes(context, dataUrl)
        assertNotNull(payload)
        assertEquals("image/png", payload!!.mimeType)
        assertEquals(png.toList(), payload.bytes.toList())
    }

    @Test
    fun savesPngBytesToGallery() {
        val context = RuntimeEnvironment.getApplication()
        val png = tinyPng()
        val uri = ChatImageGallery.save(context, png, "image/png")
        assertNotNull(uri)
        val bytes = context.contentResolver.openInputStream(uri!!)?.use { it.readBytes() }
        assertNotNull(bytes)
        assertEquals(png.toList(), bytes!!.toList())
    }

    private fun tinyPng(): ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )
}
