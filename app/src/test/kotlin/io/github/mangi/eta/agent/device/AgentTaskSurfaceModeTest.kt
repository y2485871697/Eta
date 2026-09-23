package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskSurfaceModeTest {
    @Test
    fun missingModuleDoesNotSilentlyRewriteBackgroundOrAskToForeground() {
        assertEquals(
            AgentTaskSurfaceMode.BACKGROUND,
            AgentTaskSurfaceMode.resolve(AgentTaskSurfaceMode.BACKGROUND, moduleInstalled = false),
        )
        assertEquals(
            AgentTaskSurfaceMode.ASK,
            AgentTaskSurfaceMode.resolve(AgentTaskSurfaceMode.ASK, moduleInstalled = false),
        )
        assertEquals(
            AgentTaskSurfaceMode.BACKGROUND,
            AgentTaskSurfaceMode.resolve(AgentTaskSurfaceMode.BACKGROUND, moduleInstalled = true),
        )
    }

    @Test
    fun foregroundStaysForegroundWithOrWithoutLegacyModule() {
        assertEquals(
            AgentTaskSurfaceMode.FOREGROUND,
            AgentTaskSurfaceMode.resolve(AgentTaskSurfaceMode.FOREGROUND, moduleInstalled = false),
        )
        assertEquals(
            AgentTaskSurfaceMode.FOREGROUND,
            AgentTaskSurfaceMode.resolve(AgentTaskSurfaceMode.FOREGROUND, moduleInstalled = true),
        )
        assertFalse(AgentTaskSurface.useVirtualDisplay(AgentTaskSurfaceMode.FOREGROUND))
    }

    @Test
    fun backgroundAndAskRefuseInsteadOfUsingForeground() {
        listOf(AgentTaskSurfaceMode.ASK).forEach { mode ->
            val error = assertThrows(VirtualDisplayHandoffNotReadyException::class.java) {
                AgentTaskSurface.useVirtualDisplay(mode)
            }
            assertEquals(VirtualDisplaySession.NOT_READY, error.message)
            assertTrue(error is IllegalStateException)
        }
    }

    @Test
    fun askChoiceDoesNotBlockOrFallBackToForeground() {
        val error = assertThrows(VirtualDisplayHandoffNotReadyException::class.java) {
            AgentTaskPrompt.choice()
        }
        assertEquals(VirtualDisplaySession.NOT_READY, error.message)
        assertFalse(AgentTaskPrompt.pending.value)
        AgentTaskPrompt.answer(AgentTaskSurfaceMode.BACKGROUND)
        assertFalse(AgentTaskPrompt.pending.value)
    }

    @Test
    fun settingsStayReachableWhenStoredBackgroundOrAskHasNoModule() {
        assertTrue(AgentTaskSurface.settingsEntryVisible(moduleInstalled = false, stored = AgentTaskSurfaceMode.BACKGROUND))
        assertTrue(AgentTaskSurface.settingsEntryVisible(moduleInstalled = false, stored = AgentTaskSurfaceMode.ASK))
        assertTrue(AgentTaskSurface.settingsEntryVisible(moduleInstalled = true, stored = AgentTaskSurfaceMode.FOREGROUND))
        assertTrue(AgentTaskSurface.settingsEntryVisible(moduleInstalled = false, stored = AgentTaskSurfaceMode.FOREGROUND))
    }

    @Test
    fun onlyForegroundCanBeSavedAndNonForegroundSummariesAreNotReady() {
        assertTrue(AgentTaskSurface.allowsPersist(AgentTaskSurfaceMode.FOREGROUND))
        assertTrue(AgentTaskSurface.allowsPersist(AgentTaskSurfaceMode.BACKGROUND))
        assertFalse(AgentTaskSurface.allowsPersist(AgentTaskSurfaceMode.ASK))
        assertEquals(
            AgentTaskSurfaceMode.FOREGROUND.labelRes,
            AgentTaskSurface.settingsSummaryRes(AgentTaskSurfaceMode.FOREGROUND),
        )
        assertEquals(
            io.github.mangi.eta.R.string.agent_task_surface_background_summary,
            AgentTaskSurface.settingsSummaryRes(AgentTaskSurfaceMode.BACKGROUND),
        )
        assertEquals(
            io.github.mangi.eta.R.string.agent_task_surface_ask_not_ready,
            AgentTaskSurface.settingsSummaryRes(AgentTaskSurfaceMode.ASK),
        )
    }

    @Test
    fun backgroundPromptRequiresVerifiedExplicitFinish() {
        assertTrue(AgentTaskSurface.useVirtualDisplay(AgentTaskSurfaceMode.BACKGROUND))
        val text=AgentTaskSurface.handoffPromptClause(true,AgentTaskSurfaceMode.BACKGROUND)
        assertTrue(text.contains("keep_virtual_result"))
        assertTrue(text.contains("finish_virtual_session"))
        assertTrue(text.contains("失败保留副屏"))
        assertTrue(text.contains("不得回退主屏"))
        assertFalse(text.contains("进程退出后自动关闭"))
    }

    @Test
    fun backgroundAndAskBlockScreenGuiButForegroundAndOffscreenStayOpen() {
        val blocked = listOf(
            "press_key",
            "scroll",
            "scroll_element",
            "input_text",
            "replace_text",
            "clear_text",
            "paste_text",
            "tap",
            "swipe",
            "observe_screen",
            "wait",
            "wait_for_text",
            "wait_for_package",
            "open_system_panel",
            "launch_app",
            "open_uri",
            "set_alarm",
            "set_timer",
        )
        val open = listOf(
            "inspect_virtual_backend",
            "read_file",
            "browser_use",
            "search_apps",
            "keep_virtual_result",
        )
        listOf(AgentTaskSurfaceMode.BACKGROUND, AgentTaskSurfaceMode.ASK).forEach { mode ->
            blocked.forEach { tool ->
                assertTrue("$tool@$mode", AgentTaskSurface.blocksGuiTool(tool, mode))
            }
            open.forEach { tool ->
                assertFalse("$tool@$mode", AgentTaskSurface.blocksGuiTool(tool, mode))
            }
        }
        blocked.forEach { tool ->
            assertFalse(tool, AgentTaskSurface.blocksGuiTool(tool, AgentTaskSurfaceMode.FOREGROUND))
        }
        open.forEach { tool ->
            assertFalse(tool, AgentTaskSurface.blocksGuiTool(tool, AgentTaskSurfaceMode.FOREGROUND))
        }
    }

    @Test
    fun nonGuiToolsSkipSurfaceReadAndGuiReadErrorsFailClosed() {
        assertFalse(AgentTaskSurface.blocksGuiTool("read_file") { error("prefs") })
        assertFalse(AgentTaskSurface.blocksGuiTool("inspect_virtual_backend") { error("prefs") })
        assertFalse(AgentTaskSurface.blocksGuiTool("browser_use") { error("prefs") })
        assertTrue(AgentTaskSurface.blocksGuiTool("press_key") { error("prefs") })
        assertTrue(AgentTaskSurface.blocksGuiTool("scroll") { error("prefs") })
        assertTrue(AgentTaskSurface.blocksGuiTool("input_text") { error("prefs") })
        assertFalse(AgentTaskSurface.blocksGuiTool("press_key") { AgentTaskSurfaceMode.FOREGROUND })
        assertFalse(AgentTaskSurface.blocksGuiTool("read_file"))
        assertFalse(AgentTaskSurface.blocksGuiTool("inspect_virtual_backend"))
        assertFalse(AgentTaskSurface.blocksGuiTool("browser_use"))
    }
}
