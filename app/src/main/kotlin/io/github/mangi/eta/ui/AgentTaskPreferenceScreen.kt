package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun AgentTaskPreferenceScreen() {
    val view = LocalView.current
    var selected by remember { mutableStateOf(AgentTaskSurface.stored()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            AgentTaskSurfaceMode.entries.forEach { mode ->
                val canSelect = AgentTaskSurface.allowsPersist(mode)
                BasicComponent(
                    title = stringResource(mode.labelRes),
                    summary = stringResource(mode.summaryRes()),
                    onClick = {
                        if (!canSelect) return@BasicComponent
                        TouchHaptics.click(view)
                        selected = mode
                        AgentTaskSurface.save(mode)
                    },
                    endActions = {
                        RadioButton(
                            selected = selected == mode,
                            enabled = canSelect || selected == mode,
                            onClick = {
                                if (!canSelect) return@RadioButton
                                TouchHaptics.click(view)
                                selected = mode
                                AgentTaskSurface.save(mode)
                            },
                        )
                    },
                )
            }
        }
        Text(
            text = stringResource(R.string.agent_task_surface_not_ready_hint),
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
        )
    }
}

private fun AgentTaskSurfaceMode.summaryRes(): Int = when (this) {
    AgentTaskSurfaceMode.ASK -> R.string.agent_task_surface_ask_not_ready
    AgentTaskSurfaceMode.FOREGROUND -> R.string.agent_task_surface_foreground_summary
    AgentTaskSurfaceMode.BACKGROUND -> R.string.agent_task_surface_background_summary
}
