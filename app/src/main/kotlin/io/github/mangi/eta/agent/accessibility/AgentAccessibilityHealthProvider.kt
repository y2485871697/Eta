package io.github.mangi.eta.agent.accessibility

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * 向 system_server 暴露最小连接状态，不返回节点、窗口或用户内容。
 */
class AgentAccessibilityHealthProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (
            Binder.getCallingUid() != Process.SYSTEM_UID ||
            method != AccessibilityProtectionProtocol.HEALTH_METHOD ||
            !AccessibilityProtectionProtocol.hasSupportedVersion(extras)
        ) {
            return AccessibilityProtectionProtocol.healthResult(
                AccessibilityProtectionProtocol.HEALTH_STATUS_REJECTED,
            )
        }
        return AccessibilityProtectionProtocol.healthResult(
            if (AgentAccessibilityService.isAvailable()) {
                AccessibilityProtectionProtocol.HEALTH_STATUS_CONNECTED
            } else {
                AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED
            },
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
