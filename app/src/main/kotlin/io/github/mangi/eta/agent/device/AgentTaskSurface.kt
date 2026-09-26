package io.github.mangi.eta.agent.device

import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AgentTaskSurfaceMode(val wire: String, val labelRes: Int) {
    ASK("ask", R.string.agent_task_surface_ask),
    FOREGROUND("foreground", R.string.agent_task_surface_foreground),
    BACKGROUND("background", R.string.agent_task_surface_background);

    companion object {
        fun fromWire(value: String?): AgentTaskSurfaceMode =
            entries.firstOrNull { it.wire == value } ?: FOREGROUND

        /**
         * 模块是否安装都不能改写存储值。
         * 缺模块时把 BACKGROUND/ASK 收成 FOREGROUND 会静默改走前台；
         * 模块在场也不表示本阶段的副屏交接已经可用。
         */
        fun resolve(
            stored: AgentTaskSurfaceMode,
            @Suppress("UNUSED_PARAMETER") moduleInstalled: Boolean,
        ): AgentTaskSurfaceMode = stored
    }
}

/**
 * 前台保持直接在当前屏幕执行。
 * 后台，以及本阶段无法安全跨 run 询问的 ASK，都明确拒绝。
 * 设置入口仅在后端模块已安装时可见；不改变已保存的执行模式。
 */
internal object AgentTaskSurface {
    const val PREF_KEY = "agent_task_surface"

    private val traditionalScreenGuiTools: Set<String> = setOf(
        "observe_screen",
        "wait",
        "wait_for_text",
        "wait_for_package",
        "input_text",
        "replace_text",
        "clear_text",
        "paste_text",
        "press_key",
        "tap",
        "tap_area",
        "tap_element",
        "long_press",
        "long_press_element",
        "swipe",
        "scroll",
        "scroll_element",
        "open_system_panel",
        "launch_app",
        "open_uri",
        // 直达失败时可能只打开时钟界面。
        "set_alarm",
        "set_timer",
    )

    fun moduleInstalled(): Boolean {
        val binary = File("/system/bin/vd")
        if (binary.isFile && binary.canExecute()) return true
        return File("/data/adb/modules/agent_mobile_use/module.prop").isFile
    }

    fun stored(): AgentTaskSurfaceMode =
        AgentTaskSurfaceMode.fromWire(Prefs.getString(PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire))

    fun allowsPersist(mode: AgentTaskSurfaceMode): Boolean = mode != AgentTaskSurfaceMode.ASK

    fun save(mode: AgentTaskSurfaceMode) {
        if (!allowsPersist(mode)) throw VirtualDisplayHandoffNotReadyException()
        Prefs.putString(PREF_KEY, mode.wire)
    }

    fun effective(): AgentTaskSurfaceMode = AgentTaskSurfaceMode.resolve(stored(), moduleInstalled())

    fun settingsEntryVisible(): Boolean = settingsEntryVisible(moduleInstalled(), stored())

    fun settingsEntryVisible(moduleInstalled: Boolean, @Suppress("UNUSED_PARAMETER") stored: AgentTaskSurfaceMode): Boolean =
        moduleInstalled

    fun settingsSummaryRes(stored: AgentTaskSurfaceMode): Int = when (stored) {
        AgentTaskSurfaceMode.FOREGROUND -> stored.labelRes
        AgentTaskSurfaceMode.BACKGROUND -> R.string.agent_task_surface_background_summary
        AgentTaskSurfaceMode.ASK -> R.string.agent_task_surface_ask_not_ready
    }

    fun handoffPromptClause(): String {
        val storedMode = runCatching { stored() }.getOrDefault(AgentTaskSurfaceMode.BACKGROUND)
        val installed = runCatching { moduleInstalled() }.getOrDefault(false)
        return handoffPromptClause(moduleInstalled = installed, stored = storedMode)
    }

    fun handoffPromptClause(
        @Suppress("UNUSED_PARAMETER") moduleInstalled: Boolean,
        stored: AgentTaskSurfaceMode,
    ): String = when (stored) {
        AgentTaskSurfaceMode.FOREGROUND -> ""
        AgentTaskSurfaceMode.BACKGROUND ->
            "本次选择实验性后台副屏。GUI 不得回退主屏；先 launch_app 精确包名、observe_screen 截图再坐标操作。节点、系统面板及不支持的工具会明确拒绝。任务完成前必须 keep_virtual_result 标记交付任务，再 finish_virtual_session，只有返回 handedOff=true 且 released=true 才可声称交付完成。失败保留副屏，禁止杀进程或用终端绕过关闭。提示用户期间不要从桌面启动或清理正在操作的应用。"
        AgentTaskSurfaceMode.ASK -> "每次询问尚未支持，请明确选择前台或后台。"
    }

    fun useVirtualDisplay(): Boolean = useVirtualDisplay(stored())

    fun useVirtualDisplay(stored: AgentTaskSurfaceMode): Boolean = when (stored) {
        AgentTaskSurfaceMode.FOREGROUND -> false
        AgentTaskSurfaceMode.BACKGROUND -> true
        AgentTaskSurfaceMode.ASK -> throw VirtualDisplayHandoffNotReadyException()
    }

    /**
     * 非 GUI 工具立即返回，不读取 Prefs。
     * GUI 工具读取失败时拒绝，避免异常被当成前台放行。
     */
    fun blocksGuiTool(toolName: String): Boolean = blocksGuiTool(toolName, readMode = { stored() })

    fun blocksGuiTool(toolName: String, mode: AgentTaskSurfaceMode): Boolean =
        mode != AgentTaskSurfaceMode.FOREGROUND && isTraditionalScreenGuiTool(toolName)

    fun blocksGuiTool(toolName: String, readMode: () -> AgentTaskSurfaceMode): Boolean {
        if (!isTraditionalScreenGuiTool(toolName)) return false
        val mode = runCatching(readMode).getOrNull() ?: return true
        return blocksGuiTool(toolName, mode)
    }

    fun isTraditionalScreenGuiTool(toolName: String): Boolean =
        toolName.trim() in traditionalScreenGuiTools
}

/**
 * 保留给既有界面引用的兼容接口。
 * 本阶段不再挂起线程，也不再用全局状态管理 run。
 */
internal object AgentTaskPrompt {
    private val pendingState = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = pendingState.asStateFlow()

    fun clear() = Unit

    fun choice(): AgentTaskSurfaceMode = throw VirtualDisplayHandoffNotReadyException()

    fun answer(mode: AgentTaskSurfaceMode) {
        if (mode == AgentTaskSurfaceMode.BACKGROUND ||
            mode == AgentTaskSurfaceMode.FOREGROUND ||
            mode == AgentTaskSurfaceMode.ASK
        ) {
            pendingState.value = false
        }
    }
}
