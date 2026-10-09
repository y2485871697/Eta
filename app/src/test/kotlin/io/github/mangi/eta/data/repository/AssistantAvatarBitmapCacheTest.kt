package io.github.mangi.eta.data.repository

import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class AssistantAvatarBitmapCacheTest {
    private fun bitmap() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test fun successIsSharedAndDecodeIsOffCallerThread() = runBlocking {
        val cache = AssistantAvatarBitmapCache()
        val caller = Thread.currentThread()
        val image = bitmap()
        val calls = AtomicInteger()
        val revision = cache.generation()
        val first = cache.load("a.png", revision) {
            assertNotSame(caller, Thread.currentThread())
            calls.incrementAndGet()
            image
        }
        val second = cache.load("a.png", revision) { calls.incrementAndGet(); bitmap() }
        assertSame(image, first)
        assertSame(first, second)
        assertSame(image, cache.cached("a.png", revision))
        assertEquals(1, calls.get())
    }

    @Test fun concurrentRequestsReuseOneDecode() = runBlocking {
        val cache = AssistantAvatarBitmapCache()
        val calls = AtomicInteger()
        val image = bitmap()
        val revision = cache.generation()
        val results = List(12) { async(Dispatchers.Default) { cache.load("a.png", revision) {
            calls.incrementAndGet(); image
        } } }.map { it.await() }
        assertEquals(1, calls.get())
        results.forEach { assertSame(image, it) }
    }

    @Test fun missingOrFailedResultIsNotPermanentlyCached() = runBlocking {
        val cache = AssistantAvatarBitmapCache()
        val revision = cache.generation()
        assertNull(cache.load(null, revision) { fail("null filename must not decode"); null })
        assertNull(cache.load("", revision) { fail("blank filename must not decode"); null })
        assertNull(cache.load("missing.png", revision) { null })
        val image = bitmap()
        assertSame(image, cache.load("missing.png", revision) { image })
    }

    @Test fun overwriteRevisionRejectsOldDecodeAndCanReloadSameFileName() = runBlocking {
        val cache = AssistantAvatarBitmapCache()
        val oldRevision = cache.generation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldImage = bitmap()
        val old = async(Dispatchers.Default) { cache.load("same.png", oldRevision) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            oldImage
        } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val nextRevision = cache.invalidate()
        release.countDown()
        assertNull(old.await())
        assertNull(cache.cached("same.png", oldRevision))
        assertNull(cache.cached("same.png", nextRevision))
        val updated = bitmap()
        assertSame(updated, cache.load("same.png", nextRevision) { updated })
        assertFalse(oldImage.isRecycled)
    }

    @Test fun byteBoundEvictionAndInvalidationNeverRecycleVisibleImages() = runBlocking {
        val image = bitmap()
        val cache = AssistantAvatarBitmapCache(maxBytes = image.allocationByteCount)
        val revision = cache.generation()
        cache.load("a.png", revision) { image }
        val other = bitmap()
        cache.load("b.png", revision) { other }
        assertNull(cache.cached("a.png", revision))
        assertSame(other, cache.cached("b.png", revision))
        assertFalse(image.isRecycled)
        cache.invalidate()
        assertFalse(other.isRecycled)
        assertNull(cache.cached("b.png", revision))
    }

    @Test fun updatingOneAvatarKeepsOtherSuccessfulBitmapsReady() = runBlocking {
        val cache = AssistantAvatarBitmapCache()
        val revision = cache.generation()
        val changed = bitmap()
        val unaffected = bitmap()
        cache.load("a.png", revision) { changed }
        cache.load("b.png", revision) { unaffected }
        val next = cache.invalidate("a.png")
        assertNull(cache.cached("a.png", next))
        assertSame(unaffected, cache.cached("b.png", next))
        assertSame(unaffected, cache.load("b.png", next) { fail("unrelated avatar must not decode again"); null })
        assertFalse(changed.isRecycled)
    }

    @Test fun recycledImagesAreNotReturnedOrRetained() = runBlocking {
        val cache = AssistantAvatarBitmapCache()
        val revision = cache.generation()
        val recycled = bitmap().also { it.recycle() }
        assertNull(cache.load("a.png", revision) { recycled })
        val image = bitmap()
        assertSame(image, cache.load("a.png", revision) { image })
        image.recycle()
        assertNull(cache.cached("a.png", revision))
        val replacement = bitmap()
        assertSame(replacement, cache.load("a.png", revision) { replacement })
    }

    @Test fun cancelledDecodeCannotPublishOrPopulateCache() = runBlocking {
        val cache = AssistantAvatarBitmapCache()
        val revision = cache.generation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val image = bitmap()
        val request = async(Dispatchers.Default) { cache.load("a.png", revision) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            image
        } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        request.cancel()
        release.countDown()
        request.cancelAndJoin()
        assertNull(cache.cached("a.png", revision))
    }
}
