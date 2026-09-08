package io.github.mangi.eta.agent.accessibility

import android.net.Uri
import android.os.Bundle

/**
 * Eta App 与 system_server 无障碍保护后端之间的最小协议。
 *
 * 控制广播由 signature 权限、协议版本和发送者 UID 共同校验；健康检查 Provider
 * 只接受 system UID。协议不授予 App 写 Secure Settings 的能力。
 */
internal object AccessibilityProtectionProtocol {
    const val VERSION = 1

    const val ACTION_SET =
        "io.github.mangi.eta.action.SET_ACCESSIBILITY_PROTECTION"
    const val ACTION_RECOVER =
        "io.github.mangi.eta.action.RECOVER_ACCESSIBILITY_SERVICE"
    const val PERMISSION =
        "io.github.mangi.eta.permission.CONTROL_ACCESSIBILITY_PROTECTION"
    const val RECEIVER_PACKAGE = "android"

    const val EXTRA_PROTOCOL_VERSION = "protocol_version"
    const val EXTRA_ENABLED = "enabled"

    const val RESULT_UNAVAILABLE = 0
    const val RESULT_APPLIED = 1
    const val RESULT_REJECTED = 2

    const val SETTING_NAME = "eta_accessibility_protection_enabled"
    const val DEFAULT_ENABLED = false

    const val HEALTH_AUTHORITY = "io.github.mangi.eta.accessibility.health"
    const val HEALTH_METHOD = "accessibility_health"
    const val HEALTH_STATUS = "status"
    const val HEALTH_STATUS_CONNECTED = "connected"
    const val HEALTH_STATUS_DISCONNECTED = "disconnected"
    const val HEALTH_STATUS_REJECTED = "rejected"

    val HEALTH_URI: Uri = Uri.parse("content://$HEALTH_AUTHORITY")

    fun request(): Bundle = Bundle().apply {
        putInt(EXTRA_PROTOCOL_VERSION, VERSION)
    }

    fun hasSupportedVersion(bundle: Bundle?): Boolean =
        bundle?.getInt(EXTRA_PROTOCOL_VERSION, -1) == VERSION

    fun healthResult(status: String): Bundle = Bundle().apply {
        putInt(EXTRA_PROTOCOL_VERSION, VERSION)
        putString(HEALTH_STATUS, status)
    }
}
