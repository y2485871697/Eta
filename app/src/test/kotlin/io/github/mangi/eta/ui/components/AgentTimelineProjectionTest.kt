package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTimelineProjectionTest {
    @Test fun progressTextBetweenToolsDoesNotSplitOneWorkCard() {
        val messages = listOf<AgentChatMessageUi>(
            UserMessageUi("user-run", "去美团操作"),
            ToolActivityMessageUi("run-tool-1-call-1", "tap", ToolActivityStatusUi.Success, "点击"),
            AgentMessageUi("assistant-run-1-0", "继续检查页面"),
            ToolActivityMessageUi("run-tool-2-call-2", "observe_screen", ToolActivityStatusUi.Success, "读取页面"),
            AgentMessageUi("assistant-run-2-result", "已完成"),
        )

        val entries = messages.toTimelineEntries()
        assertEquals(3, entries.size)
        val work = entries[1] as AgentTimelineEntry.WorkProcess
        assertEquals(
            listOf("run-tool-1-call-1", "assistant-run-1-0", "run-tool-2-call-2"),
            work.messages.map { it.id },
        )
        assertEquals(2, work.messages.count { it is ToolActivityMessageUi })
        assertEquals("assistant-run-2-result", (entries[2] as AgentTimelineEntry.Message).message.id)
    }

    @Test fun differentRunsStillRemainSeparateWorkCards() {
        val messages = listOf<AgentChatMessageUi>(
            UserMessageUi("user-one", "第一轮"),
            ToolActivityMessageUi("run-one-tool-1-call", "tap", ToolActivityStatusUi.Success, "点击"),
            AgentMessageUi("assistant-run-one-1-0", "第一轮说明"),
            ToolActivityMessageUi("run-two-tool-1-call", "tap", ToolActivityStatusUi.Success, "点击"),
        )

        val entries = messages.toTimelineEntries().filterIsInstance<AgentTimelineEntry.WorkProcess>()
        assertEquals(2, entries.size)
        assertEquals(1, entries[0].messages.count { it is ToolActivityMessageUi })
        assertEquals(1, entries[1].messages.count { it is ToolActivityMessageUi })
    }
}
