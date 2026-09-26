package io.github.mangi.eta.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.agent.device.AgentTaskSurface

/** Installation, not a live-session or transient Root probe. Recheck on returning to settings. */
@Composable
internal fun rememberTaskBackendInstalled(): Boolean {
    val owner = LocalLifecycleOwner.current
    var installed by remember { mutableStateOf(runCatching { AgentTaskSurface.moduleInstalled() }.getOrDefault(false)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installed = runCatching { AgentTaskSurface.moduleInstalled() }.getOrDefault(false)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return installed
}
