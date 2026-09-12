package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray

/**
 * 估算下一轮请求里不在对话历史中的固定开销：系统提示、Skills/记忆注入、工具 JSON Schema。
 *
 * 第一轮还没有账单时，把它加进本地估算；已有账单时只把「当前开销 - 账单当时开销」当作差额。
 */
internal object AgentRequestOverhead {
    fun estimate(
        config: AgentModelClient.ModelConfig,
        skillContext: SkillContext = SkillContext.EMPTY,
        memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = false),
        additionalTools: JSONArray = JSONArray(),
    ): Int {
        val systemMessages = AgentPromptBuilder.buildSystemMessages(
            config = config,
            skillContext = skillContext,
            memoryContext = memoryContext,
            rootAvailable = capabilities.rootAvailable,
        )
        var tokens = 0
        for (index in 0 until systemMessages.length()) {
            val content = systemMessages.optJSONObject(index)?.optString("content").orEmpty()
            if (content.isBlank()) continue
            tokens += AgentContextBudget.countMessage(
                AgentModelClient.ConversationMessage(role = "system", content = content),
            )
        }
        val tools = AgentToolCatalog.build(
            terminalTools = config.terminalTools,
            browserTools = config.browserTools,
            deviceDirectTools = config.deviceDirectTools,
            deviceSensitiveReadTools = config.deviceSensitiveReadTools,
            deviceSensitiveActionTools = config.deviceSensitiveActionTools,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = memoryContext.enabled,
            capabilities = capabilities,
        )
        for (index in 0 until additionalTools.length()) {
            additionalTools.opt(index)?.let(tools::put)
        }
        if (tools.length() > 0) {
            tokens += AgentContextBudget.countTokens(tools.toString())
        }
        return tokens
    }
}
