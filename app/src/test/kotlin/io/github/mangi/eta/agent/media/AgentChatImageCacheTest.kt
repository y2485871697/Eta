package io.github.mangi.eta.agent.media

import android.app.Application
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AgentChatImageCacheTest {
    @Test
    fun stagesBytesAndRemovesConversationAndOrphans() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val kept = cache.stage("conv-keep", byteArrayOf(1, 2, 3, 4), "photo.jpg")!!
        val orphan = cache.stage("conv-orphan", byteArrayOf(5, 6, 7, 8), "other.png")!!

        assertTrue(File(kept.absolutePath).isFile)
        assertTrue(File(orphan.absolutePath).isFile)
        assertTrue(kept.absolutePath.contains(AgentChatImageCache.CACHE_DIRECTORY))
        assertFalse(kept.absolutePath.contains("workspace"))

        cache.deleteOrphans(setOf("conv-keep"))
        assertTrue(File(kept.absolutePath).isFile)
        assertFalse(File(orphan.absolutePath).exists())

        cache.deleteConversation("conv-keep")
        assertFalse(File(kept.absolutePath).exists())
    }

    @Test
    fun readBytesLoadsStagedFileAndDataUrl() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val bytes = byteArrayOf(9, 8, 7, 6)
        val staged = cache.stage("conv-read", bytes, "photo.jpg")!!
        assertEquals(bytes.toList(), AgentChatImageCache.readBytes(staged.absolutePath)!!.toList())
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        assertEquals(bytes.toList(), AgentChatImageCache.readBytes(dataUrl)!!.toList())
    }
}

class AgentHistoryImageHydratorTest {
    @Test
    fun missingFileIsDroppedForVisionAndListedForTextModels() {
        val persisted = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userPersistedImageMessage(
                text = "看看这张图",
                images = listOf(
                    AgentConversationCodec.PersistedImage(
                        path = "/missing/chat-image-1.jpg",
                        mimeType = "image/jpeg",
                        displayName = "chat-image-1.jpg",
                    ),
                ),
            ),
        )
        assertTrue(persisted.contentJson.contains("image_file"))
        assertFalse(persisted.contentJson.contains("base64"))

        val vision = AgentHistoryImageHydrator.hydrate(persisted, supportsVision = true)
        assertFalse(vision.contentJson.contains("image_url"))
        assertFalse(vision.contentJson.contains("image_file"))

        val textOnly = AgentHistoryImageHydrator.hydrate(persisted, supportsVision = false)
        assertTrue(textOnly.content.contains("/missing/chat-image-1.jpg"))
        assertTrue(textOnly.contentJson.contains("[用户图片]"))
        assertFalse(textOnly.contentJson.contains("image_file"))
    }
}
