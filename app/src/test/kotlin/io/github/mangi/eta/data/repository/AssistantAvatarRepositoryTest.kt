package io.github.mangi.eta.data.repository

import android.app.Application
import android.graphics.Bitmap
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class AssistantAvatarRepositoryTest {
    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        java.io.File(context.filesDir, "eta-assistants").deleteRecursively()
        Prefs.initLocal(context)
        SettingsDataStore.init(context)
        AgentMemoryRepository.init(context)
        AssistantRepository.init(context)
    }

    private fun image(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    @Test fun consecutiveSameNameSavesAndClearInvalidateLoadedBitmap() = runBlocking {
        val assistant = AssistantRepository.create(name = "avatar test", enabledSkillIds = emptyList())
        val firstSave = AssistantRepository.saveAvatar(assistant.id, image(android.graphics.Color.RED))
        val firstRevision = AssistantRepository.avatarRevision.value
        val first = requireNotNull(AssistantRepository.loadAvatarBitmap(firstSave.avatarFileName, firstRevision))
        assertSame(first, AssistantRepository.loadAvatarBitmap(firstSave.avatarFileName, firstRevision))
        val secondSave = AssistantRepository.saveAvatar(assistant.id, image(android.graphics.Color.BLUE))
        val secondRevision = AssistantRepository.avatarRevision.value
        assertEquals(firstSave.avatarFileName, secondSave.avatarFileName)
        assertTrue(secondRevision > firstRevision)
        assertNull(AssistantRepository.cachedAvatarBitmap(firstSave.avatarFileName, firstRevision))
        val second = requireNotNull(AssistantRepository.loadAvatarBitmap(secondSave.avatarFileName, secondRevision))
        assertNotSame(first, second)
        assertFalse(first.isRecycled)
        AssistantRepository.clearAvatar(assistant.id)
        assertTrue(AssistantRepository.avatarRevision.value > secondRevision)
        assertNull(AssistantRepository.loadAvatarBitmap(secondSave.avatarFileName, AssistantRepository.avatarRevision.value))
        assertFalse(second.isRecycled)
    }

    @Test fun restoreEmptyAvatarsAndDeletionCannotReturnCachedFile() = runBlocking {
        val assistant = AssistantRepository.create(name = "avatar import test", enabledSkillIds = emptyList())
        val saved = AssistantRepository.saveAvatar(assistant.id, image(android.graphics.Color.RED))
        val revision = AssistantRepository.avatarRevision.value
        val old = requireNotNull(AssistantRepository.loadAvatarBitmap(saved.avatarFileName, revision))
        AssistantRepository.importAvatars(emptyMap())
        val restoredRevision = AssistantRepository.avatarRevision.value
        assertTrue(restoredRevision > revision)
        assertNull(AssistantRepository.cachedAvatarBitmap(saved.avatarFileName, restoredRevision))
        assertNull(AssistantRepository.loadAvatarBitmap(saved.avatarFileName, restoredRevision))
        val resaved = AssistantRepository.saveAvatar(assistant.id, image(android.graphics.Color.BLUE))
        val latest = requireNotNull(AssistantRepository.loadAvatarBitmap(resaved.avatarFileName, AssistantRepository.avatarRevision.value))
        AssistantRepository.delete(assistant.id)
        assertNull(AssistantRepository.loadAvatarBitmap(resaved.avatarFileName, AssistantRepository.avatarRevision.value))
        assertFalse(old.isRecycled)
        assertFalse(latest.isRecycled)
    }
}
