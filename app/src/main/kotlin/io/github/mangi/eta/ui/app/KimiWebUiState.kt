package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.R

internal enum class KimiWebPhase { CHECKING, NOT_INSTALLED, READY, STARTING, RUNNING, FAILED }

internal data class KimiWebUiState(
    val phase: KimiWebPhase = KimiWebPhase.CHECKING,
    val errorCode: String? = null,
) {
    val canStop: Boolean get() = phase == KimiWebPhase.STARTING || phase == KimiWebPhase.RUNNING

    fun actionLabel(context: Context): String = context.getString(when (phase) {
        KimiWebPhase.CHECKING -> R.string.capability_kimi_checking
        KimiWebPhase.NOT_INSTALLED -> R.string.capability_kimi_install
        KimiWebPhase.READY -> R.string.action_launch_kimi_web
        KimiWebPhase.STARTING -> R.string.capability_kimi_preparing
        KimiWebPhase.RUNNING -> R.string.capability_kimi_open
        KimiWebPhase.FAILED -> R.string.capability_kimi_retry
    })
}

/**
 * 菜单只反映“现在能不能开 / 已经在跑”，不把巡检错误当成一次启动失败。
 * 点开溢出菜单和回到前台都会 refresh；已退出的守护记录、临时 Root 不可用
 * 如果映射成 FAILED，用户会看到“启动失败 · 重试”，即使从未点过启动。
 */
internal fun observedKimiWebState(installed: Boolean, running: Boolean): KimiWebUiState = when {
    !installed -> KimiWebUiState(KimiWebPhase.NOT_INSTALLED)
    running -> KimiWebUiState(KimiWebPhase.RUNNING)
    else -> KimiWebUiState(KimiWebPhase.READY)
}

internal fun KimiWebLaunchResult.Failed.message(context: Context): String = context.getString(when (code) {
    "ROOT_REQUIRED" -> R.string.capability_kimi_root_required
    "BACKGROUND_START_NOT_ALLOWED" -> R.string.capability_background_failed
    "KIMI_EXITED", "PROCESS_EXITED" -> R.string.capability_kimi_exited
    "LINUX_ENVIRONMENT_NOT_READY", "PROFILE_NOT_INSTALLED" -> R.string.capability_kimi_not_installed
    "PROOT_UNAVAILABLE" -> R.string.capability_kimi_proot_unavailable
    "LOGS_UNAVAILABLE" -> R.string.capability_kimi_logs_unavailable
    "URL_TIMEOUT" -> R.string.linux_kimi_web_failed_url
    "BROWSER_UNAVAILABLE" -> R.string.linux_kimi_web_failed_browser
    else -> R.string.linux_kimi_web_failed_start
})
