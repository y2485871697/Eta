package io.github.mangi.eta.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskPrompt
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode

@Composable
internal fun AgentTaskSurfacePrompt() {
    val pending by AgentTaskPrompt.pending.collectAsState()
    if (!pending) return
    AlertDialog(
        onDismissRequest = { AgentTaskPrompt.answer(AgentTaskSurfaceMode.FOREGROUND) },
        title = { Text(stringResource(R.string.agent_task_surface_prompt_title)) },
        text = { Text(stringResource(R.string.agent_task_surface_prompt_body)) },
        confirmButton = {
            TextButton(onClick = { AgentTaskPrompt.answer(AgentTaskSurfaceMode.BACKGROUND) }) {
                Text(stringResource(R.string.agent_task_surface_background))
            }
        },
        dismissButton = {
            TextButton(onClick = { AgentTaskPrompt.answer(AgentTaskSurfaceMode.FOREGROUND) }) {
                Text(stringResource(R.string.agent_task_surface_foreground))
            }
        },
    )
}
