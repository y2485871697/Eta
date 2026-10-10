package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentToolCallValidator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentToolsTest {
    @Test fun mutationRecursionAndUnknownToolsFailClosed() {
        var calls = 0
        val executor = SubAgentTools.guarded { calls++; AgentModelClient.ToolResult("ok") }
        for (name in listOf("terminal", "run_command", "browser_use", "write_file", "tap", "launch_app",
                "memory_write", "delegate_task", "get_task_result", "cancel_task", "mcp_any", "future_tool")) {
            assertFalse(SubAgentTools.allows(name))
            assertTrue(executor.execute(AgentModelClient.ToolCall("id", name, "{}")).content.contains("SUB_AGENT_READ_ONLY"))
        }
        assertEquals(0, calls)
        executor.execute(AgentModelClient.ToolCall("id", "read_file", "{}"))
        assertEquals(1, calls)
    }

    @Test fun catalogContainsOnlyExecutableReads() {
        val all = AgentToolCatalog.build(terminalTools = true, browserTools = true,
            deviceSensitiveReadTools = true, deviceSensitiveActionTools = true, memoryTools = true)
        SubAgentTools.appendTo(all, listOf("worker"))
        val filtered = SubAgentTools.filter(all)
        assertTrue(filtered.length() > 0)
        for (i in 0 until filtered.length()) {
            assertTrue(SubAgentTools.allows(filtered.getJSONObject(i).getJSONObject("function").getString("name")))
        }
        val empty = SubAgentTools.filter(JSONArray())
        assertEquals(0, empty.length())
    }

    @Test fun delegationSchemaEnforcesRequiredTaskAndWorkerBounds() {
        val tools = JSONArray().also { SubAgentTools.appendTo(it, listOf("a", "b")) }
        val validator = AgentToolCallValidator(tools)
        assertNull(validator.validate(AgentModelClient.ToolCall("id", "delegate_task", "{\"task\":\"review\",\"worker\":2}")))
        listOf("{}", "{\"context\":\"only context\"}", "{\"task\":null}", "{\"task\":\"   \"}").forEach { args ->
            assertNotNull(validator.validate(AgentModelClient.ToolCall("id", "delegate_task", args)))
        }
        assertNotNull(validator.validate(AgentModelClient.ToolCall("id", "delegate_task", "{\"task\":\"review\",\"worker\":3}")))
        val description = tools.getJSONObject(0).getJSONObject("function").getString("description")
        assertTrue(description.contains("missing shell is not a reason for the parent to read that source itself"))
        assertTrue(description.contains("A multi-file investigation is not a trivial task"))
        assertFalse(description.contains("handle it yourself"))
        assertTrue(description.contains("continue in the background after a normal parent final reply"))
        assertTrue(description.contains("does not resume previously paused tasks"))
        assertTrue(description.contains("explicit pause, stop, cancel, or failure handling"))
        assertTrue(description.contains("Pending tasks are not completed or verified results"))
    }
}
