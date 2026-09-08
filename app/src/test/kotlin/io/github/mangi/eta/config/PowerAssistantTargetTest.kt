package io.github.mangi.eta.config

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerAssistantTargetTest {
    @Test
    fun `valid persisted values select matching target`() {
        assertEquals(
            PowerAssistantTarget.OEM,
            PowerAssistantTarget.resolve("oem", legacyPowerKeyTakeover = true),
        )
        assertEquals(
            PowerAssistantTarget.GEMINI,
            PowerAssistantTarget.resolve("gemini", legacyPowerKeyTakeover = false),
        )
        assertEquals(
            PowerAssistantTarget.ETA,
            PowerAssistantTarget.resolve("eta", legacyPowerKeyTakeover = false),
        )
    }

    @Test
    fun `missing persisted value preserves legacy Gemini takeover`() {
        assertEquals(
            PowerAssistantTarget.GEMINI,
            PowerAssistantTarget.resolve(null, legacyPowerKeyTakeover = true),
        )
    }

    @Test
    fun `missing persisted value preserves legacy OEM behavior`() {
        assertEquals(
            PowerAssistantTarget.OEM,
            PowerAssistantTarget.resolve(null, legacyPowerKeyTakeover = false),
        )
    }

    @Test
    fun `unknown persisted value falls back to legacy setting`() {
        assertEquals(
            PowerAssistantTarget.GEMINI,
            PowerAssistantTarget.resolve("unknown", legacyPowerKeyTakeover = true),
        )
        assertEquals(
            PowerAssistantTarget.OEM,
            PowerAssistantTarget.resolve("unknown", legacyPowerKeyTakeover = false),
        )
    }
}
