package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayToolContractTest {
    @Test
    fun inspectVirtualBackendIsRootOnlyAndTakesNoArguments() {
        val rooted = catalog(rootAvailable = true)
        assertTrue("inspect_virtual_backend" in rooted.names())
        assertEquals(
            RootRequirement.REQUIRED,
            AgentToolRequirements.rootRequirement("inspect_virtual_backend"),
        )
        val function = rooted.function("inspect_virtual_backend")
        val parameters = function.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals(0, parameters.getJSONObject("properties").length())
        assertEquals(0, parameters.optJSONArray("required")?.length() ?: 0)
        val description = function.getString("description")
        assertTrue(description.contains("mutations_enabled"))
        assertTrue(description.contains("session_authenticated"))
        assertTrue(description.contains("false"))
        assertTrue(description.contains("不等于授权") || description.contains("不是授权"))
        assertFalse(description.contains("vd start"))

        val rootless = AgentToolRequirements.project(rooted, rootAvailable = false)
        assertFalse("inspect_virtual_backend" in rootless.names())
        assertEquals(
            "ROOT_REQUIRED",
            AgentToolCapabilities(rootAvailable = false).unavailableCode("inspect_virtual_backend"),
        )
        assertEquals(
            null,
            AgentToolCapabilities(rootAvailable = true).unavailableCode("inspect_virtual_backend"),
        )
    }

    @Test
    fun keepVirtualResultRemainsCallableButDoesNotPromiseRetentionOrCleanup() {
        assertEquals(RootRequirement.NONE, AgentToolRequirements.rootRequirement("keep_virtual_result"))
        val description = catalog(rootAvailable = false).function("keep_virtual_result").getString("description")
        assertTrue(description.contains("尚未就绪"))
        assertFalse(description.contains("关掉"))
        assertFalse(description.contains("关闭副屏"))
        assertFalse(description.contains("中间应用不要"))
        assertFalse(description.contains("都会被关掉"))
        assertTrue("keep_virtual_result" in catalog(rootAvailable = false).names())
    }

    private fun catalog(rootAvailable: Boolean): JSONArray = AgentToolCatalog.build(
        terminalTools = false,
        browserTools = false,
        capabilities = AgentToolCapabilities(rootAvailable = rootAvailable),
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .map { getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == name }
}
