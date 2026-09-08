package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleConfigEntryPackagesTest {
    @Test
    fun runtimeOnlyTrustsTheKnownAssistantEntryPackages() {
        assertEquals(
            setOf("com.heytap.speechassist", "com.miui.voiceassist"),
            ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES,
        )
        assertTrue(ModuleConfig.XIAOAI_CORE_PROCESS.endsWith(":core"))
        assertEquals("com.oplus.aimemory", ModuleConfig.COLOROS_MEMORY_PACKAGE)
    }
}
