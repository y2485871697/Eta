package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVirtualRunOverlayPolicyTest {
    @Test
    fun `virtual and unresolved modes never reveal record or dismiss for foreground tool names`() {
        for (mode in listOf(AgentTaskSurfaceMode.BACKGROUND, AgentTaskSurfaceMode.ASK)) {
            assertFalse(AgentOverlayVisibilityPolicy.allowsOverlay(mode))
            for (event in toolEvents()) {
                assertFalse("$mode: $event", AgentOverlayVisibilityPolicy.shouldRevealFor(event, mode))
                assertFalse("$mode: $event", AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event, mode))
                for (ready in listOf(false, true)) {
                    assertFalse(
                        "$mode: $event, ready=$ready",
                        AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, ready, mode),
                    )
                }
            }
            // Terminal presentation must stay quiet even if a previous run set this flag.
            assertFalse(AgentOverlayVisibilityPolicy.shouldShowResultCard(mode, false))
            assertFalse(AgentOverlayVisibilityPolicy.shouldShowResultCard(mode, true))
        }
    }

    @Test
    fun `foreground mode preserves existing tool and terminal presentation rules`() {
        val mode = AgentTaskSurfaceMode.FOREGROUND
        assertTrue(AgentOverlayVisibilityPolicy.allowsOverlay(mode))
        for (event in toolEvents()) {
            assertEquals(
                AgentOverlayVisibilityPolicy.shouldRevealFor(event),
                AgentOverlayVisibilityPolicy.shouldRevealFor(event, mode),
            )
            assertEquals(
                AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event),
                AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event, mode),
            )
            for (ready in listOf(false, true)) {
                assertEquals(
                    AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, ready),
                    AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, ready, mode),
                )
            }
        }
        assertFalse(AgentOverlayVisibilityPolicy.shouldShowResultCard(mode, false))
        assertTrue(AgentOverlayVisibilityPolicy.shouldShowResultCard(mode, true))
    }

    private fun toolEvents(): List<AgentEvent> = listOf(
        "launch_app", "open_uri", "observe_screen", "tap", "tap_area", "tap_element",
        "long_press", "long_press_element", "swipe", "scroll", "scroll_element",
        "input_text", "replace_text", "clear_text", "paste_text", "press_key",
        "open_system_panel", "set_alarm", "set_timer", "run_command", "search_apps",
    ).flatMap { name ->
        listOf(
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = name),
            AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = name, contentChars = 2),
            AgentEvent.AssistantReceived(1, 0, "", listOf(name)),
            AgentEvent.ToolStarted(1, "call", name, "{}"),
            AgentEvent.ToolFinished(1, "call", name, "ok", 0, 0),
            AgentEvent.ToolImagesAttached(1, name, 1, 1024),
        )
    }
}
