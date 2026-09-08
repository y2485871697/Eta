package io.github.mangi.eta.agent.runtime

/** 恢复日志只保留 UI 可见事件；工具参数增量仍只存在于当前模型回合。 */
internal fun AgentEvent.recoveryProjection(): AgentEvent? = when (this) {
    is AgentEvent.AssistantBlockDelta ->
        takeUnless { kind == AgentEvent.AssistantBlockKind.TOOL_CALL }
    else -> this
}
