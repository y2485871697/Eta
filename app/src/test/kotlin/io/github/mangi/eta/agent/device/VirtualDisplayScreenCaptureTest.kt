package io.github.mangi.eta.agent.device

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualDisplayScreenCaptureTest {
    @Test
    fun captureScalesTheActualFrameAndPublishesMatchingContract() {
        val frame = patternedBitmap(width = 1216, height = 2640)
        val png = try {
            ByteArrayOutputStream().use { output ->
                frame.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            frame.recycle()
        }

        val capture = VirtualDisplayScreenCapture.capture(
            encodedPngBase64 = Base64.encodeToString(png, Base64.NO_WRAP),
            frameWidth = 1216,
            frameHeight = 2640,
        )
        val image = capture.image
        val contract = capture.contract
        val width = image.width ?: error("缺少图片宽度")
        val height = image.height ?: error("缺少图片高度")

        // 合约发布的就是实际附件尺寸，与 ModelImage 完全一致。
        assertEquals(width, contract.screenshotWidth)
        assertEquals(height, contract.screenshotHeight)
        assertEquals(1216, contract.screenWidth)
        assertEquals(2640, contract.screenHeight)

        // 图像确实被缩放（而不是只改元数据）。
        assertTrue(width < 1216)
        assertTrue(height < 2640)
        assertTrue(maxOf(width, height) <= 1_600)
        assertTrue(width.toLong() * height <= 1_500_000L)
        assertEquals("image/jpeg", image.mimeType)

        val actual = Base64.decode(image.reference.substringAfter(","), Base64.DEFAULT)
        val decoded = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(actual, 0, actual.size, decoded)
        assertEquals(width, decoded.outWidth)
        assertEquals(height, decoded.outHeight)
        assertThrows(IllegalArgumentException::class.java) {
            VirtualDisplayScreenCapture.capture(Base64.encodeToString(png, Base64.NO_WRAP), 100, 100)
        }

        val json = contract.toContractJson()
        assertEquals(width, json.getJSONObject("screenshot").getInt("width"))
        assertEquals(height, json.getJSONObject("screenshot").getInt("height"))
        assertEquals(1216, json.getJSONObject("screen").getInt("width"))
        assertEquals(2640, json.getJSONObject("screen").getInt("height"))
        assertEquals(1216.0 / width, json.getJSONObject("scale_to_screen").getDouble("x"), 1e-9)
        assertEquals(2640.0 / height, json.getJSONObject("scale_to_screen").getDouble("y"), 1e-9)
    }

    @Test
    fun captureRejectsInvalidFrameSize() {
        assertThrows(IllegalArgumentException::class.java) {
            VirtualDisplayScreenCapture.capture("", 0, 2640)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualDisplayScreenCapture.capture("", 1216, -1)
        }
    }

    private fun patternedBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(32, 92, 180)
                strokeWidth = 7f
            }
            val step = (minOf(width, height) / 12).coerceAtLeast(8)
            for (offset in 0 until maxOf(width, height) step step) {
                canvas.drawLine(0f, offset.toFloat(), width.toFloat(), (offset / 2).toFloat(), paint)
                canvas.drawLine(offset.toFloat(), 0f, (offset / 2).toFloat(), height.toFloat(), paint)
            }
        }
}
