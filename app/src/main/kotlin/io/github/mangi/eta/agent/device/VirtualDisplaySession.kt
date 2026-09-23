package io.github.mangi.eta.agent.device

/**
 * 本阶段没有可激活的虚拟副屏会话。
 * 旧的 vd start/stop、进程监视和 keep 文件都已删除。
 * 任何启动、停止或保留调用都明确失败，不能报告成功。
 */
internal object VirtualDisplaySession {
    const val NOT_READY = "VIRTUAL_DISPLAY_HANDOFF_NOT_READY"

    fun onRunStarted(): Nothing = refuse()

    fun onRunFinished(): Nothing = refuse()

    fun keep(@Suppress("UNUSED_PARAMETER") packageName: String): Nothing = refuse()

    fun engage(): Nothing = refuse()

    private fun refuse(): Nothing = throw VirtualDisplayHandoffNotReadyException()
}

internal class VirtualDisplayHandoffNotReadyException :
    IllegalStateException(VirtualDisplaySession.NOT_READY)
