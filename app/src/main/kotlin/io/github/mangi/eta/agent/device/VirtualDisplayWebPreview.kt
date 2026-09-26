package io.github.mangi.eta.agent.device

import android.content.Context

/** Started only by the explicit settings button. No listener during normal agent work. */
internal object VirtualDisplayWebPreview {
    private var server: VirtualDisplayPreviewHttpServer? = null

    /** Existing callers always receive a read-only capability. */
    @Synchronized fun open(context: Context): String = openServer(context, control = false)

    /** A new listener and new capabilities; an old preview ticket is never upgraded. */
    @Synchronized fun openWithManualClose(context: Context): String = openServer(context, control = true)

    private fun openServer(context: Context, control: Boolean): String {
        stop()
        val app = context.applicationContext
        val next = VirtualDisplayPreviewHttpServer(
            discover = { VirtualDisplaySession.previewDisplays(app) },
            capture = { selected -> VirtualDisplaySession.previewFrame(app, selected) },
            prepareClose = if (control) { selected ->
                VirtualDisplaySession.prepareManualClose(app, selected)
            } else null,
            commitClose = if (control) { selected, nonce ->
                VirtualDisplaySession.commitManualClose(app, selected, nonce)
            } else null,
        )
        return try {
            val ticket = next.start()
            server = next
            ticket.viewerUri
        } catch (ex: Exception) { next.stop(); throw ex }
    }
    @Synchronized fun stop() { server?.stop(); server = null }
    @Synchronized fun isRunning(): Boolean = server != null
}
