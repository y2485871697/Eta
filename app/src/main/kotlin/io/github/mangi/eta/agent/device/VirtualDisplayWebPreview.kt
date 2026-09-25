package io.github.mangi.eta.agent.device

import android.content.Context

/** Started only by the explicit settings button. No listener during normal agent work. */
internal object VirtualDisplayWebPreview {
    private var server: VirtualDisplayPreviewHttpServer? = null
    @Synchronized fun open(context: Context): String {
        stop()
        val app = context.applicationContext
        val next = VirtualDisplayPreviewHttpServer { VirtualDisplaySession.previewFrame(app) }
        return try {
            val ticket = next.start()
            server = next
            ticket.viewerUri
        } catch (ex: Exception) { next.stop(); throw ex }
    }
    @Synchronized fun stop() { server?.stop(); server = null }
    @Synchronized fun isRunning(): Boolean = server != null
}
