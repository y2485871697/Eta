package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.PreferenceIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun AgentTaskPreferenceScreen(onBack: () -> Unit, onOpenRecovery: () -> Unit) {
    val moduleInstalled = rememberTaskBackendInstalled()
    if (moduleInstalled != true) {
        LaunchedEffect(moduleInstalled) { if (moduleInstalled == false) onBack() }
        return
    }
    val view = LocalView.current
    var selected by remember { mutableStateOf(AgentTaskSurface.stored()) }
    // A navigation destination must draw its own full-page background and top bar.
    // A bare transparent Column let the settings page show through behind this Card.
    MiuixScaffoldPage(
        title = stringResource(R.string.agent_task_surface_title),
        onBack = onBack,
    ) {
        item(key = "agent_task_modes") {
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
        }
        item(key = "virtual_display_recovery") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.vd_recovery_title),
                    summary = stringResource(R.string.vd_recovery_explanation),
                    startAction = { PreferenceIcon(Icons.Rounded.Layers) },
                    onClick = onOpenRecovery,
                )
            }
        }
        item(key = "agent_task_hint") {
            Text(
                text = stringResource(R.string.agent_task_surface_not_ready_hint),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
    }
}

private fun AgentTaskSurfaceMode.summaryRes(): Int = when (this) {
    AgentTaskSurfaceMode.ASK -> R.string.agent_task_surface_ask_not_ready
    AgentTaskSurfaceMode.FOREGROUND -> R.string.agent_task_surface_foreground_summary
    AgentTaskSurfaceMode.BACKGROUND -> R.string.agent_task_surface_background_summary
}
