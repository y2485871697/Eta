package io.github.mangi.eta.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.AssistantPrompt
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object AssistantRepository {
    private const val DIRECTORY_NAME = "eta-assistants"
    private const val INDEX_NAME = "index.json"
    private const val AVATAR_DIR = "avatars"
    private const val AVATAR_SIZE = 512

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private lateinit var applicationContext: Context

    private val _profiles = MutableStateFlow<List<AssistantProfile>>(emptyList())
    val profiles: StateFlow<List<AssistantProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(AssistantPrompt.DEFAULT_ID)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        applicationContext = context.applicationContext
        directory().mkdirs()
        avatarsDirectory().mkdirs()
        val snapshot = readIndex() ?: seedDefault()
        publish(snapshot)
    }

    fun active(): AssistantProfile =
        profiles.value.firstOrNull { it.id == activeId.value }
            ?: profiles.value.firstOrNull()
            ?: defaultProfile()

    fun profile(id: String): AssistantProfile? =
        profiles.value.firstOrNull { it.id == id }

    fun systemPrompt(): String {
        val assistant = if (::applicationContext.isInitialized) active() else defaultProfile()
        return AssistantPrompt.build(assistant.name, assistant.prompt)
    }

    fun avatarFile(fileName: String?): File? {
        if (!::applicationContext.isInitialized || fileName.isNullOrBlank()) return null
        val file = File(avatarsDirectory(), fileName)
        return file.takeIf { it.isFile }
    }

    fun avatarBitmap(fileName: String?): Bitmap? {
        val file = avatarFile(fileName) ?: return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    @Synchronized
    fun create(name: String = "新助手", prompt: String = "", avatarFileName: String? = null): AssistantProfile {
        ensureReady()
        val id = UUID.randomUUID().toString()
        val created = AssistantProfile(
            id = id,
            name = name.trim().ifBlank { "新助手" },
            prompt = prompt,
            avatarFileName = avatarFileName,
            createdAt = System.currentTimeMillis(),
        )
        val snapshot = Snapshot(activeId.value, profiles.value + created)
        writeIndex(snapshot)
        publish(snapshot)
        return created
    }

    @Synchronized
    fun duplicate(id: String): AssistantProfile {
        ensureReady()
        val source = requireNotNull(profile(id)) { "助手不存在" }
        val copyName = source.name.trim().ifBlank { AssistantPrompt.DEFAULT_NAME } + " 副本"
        val created = create(name = copyName, prompt = source.prompt)
        source.avatarFileName?.let { copyAvatar(it, created.id) }?.let { fileName ->
            return update(created.copy(avatarFileName = fileName))
        }
        return created
    }

    @Synchronized
    fun update(profile: AssistantProfile): AssistantProfile {
        ensureReady()
        require(profiles.value.any { it.id == profile.id }) { "助手不存在" }
        val updated = profile.copy(name = profile.name.trim().ifBlank { AssistantPrompt.DEFAULT_NAME })
        val snapshot = Snapshot(
            activeId = activeId.value,
            profiles = profiles.value.map { if (it.id == updated.id) updated else it },
        )
        writeIndex(snapshot)
        publish(snapshot)
        return updated
    }

    @Synchronized
    fun delete(id: String) {
        ensureReady()
        val remaining = profiles.value.filterNot { it.id == id }
        require(remaining.isNotEmpty()) { "必须保留至少一个助手" }
        val nextActive = if (activeId.value == id) remaining.first().id else activeId.value
        avatarFile(profile(id)?.avatarFileName)?.delete()
        val snapshot = Snapshot(nextActive, remaining)
        writeIndex(snapshot)
        publish(snapshot)
    }

    @Synchronized
    fun select(id: String) {
        ensureReady()
        require(profiles.value.any { it.id == id }) { "助手不存在" }
        if (activeId.value == id) return
        val snapshot = Snapshot(id, profiles.value)
        writeIndex(snapshot)
        publish(snapshot)
    }

    @Synchronized
    fun saveAvatar(id: String, bitmap: Bitmap): AssistantProfile {
        ensureReady()
        val current = requireNotNull(profile(id)) { "助手不存在" }
        val fileName = "$id.png"
        val target = File(avatarsDirectory(), fileName)
        val scaled = scaleAvatar(bitmap)
        target.outputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        if (scaled !== bitmap) scaled.recycle()
        return update(current.copy(avatarFileName = fileName))
    }

    @Synchronized
    fun clearAvatar(id: String): AssistantProfile {
        ensureReady()
        val current = requireNotNull(profile(id)) { "助手不存在" }
        avatarFile(current.avatarFileName)?.delete()
        return update(current.copy(avatarFileName = null))
    }

    private fun seedDefault(): Snapshot {
        val seeded = Snapshot(
            activeId = AssistantPrompt.DEFAULT_ID,
            profiles = listOf(defaultProfile(System.currentTimeMillis())),
        )
        writeIndex(seeded)
        return seeded
    }

    private fun defaultProfile(createdAt: Long = 0L) = AssistantProfile(
        id = AssistantPrompt.DEFAULT_ID,
        name = AssistantPrompt.DEFAULT_NAME,
        prompt = AssistantPrompt.DEFAULT_BODY,
        createdAt = createdAt,
    )

    private fun publish(snapshot: Snapshot) {
        _profiles.value = snapshot.profiles
        _activeId.value = snapshot.activeId.takeIf { id -> snapshot.profiles.any { it.id == id } }
            ?: snapshot.profiles.first().id
    }

    private fun readIndex(): Snapshot? {
        val file = indexFile()
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<Snapshot>(file.readText())
                .takeIf { it.profiles.isNotEmpty() }
        }.getOrNull()
    }

    private fun writeIndex(snapshot: Snapshot) {
        val target = indexFile()
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(json.encodeToString(snapshot))
        if (!temporary.renameTo(target)) {
            target.writeText(json.encodeToString(snapshot))
            temporary.delete()
        }
    }

    private fun copyAvatar(sourceFileName: String, targetId: String): String? {
        val source = avatarFile(sourceFileName) ?: return null
        val fileName = "$targetId.png"
        val target = File(avatarsDirectory(), fileName)
        return runCatching {
            source.copyTo(target, overwrite = true)
            fileName
        }.getOrNull()
    }

    private fun scaleAvatar(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        if (longest <= AVATAR_SIZE) return bitmap
        val scale = AVATAR_SIZE.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun ensureReady() {
        check(::applicationContext.isInitialized) { "AssistantRepository 未初始化" }
        if (profiles.value.isEmpty()) {
            publish(readIndex() ?: seedDefault())
        }
    }

    private fun directory(): File = File(applicationContext.filesDir, DIRECTORY_NAME)

    private fun avatarsDirectory(): File = File(directory(), AVATAR_DIR)

    private fun indexFile(): File = File(directory(), INDEX_NAME)

    @Serializable
    private data class Snapshot(
        val activeId: String,
        val profiles: List<AssistantProfile>,
    )
}
