package io.github.mangi.eta.hook.system

import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceEnforcerTest {
    private val component =
        "io.github.mangi.eta/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    @Test
    fun `adds target while preserving other accessibility services`() {
        assertEquals(component, appendAccessibilityServiceIfMissing(null, component))

        val existing = "example.reader/.ReaderService:example.switch/.SwitchService"
        assertEquals(
            "$existing:$component",
            appendAccessibilityServiceIfMissing(existing, component),
        )
    }

    @Test
    fun `does not duplicate full or short component identity`() {
        assertNull(appendAccessibilityServiceIfMissing(component, component))
        assertNull(
            appendAccessibilityServiceIfMissing(
                "example.reader/.ReaderService",
                "example.reader/example.reader.ReaderService",
            ),
        )
    }

    @Test
    fun `similar and cross package class names are not treated as target`() {
        val similar = "$component.backup"
        assertEquals(
            "$similar:$component",
            appendAccessibilityServiceIfMissing(similar, component),
        )
        assertEquals(
            "io.github.mangi.eta/.SelectToSpeakService:$component",
            appendAccessibilityServiceIfMissing(
                "io.github.mangi.eta/.SelectToSpeakService",
                component,
            ),
        )
    }

    @Test
    fun `repair removes only Eta and keeps service order`() {
        val existing = "example.reader/.ReaderService:$component:example.switch/.SwitchService"

        assertEquals(
            "example.reader/.ReaderService:example.switch/.SwitchService",
            removeAccessibilityServiceIfPresent(existing, component),
        )
        assertTrue(containsAccessibilityService(existing, component))
        assertFalse(
            containsAccessibilityService(
                "example.reader/.ReaderService:example.switch/.SwitchService",
                component,
            ),
        )
    }

    @Test
    fun `repair limiter is bounded and resumes after cooldown`() {
        val limiter = AccessibilityRepairLimiter(
            disabledDurationsMs = longArrayOf(500L, 1_000L, 2_000L),
            cooldownMs = 60_000L,
        )

        assertEquals(AccessibilityRepairAttempt(1, 500L), limiter.nextAttempt(1_000L))
        assertEquals(AccessibilityRepairAttempt(2, 1_000L), limiter.nextAttempt(2_000L))
        assertEquals(AccessibilityRepairAttempt(3, 2_000L), limiter.nextAttempt(3_000L))
        assertNull(limiter.nextAttempt(3_001L))
        assertNull(limiter.nextAttempt(62_999L))
        assertEquals(AccessibilityRepairAttempt(1, 500L), limiter.nextAttempt(63_000L))
    }

    @Test
    fun `restore backoff escalates caps and resets after stable window`() {
        val backoff = AccessibilityRestoreBackoff(
            delaysMs = longArrayOf(300L, 1_000L, 5_000L, 30_000L),
            stableWindowMs = 60_000L,
        )

        assertEquals(300L, backoff.delayFor(0L))
        backoff.recordRestore(1_000L)
        assertEquals(1_000L, backoff.delayFor(1_001L))
        backoff.recordRestore(2_000L)
        assertEquals(5_000L, backoff.delayFor(2_001L))
        backoff.recordRestore(3_000L)
        assertEquals(30_000L, backoff.delayFor(3_001L))
        backoff.recordRestore(4_000L)
        assertEquals(30_000L, backoff.delayFor(4_001L))
        assertEquals(300L, backoff.delayFor(64_000L))
    }

    @Test
    fun `health status requires matching protocol and explicit state`() {
        assertEquals(
            AccessibilityConnectionStatus.CONNECTED,
            accessibilityConnectionStatus(
                AccessibilityProtectionProtocol.VERSION,
                AccessibilityProtectionProtocol.HEALTH_STATUS_CONNECTED,
            ),
        )
        assertEquals(
            AccessibilityConnectionStatus.DISCONNECTED,
            accessibilityConnectionStatus(
                AccessibilityProtectionProtocol.VERSION,
                AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED,
            ),
        )
        assertEquals(
            AccessibilityConnectionStatus.UNKNOWN,
            accessibilityConnectionStatus(
                AccessibilityProtectionProtocol.VERSION - 1,
                AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED,
            ),
        )
        assertEquals(
            AccessibilityConnectionStatus.UNKNOWN,
            accessibilityConnectionStatus(
                AccessibilityProtectionProtocol.VERSION,
                AccessibilityProtectionProtocol.HEALTH_STATUS_REJECTED,
            ),
        )
    }

    @Test
    fun `health checks wait for owner unlock and configured service`() {
        assertFalse(shouldScheduleAccessibilityHealthCheck(false, true))
        assertFalse(shouldScheduleAccessibilityHealthCheck(true, false))
        assertTrue(shouldScheduleAccessibilityHealthCheck(true, true))
    }

    @Test
    fun `control accepts only ordered versioned request from Eta uid`() {
        assertTrue(
            isAccessibilityControlRequestValid(
                ordered = true,
                protocolVersion = AccessibilityProtectionProtocol.VERSION,
                senderUid = 10_123,
                appUid = 10_123,
            ),
        )
        assertFalse(
            isAccessibilityControlRequestValid(
                ordered = false,
                protocolVersion = AccessibilityProtectionProtocol.VERSION,
                senderUid = 10_123,
                appUid = 10_123,
            ),
        )
        assertFalse(
            isAccessibilityControlRequestValid(
                ordered = true,
                protocolVersion = AccessibilityProtectionProtocol.VERSION,
                senderUid = 10_124,
                appUid = 10_123,
            ),
        )
    }

    @Test
    fun `protection defaults to off`() {
        assertFalse(AccessibilityProtectionProtocol.DEFAULT_ENABLED)
    }
}
