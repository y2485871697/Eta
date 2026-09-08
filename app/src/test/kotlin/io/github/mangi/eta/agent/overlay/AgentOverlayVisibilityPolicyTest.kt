package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOverlayVisibilityPolicyTest {
    @Test
    fun `text-only and background tool events do not reveal operation overlay`() {
        val events = listOf(
            AgentEvent.RunStarted(
                initialImages = 0,
                initialImageBytes = 0,
                toolCount = 24,
                terminalTools = false
            ),
            AgentEvent.RoundStarted(round = 1, messageCount = 2),
            AgentEvent.ProviderRequestStarted(round = 1),
            AgentEvent.ProviderResponseStarted(round = 1, httpCode = 200),
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 7,
                delta = "thinking"
            ),
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 1,
                deltaChars = 5,
                delta = "hello"
            ),
            AgentEvent.AssistantReceived(
                round = 1,
                contentChars = 5,
                reasoningContent = "",
                toolNames = emptyList()
            ),
            AgentEvent.AssistantBlockEnd(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
                index = 2,
                blockId = "call_terminal",
                name = "terminal",
                contentChars = 12
            ),
            AgentEvent.AssistantReceived(
                round = 1,
                contentChars = 0,
                reasoningContent = "",
                toolNames = listOf("run_command", "search_apps")
            ),
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_status",
                name = "run_command",
                argsPreview = "{}"
            ),
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_status",
                name = "run_command",
                resultSummary = "ok",
                imageCount = 0,
                imageBytes = 0
            ),
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_browser",
                name = "browser_use",
                argsPreview = "提取正文 · example.com"
            ),
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_browser",
                name = "browser_use",
                resultSummary = "ok=true, action=get_readable, host=example.com",
                imageCount = 0,
                imageBytes = 0
            ),
            AgentEvent.RunFinished(round = 1, contentChars = 5)
        )

        assertFalse(events.any(AgentOverlayVisibilityPolicy::shouldRevealFor))
    }

    @Test
    fun `screen observation reveals operation overlay after observation finishes`() {
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call_observe",
                    name = "observe_screen",
                    argsPreview = "{}"
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ToolFinished(
                    round = 1,
                    toolCallId = "call_observe",
                    name = "observe_screen",
                    resultSummary = "ok",
                    imageCount = 1,
                    imageBytes = 2048
                )
            )
        )
    }

    @Test
    fun `foreground operation tools reveal operation overlay before execution`() {
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.AssistantBlockEnd(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
                    index = 0,
                    blockId = "call_1",
                    name = "tap",
                    contentChars = 12
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.AssistantReceived(
                    round = 1,
                    contentChars = 0,
                    reasoningContent = "",
                    toolNames = listOf("search_apps", "launch_app")
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call_1",
                    name = "tap",
                    argsPreview = "{}"
                )
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                AgentEvent.ToolFinished(
                    round = 1,
                    toolCallId = "call_1",
                    name = "tap",
                    resultSummary = "ok",
                    imageCount = 0,
                    imageBytes = 0
                )
            )
        )
    }

    @Test
    fun `screen observation dismisses external entry surface before execution`() {
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(
                AgentEvent.AssistantReceived(
                    round = 1,
                    contentChars = 0,
                    reasoningContent = "",
                    toolNames = listOf("observe_screen")
                )
            )
        )
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call_status",
                    name = "run_command",
                    argsPreview = "{}"
                )
            )
        )
    }

    @Test
    fun `clock direct action dismisses entry surface without revealing operation overlay`() {
        val event = AgentEvent.ToolStarted(
            round = 1,
            toolCallId = "call_alarm",
            name = "set_alarm",
            argsPreview = "参数已接收",
        )

        assertTrue(AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event))
        assertFalse(AgentOverlayVisibilityPolicy.shouldRevealFor(event))
        assertFalse(AgentOverlayVisibilityPolicy.isForegroundOperationTool("set_alarm"))
    }

    @Test
    fun `foreground execution is recorded only after entry surface is ready`() {
        val planned = AgentEvent.AssistantReceived(
            round = 1,
            contentChars = 0,
            reasoningContent = "",
            toolNames = listOf("launch_app"),
        )
        val started = AgentEvent.ToolStarted(
            round = 1,
            toolCallId = "call_launch",
            name = "launch_app",
            argsPreview = "参数已接收",
        )

        assertFalse(
            AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                planned,
                entrySurfaceReady = true,
            )
        )
        assertFalse(
            AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                started,
                entrySurfaceReady = false,
            )
        )
        assertTrue(
            AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                started,
                entrySurfaceReady = true,
            )
        )
    }
}
