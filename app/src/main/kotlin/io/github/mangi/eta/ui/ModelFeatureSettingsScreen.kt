package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.ModelFeature
import io.github.mangi.eta.agent.model.ModelFeaturePreferences
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun ModelFeatureSettingsScreen(feature: ModelFeature, onBack: () -> Unit) {
    var selection by remember(feature) { mutableStateOf(ModelFeaturePreferences.selection(feature)) }
    var picker by remember { mutableStateOf(false) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val vision = feature == ModelFeature.VISION
    val title = stringResource(if (vision) R.string.auxiliary_vision_title else R.string.title_model_title)
    val models = remember(providers, selection, vision) {
        val all = AgentModelPickerProjector.project(providers, selection.providerId, selection.modelId)
        all.copy(
            providerGroups = all.providerGroups.map { group ->
                group.copy(models = group.models.filter { !it.supportsImageGeneration && !it.supportsVideoGeneration && (!vision || it.supportsVision) })
            }.filter { it.models.isNotEmpty() },
            selectedModel = all.selectedModel?.takeIf { !it.supportsImageGeneration && !it.supportsVideoGeneration && (!vision || it.supportsVision) },
        )
    }
    MiuixScaffoldPage(title = title, onBack = onBack) {
        item {
            Text(
                stringResource(if (vision) R.string.vision_feature_description else R.string.title_feature_description),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.model_feature_custom),
                    checked = selection.custom,
                    onCheckedChange = {
                        selection = selection.copy(custom = it)
                        ModelFeaturePreferences.save(feature, selection)
                    },
                )
                if (selection.custom) {
                    ArrowPreference(
                        title = stringResource(R.string.model_feature_select),
                        summary = models.selectedModel?.let { "${it.providerName} / ${it.displayName}" }
                            ?: stringResource(R.string.model_feature_missing),
                        onClick = { picker = true },
                    )
                } else {
                    Text(
                        stringResource(if (vision) R.string.vision_feature_off else R.string.title_feature_default),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
    TtsModelPickerDialog(models, picker, { picker = false }, { providerId, modelId ->
        selection = selection.copy(providerId = providerId, modelId = modelId)
        ModelFeaturePreferences.save(feature, selection)
        picker = false
    }, title)
}
