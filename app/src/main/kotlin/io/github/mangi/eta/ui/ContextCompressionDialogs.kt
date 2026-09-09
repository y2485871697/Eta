package io.github.mangi.eta.ui

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.defaultExpandedModelProviderIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.window.WindowDialog

private val CompressTargetTokenOptions = listOf(500, 1000, 2000, 4000)

@Composable
internal fun CompressConversationDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        providerId: String?,
        modelId: String?,
        targetTokens: Int,
        keepRecent: Int,
        onFinished: (Boolean) -> Unit,
    ) -> Unit,
) {
    val prefs = remember { Prefs.localAgentPreferences() }
    val scope = rememberCoroutineScope()
    var targetTokens by remember { mutableIntStateOf(AgentContextCompactor.DEFAULT_TARGET_TOKENS) }
    var keepRecent by remember { mutableIntStateOf(AgentContextCompactor.DEFAULT_KEEP_RECENT) }
    var keepRecentInput by remember { mutableStateOf(keepRecent.toString()) }
    var selectedModel by remember { mutableStateOf<AgentModelOptionUi?>(null) }
    var modelPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var showModelDialog by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var compressing by remember { mutableStateOf(false) }

    LaunchedEffect(show, prefs) {
        if (!show) {
            showModelDialog = false
            compressing = false
            return@LaunchedEffect
        }
        val currentPrefs = prefs
        targetTokens = currentPrefs?.getInt(
            Prefs.Keys.AGENT_COMPRESS_TARGET_TOKENS,
            AgentContextCompactor.DEFAULT_TARGET_TOKENS,
        ) ?: AgentContextCompactor.DEFAULT_TARGET_TOKENS
        val storedKeepRecent = currentPrefs?.getInt(
            Prefs.Keys.AGENT_COMPRESS_KEEP_RECENT,
            AgentContextCompactor.DEFAULT_KEEP_RECENT,
        ) ?: AgentContextCompactor.DEFAULT_KEEP_RECENT
        keepRecent = storedKeepRecent
        keepRecentInput = storedKeepRecent.toString()
        isLoadingModels = true
        val pickerState = withContext(Dispatchers.IO) {
            buildCompressModelPickerState(currentPrefs)
        }
        modelPickerState = pickerState
        selectedModel = pickerState.selectedModel
        isLoadingModels = false
    }

    WindowDialog(
        show = show,
        title = stringResource(R.string.action_compress_conversation),
        onDismissRequest = {
            if (!compressing) onDismiss()
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.ui_compress_model_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !compressing && !isLoadingModels) {
                        showModelDialog = true
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedModel?.displayName
                        ?: stringResource(R.string.model_not_selected),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = stringResource(R.string.ui_compress_target_tokens_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CompressTargetTokenOptions.forEach { value ->
                    val selected = targetTokens == value
                    androidx.compose.material3.Button(
                        onClick = { targetTokens = value },
                        enabled = !compressing,
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        ),
                    ) {
                        Text(value.toString())
                    }
                }
            }

            Text(
                text = stringResource(R.string.ui_compress_keep_recent_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = keepRecentInput,
                onValueChange = { value ->
                    val digits = value.filter(Char::isDigit).take(3)
                    val parsed = digits.toIntOrNull()
                    if (parsed == null) {
                        keepRecentInput = digits
                        return@OutlinedTextField
                    }
                    val number = parsed.coerceIn(0, 100)
                    keepRecentInput = number.toString()
                    keepRecent = number
                },
                enabled = !compressing,
                label = { Text(stringResource(R.string.ui_compress_keep_recent_title)) },
                supportingText = { Text(stringResource(R.string.ui_compress_keep_recent_summary)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            if (compressing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.compress_conversation_in_progress),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            MiuixDialogActions(
                confirmText = stringResource(R.string.compress_conversation_confirm),
                cancelText = stringResource(R.string.action_cancel),
                confirmEnabled = !compressing,
                cancelEnabled = !compressing,
                onCancel = onDismiss,
                onConfirm = {
                    if (compressing) return@MiuixDialogActions
                    compressing = true
                    val parsedKeepRecent = keepRecentInput.toIntOrNull()?.coerceIn(0, 100) ?: keepRecent
                    onConfirm(
                        selectedModel?.providerId,
                        selectedModel?.id,
                        targetTokens,
                        parsedKeepRecent,
                    ) { ok ->
                        compressing = false
                        if (ok) onDismiss()
                    }
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    CompressModelPickerDialog(
        state = modelPickerState,
        show = show && showModelDialog,
        isLoading = isLoadingModels,
        onDismiss = { showModelDialog = false },
        onModelSelected = { providerId, modelId ->
            showModelDialog = false
            scope.launch {
                val pickerState = withContext(Dispatchers.IO) {
                    buildCompressModelPickerState(providerId, modelId)
                }
                modelPickerState = pickerState
                selectedModel = pickerState.selectedModel
            }
        },
    )
}

@Composable
internal fun CompressModelPickerDialog(
    state: AgentModelPickerUiState,
    show: Boolean,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit,
) {
    var expandedProviderIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(show, state.selectedModel?.providerId, state.providerGroups) {
        if (!show) return@LaunchedEffect
        expandedProviderIds = defaultExpandedModelProviderIds(state.selectedModel).ifEmpty {
            state.providerGroups.singleOrNull()?.providerId?.let(::setOf).orEmpty()
        }
    }
    WindowDialog(
        show = show,
        title = stringResource(R.string.ui_compress_model_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isLoading && state.providerGroups.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp)
                }
            } else if (state.providerGroups.isEmpty()) {
                MiuixText(
                    text = stringResource(R.string.provider_empty),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            } else {
                state.providerGroups.forEachIndexed { index, group ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    val expanded = group.providerId in expandedProviderIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedProviderIds = if (expanded) {
                                    expandedProviderIds - group.providerId
                                } else {
                                    expandedProviderIds + group.providerId
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group.providerName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (expanded) {
                        group.models.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onModelSelected(model.providerId, model.id) }
                                    .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (model.id == state.selectedModel?.id) {
                                    Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun buildCompressModelPickerState(
    prefs: SharedPreferences?,
): AgentModelPickerUiState {
    val providerId = prefs?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, null)
    val modelId = prefs?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, null)
    return buildCompressModelPickerState(providerId, modelId)
}

internal suspend fun buildCompressModelPickerState(
    selectedProviderId: String?,
    selectedModelId: String?,
): AgentModelPickerUiState {
    val providers = runCatching { ProviderRepository.allProviders() }.getOrNull() ?: emptyList()
    return AgentModelPickerProjector.project(
        providers = providers,
        selectedProviderId = selectedProviderId,
        selectedModelId = selectedModelId,
    )
}

internal suspend fun readCompressModelSelection(prefs: SharedPreferences): AgentModelOptionUi? {
    return buildCompressModelPickerState(prefs).selectedModel
}