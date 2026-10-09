package io.github.mangi.eta.data.repository

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Successful immutable bitmap references only. Eviction never recycles a UI-owned bitmap. */
internal class AssistantAvatarBitmapCache(
    maxBytes: Int = 8 * 1024 * 1024,
) {
    private val decodeMutex = Mutex()
    private val entries = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }
    private val lock = Any()
    private var generation = 0L

    fun generation(): Long = synchronized(lock) { generation }

    fun cached(fileName: String?, expectedGeneration: Long): Bitmap? = synchronized(lock) {
        if (fileName.isNullOrBlank() || expectedGeneration != generation) null
        else entries.get(fileName)?.takeUnless { it.isRecycled }
    }

    fun invalidate(fileName: String? = null): Long = synchronized(lock) {
        if (fileName == null) entries.evictAll() else entries.remove(fileName)
        ++generation
    }

    suspend fun load(
        fileName: String?,
        expectedGeneration: Long,
        decode: (String) -> Bitmap?,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (fileName.isNullOrBlank()) return@withContext null
        decodeMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (generation() != expectedGeneration) return@withLock null
            cached(fileName, expectedGeneration)?.let { return@withLock it }
            val bitmap = decode(fileName)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (expectedGeneration != generation || bitmap == null || bitmap.isRecycled) null
                else {
                    entries.put(fileName, bitmap)
                    bitmap
                }
            }
        }
    }
}
