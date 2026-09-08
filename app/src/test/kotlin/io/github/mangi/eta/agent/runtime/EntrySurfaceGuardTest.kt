package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.accessibility.PackageWindowVisibility
import io.github.mangi.eta.core.AgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EntrySurfaceGuardTest {
    @Test
    fun disabledHandoffDoesNotCreateGuard() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "breeno", dismiss = false),
            logger = NoOpLogger,
        )

        assertNull(guard)
    }

    @Test
    fun breenoGuardKeepsExclusionUntilTheFirstScreenshotConsumesIt() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "breeno", dismiss = true),
            logger = NoOpLogger,
        )

        assertNotNull(guard)
        assertEquals("com.heytap.speechassist", guard?.targetPackageName)
        assertEquals(setOf("com.heytap.speechassist"), guard?.consumeScreenshotExcludedPackages())
        assertTrue(guard?.consumeScreenshotExcludedPackages().orEmpty().isEmpty())
    }

    @Test
    fun xiaoAiGuardUsesOnlyTheXiaoAiPackage() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "xiaoai", dismiss = true),
            logger = NoOpLogger,
        )

        assertNotNull(guard)
        assertEquals("com.miui.voiceassist", guard?.targetPackageName)
        assertEquals(setOf("com.miui.voiceassist"), guard?.consumeScreenshotExcludedPackages())
    }

    @Test
    fun etaVoiceGuardExcludesEtaUntilTheVoiceWindowIsDismissed() {
        val dismissCalls = AtomicInteger()
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(
                source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                dismiss = true,
            ),
            logger = NoOpLogger,
            etaVoiceSurfaceDismissal = {
                dismissCalls.incrementAndGet()
                true
            },
        )

        assertNotNull(guard)
        assertEquals("io.github.mangi.eta", guard?.targetPackageName)
        assertTrue(guard?.dismissOnce() == true)
        assertTrue(guard?.dismissOnce() == true)
        assertEquals(1, dismissCalls.get())
        assertEquals(setOf("io.github.mangi.eta"), guard?.consumeScreenshotExcludedPackages())
    }

    @Test
    fun etaVoiceOwnedDismissalCanRetryWithoutSendingBackToTheUnderlyingApp() {
        val dismissCalls = AtomicInteger()
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(
                source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                dismiss = true,
            ),
            logger = NoOpLogger,
            etaVoiceSurfaceDismissal = {
                dismissCalls.incrementAndGet() >= 2
            },
        )

        assertFalse(guard?.dismissOnce() == true)
        assertTrue(guard?.dismissOnce() == true)
        assertEquals(2, dismissCalls.get())
    }

    @Test
    fun unknownEntryStillCreatesDismissGuardWithoutGuessingAPackage() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "future_entry", dismiss = true),
            logger = NoOpLogger,
        )

        assertNotNull(guard)
        assertNull(guard?.targetPackageName)
        assertTrue(guard?.consumeScreenshotExcludedPackages().orEmpty().isEmpty())
    }

    @Test
    fun knownEntryAlreadyGoneMustNotSendBackIntoUnderlyingApp() {
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.ALREADY_GONE,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = "com.heytap.speechassist",
                visibility = PackageWindowVisibility.GONE,
            ),
        )
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.SEND_BACK,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = "com.heytap.speechassist",
                visibility = PackageWindowVisibility.VISIBLE,
            ),
        )
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.SEND_BACK,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = null,
                visibility = null,
            ),
        )
    }

    @Test
    fun unknownVisibilityDefersWithoutClearingScreenshotExclusion() {
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.DEFER,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = "com.heytap.speechassist",
                visibility = PackageWindowVisibility.UNKNOWN,
            ),
        )
    }

    private fun handoff(source: String, dismiss: Boolean) =
        AgentRuntimeWire.EntryHandoff(
            id = "run-1",
            source = source,
            payload = "{}",
            dismissEntrySurfaceOnForegroundOperation = dismiss,
        )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
