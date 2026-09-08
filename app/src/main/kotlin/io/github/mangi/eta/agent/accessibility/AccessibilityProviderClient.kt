package io.github.mangi.eta.agent.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.github.mangi.eta.accessibility.provider.IEtaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AccessibilityProviderClient(private val context: Context) {

    companion object {
        const val PROVIDER_PACKAGE = "io.github.mangi.eta.accessibility.provider"
        const val PROVIDER_SERVICE = "io.github.mangi.eta.accessibility.provider.RemoteBinderService"
    }

    private var service: IEtaAccessibilityService? = null
    private var connection: ServiceConnection? = null

    suspend fun bind(): Boolean = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { cont ->
            val intent = Intent().apply {
                component = ComponentName(PROVIDER_PACKAGE, PROVIDER_SERVICE)
                `package` = PROVIDER_PACKAGE
            }
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = binder?.let { IEtaAccessibilityService.Stub.asInterface(it) }
                    if (cont.isActive) cont.resume(service != null)
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
            connection = conn
            try {
                val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                if (!ok && cont.isActive) cont.resume(false)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            cont.invokeOnCancellation {
                try {
                    context.unbindService(conn)
                } catch (e: IllegalArgumentException) { }
            }
        }
    }

    fun unbind() {
        connection?.let {
            try {
                context.unbindService(it)
            } catch (e: IllegalArgumentException) { }
            connection = null
        }
        service = null
    }

    fun isConnected(): Boolean {
        return try { service?.isConnected ?: false } catch (e: RemoteException) { false }
    }

    fun performClick(x: Int, y: Int): Boolean {
        return try { service?.performClick(x, y) ?: false } catch (e: RemoteException) { false }
    }

    fun performLongPress(x: Int, y: Int): Boolean {
        return try { service?.performLongPress(x, y) ?: false } catch (e: RemoteException) { false }
    }

    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): Boolean {
        return try { service?.performSwipe(x1, y1, x2, y2, duration) ?: false } catch (e: RemoteException) { false }
    }

    fun setTextOnNode(nodeId: String, text: String): Boolean {
        return try { service?.setTextOnNode(nodeId, text) ?: false } catch (e: RemoteException) { false }
    }

    fun getCurrentActivityName(): String {
        return try { service?.currentActivityName ?: "" } catch (e: RemoteException) { "" }
    }

    fun getUiHierarchy(): String {
        return try { service?.uiHierarchy ?: "" } catch (e: RemoteException) { "" }
    }

    fun takeScreenshot(path: String, format: String): Boolean {
        return try { service?.takeScreenshot(path, format) ?: false } catch (e: RemoteException) { false }
    }
}
