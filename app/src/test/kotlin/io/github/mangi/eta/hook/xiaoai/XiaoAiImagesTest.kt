package io.github.mangi.eta.hook.xiaoai

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoAiImagesTest {
    @Test
    fun blankPathMeansTheRequestHasNoImage() {
        assertTrue(XiaoAiImages.validatePath(null) is XiaoAiImages.Resolution.NoImage)
        assertTrue(XiaoAiImages.validatePath(" ") is XiaoAiImages.Resolution.NoImage)
    }

    @Test
    fun missingAndUnsupportedFilesFailBeforeClaim() {
        val directory = Files.createTempDirectory("eta-xiaoai-images").toFile()
        try {
            val missing = XiaoAiImages.validatePath(File(directory, "missing.png").path)
            assertFailure(XiaoAiImages.FailureCode.FILE_UNREADABLE, missing)

            val unsupported = File(directory, "payload.bin").apply {
                writeBytes("not-an-image".toByteArray())
            }
            assertFailure(
                XiaoAiImages.FailureCode.UNSUPPORTED_FORMAT,
                XiaoAiImages.validatePath(unsupported.path),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun emptyAndOversizedFilesFailBeforeClaim() {
        val directory = Files.createTempDirectory("eta-xiaoai-images").toFile()
        try {
            val empty = File(directory, "empty.png").apply { createNewFile() }
            assertFailure(
                XiaoAiImages.FailureCode.FILE_EMPTY,
                XiaoAiImages.validatePath(empty.path),
            )

            val oversized = File(directory, "oversized.jpg")
            RandomAccessFile(oversized, "rw").use { output ->
                output.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
                output.setLength(12L * 1024L * 1024L + 1L)
            }
            assertFailure(
                XiaoAiImages.FailureCode.FILE_TOO_LARGE,
                XiaoAiImages.validatePath(oversized.path),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun supportedImageMagicCreatesATransferReference() {
        val directory = Files.createTempDirectory("eta-xiaoai-images").toFile()
        try {
            val png = File(directory, "screenshot.data").apply {
                writeBytes(
                    byteArrayOf(
                        0x89.toByte(),
                        0x50,
                        0x4E,
                        0x47,
                        0x0D,
                        0x0A,
                        0x1A,
                        0x0A,
                        0x00,
                    )
                )
            }
            val resolution = XiaoAiImages.validatePath(png.path)

            assertTrue(resolution is XiaoAiImages.Resolution.Success)
            val image = (resolution as XiaoAiImages.Resolution.Success).image
            assertEquals("image/png", image.mimeType)
            assertEquals(png.absolutePath, image.reference)
            assertEquals("xiaoai", image.source)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertFailure(
        expected: XiaoAiImages.FailureCode,
        resolution: XiaoAiImages.Resolution,
    ) {
        assertTrue(resolution is XiaoAiImages.Resolution.Failure)
        assertEquals(expected, (resolution as XiaoAiImages.Resolution.Failure).code)
    }
}
