package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogSafetyTest {

    @Test
    fun safeLogType_returnsTypeWithoutExceptionMessage() {
        val secret = "sensitive-runtime-message"

        val rendered = IllegalStateException(secret).safeLogType()

        assertEquals("IllegalStateException", rendered)
        assertFalse(rendered.contains(secret))
    }

    @Test
    fun toSafeLogToken_acceptsStableAsciiTokens() {
        assertEquals("tool.read_file-1", "tool.read_file-1".toSafeLogToken())
        assertEquals("RESULT_OK", "RESULT_OK".toSafeLogToken())
    }

    @Test
    fun toSafeLogToken_rejectsUntrustedOrHighCardinalityValues() {
        assertEquals("unknown", null.toSafeLogToken())
        assertEquals("unknown", "".toSafeLogToken())
        assertEquals("unknown", "tool\nforged-entry".toSafeLogToken())
        assertEquals("unknown", "包含用户内容".toSafeLogToken())
        assertEquals("unknown", "a".repeat(65).toSafeLogToken())
    }
}
