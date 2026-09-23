package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualDisplayCapabilitiesTest {
    @Test fun rootVirtualGuiDoesNotRequirePhysicalAccessibility() {
        val base=AgentToolCapabilities(rootAvailable=true,accessibilityAvailable=false,accessibilityRecoveryAvailable=false)
        for(name in listOf("observe_screen","tap","swipe","paste_text","press_key")) {
            assertEquals("ACCESSIBILITY_UNAVAILABLE",base.unavailableCode(name))
            assertEquals(null,base.copy(virtualDisplay=true).unavailableCode(name))
            assertEquals("ACCESSIBILITY_UNAVAILABLE",base.copy(rootAvailable=false,virtualDisplay=true).unavailableCode(name))
        }
    }
}
