package io.github.mangi.eta.agent.runtime

import java.util.UUID

/** 记录 checkpoint 的创建进程，仅供诊断；恢复状态由 Runtime 对账决定。 */
internal object AgentRuntimeProcessIdentity {
    val id: String = UUID.randomUUID().toString()
}
