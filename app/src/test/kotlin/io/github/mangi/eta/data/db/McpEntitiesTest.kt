package io.github.mangi.eta.data.db

import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import org.junit.Assert.assertEquals
import org.junit.Test

class McpEntitiesTest {
    @Test
    fun legacyUnavailableReasonIsIgnoredWhenLoadingCachedTools() {
        val entity = McpServerEntity(
            id = "server",
            name = "Server",
            url = "https://example.com/mcp",
            enabled = true,
            protocolMode = McpProtocolMode.AUTO,
            authorizationType = McpAuthorizationType.NONE,
            toolsJson = """[{"name":"create_task","inputSchemaJson":"{\"type\":\"object\",\"anyOf\":[]}","unavailableReason":"暂不支持 schema 关键字 anyOf"}]""",
            enabledToolNamesJson = """["create_task"]""",
            createdAt = 1L,
            sortOrder = 0,
            lastRefreshedAt = null,
            lastProtocolVersion = null,
            toolsExpireAt = null,
        )

        val server = entity.toDomain()

        assertEquals(listOf("create_task"), server.tools.map { it.name })
        assertEquals(listOf("create_task"), server.activeTools.map { it.name })
    }
}
