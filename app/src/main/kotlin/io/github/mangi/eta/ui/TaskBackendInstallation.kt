package io.github.mangi.eta.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.agent.device.AgentTaskSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** Null means checking, not an absent module. Never probe the live owner. */
@Composable
internal fun rememberTaskBackendInstalled(): Boolean? {
    val owner = LocalLifecycleOwner.current
    val installed by produceState<Boolean?>(initialValue = null, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = withContext(Dispatchers.IO) {
                runCatching { AgentTaskSurface.moduleInstalled() }.getOrDefault(false)
            }
            awaitCancellation()
        }
    }
    return installed
}
