package io.github.mangi.eta.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.ui.model.AgentContextUsageUi

internal data class AgentContextTelemetry(
    val children: List<SubAgentContextStats> = emptyList(),
    val mainModelName: String = "",
    val compactingModelName: String = "",
    val selectedTaskId: String? = null,
    val onTaskSelected: ((String?) -> Unit)? = null,
)
internal val LocalAgentContextTelemetry = staticCompositionLocalOf { AgentContextTelemetry() }

/** Legacy telemetry can still be replayed after upgrading. Its projection and
 * compaction estimates must never be presented as cloud-measured occupancy. */
internal fun SubAgentContextStats.cloudContextUsage(): AgentContextUsageUi = AgentContextUsageUi(
    contextTokens = contextTokens?.takeIf { !projected && it > 0 },
    contextWindow = contextWindow,
)

internal fun SubAgentContextStats.contextLabel(): String {
    val roleLabel = when (role) {
        "implementation" -> "实现"
        "review" -> "审查"
        "summary" -> "总结"
        "image_generation" -> "图片生成"
        "video_generation" -> "视频生成"
        else -> "研究"
    }
    return "$modelName（${agentName.ifBlank { roleLabel }} ${taskId.take(6)}）"
}

internal fun SubAgentContextStats.contextStatusLabel(): String = when {
    manualCompactionState == "pending" -> "等待压缩"
    isCompacting -> "正在压缩"
    status == "queued" -> "排队中"
    status == "running" -> "执行中"
    status == "completed" -> "已完成"
    status == "awaiting_decision" -> "超时待主代理决定"
    status == "timed_out" -> "已超时"
    status == "cancelled" -> "已取消"
    else -> "失败"
}
