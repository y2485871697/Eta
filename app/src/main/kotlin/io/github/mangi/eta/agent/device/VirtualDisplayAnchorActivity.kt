package io.github.mangi.eta.agent.device

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Internal temporary secondary-display cover; never appears in recents. */
class VirtualDisplayAnchorActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (display?.displayId == null || display?.displayId == 0 || intent?.data?.scheme != "eta-vd-anchor") {
            finishAndRemoveTask()
            return
        }
        setContentView(TextView(this).apply {
            text = "正在移交后台任务"
            setBackgroundColor(android.graphics.Color.BLACK)
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
        })
    }
}
