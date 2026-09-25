package io.github.mangi.eta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.ViewTreeObserver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentCompressionEndpoint
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import top.yukonga.miuix.kmp.basic.DropdownItem
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.haptics.TouchHaptics
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog


private val CompressDialogChrome = 200.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun rememberActivityImeBottomDp(): Dp {
    val density = LocalDensity.current
    val context = LocalContext.current
    val composeImePx = WindowInsets.ime.getBottom(density)
    var viewImePx by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val target = context.findActivity()?.window?.decorView
        if (target == null) {
            return@DisposableEffect onDispose { }
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val insets = ViewCompat.getRootWindowInsets(target) ?: return@OnGlobalLayoutListener
            viewImePx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
        target.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            target.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    return with(density) { maxOf(composeImePx, viewImePx).toDp() }
}


private fun storedManualCompressionEndpoint(): String =
    AgentCompressionEndpoint.parse(Prefs.getString(Prefs.Keys.AGENT_MANUAL_COMPRESS_ENDPOINT_MODE, ""))


@Composable
internal fun CompressionEndpointPreference(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    compact: Boolean = false,
) {
    if (compact) {
        CompactCompressionEndpointPreference(selected, enabled, onSelect)
        return
    }
    val items = listOf(
        DropdownItem(text = "Chat Completions API"),
        DropdownItem(text = "Responses API"),
    )
    val index = if (selected == OpenAiEndpointMode.RESPONSES) 1 else 0
    val horizontal = 16.dp
    Column(modifier = Modifier.fillMaxWidth()) {
        WindowSpinnerPreference(
            items = items,
            selectedIndex = index,
            title = stringResource(R.string.ui_compress_request_api),
            insideMargin = PaddingValues(horizontal = horizontal, vertical = 12.dp),
            enabled = enabled,
            onSelectedIndexChange = { selectedIndex ->
                onSelect(
                    if (selectedIndex == 1) OpenAiEndpointMode.RESPONSES
                    else OpenAiEndpointMode.CHAT_COMPLETIONS,
                )
            },
        )
        Text(
            stringResource(R.string.ui_compress_endpoint_description),
            modifier = Modifier.fillMaxWidth().padding(start = horizontal, end = horizontal, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}

/** Dialog layout intentionally avoids a setting-row spinner: long API names get a full row. */
@Composable
private fun CompactCompressionEndpointPreference(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val detailsState = stringResource(if (detailsExpanded) R.string.work_collapse else R.string.work_expand)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            text = stringResource(R.string.ui_compress_request_api),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.ui_compress_endpoint_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .selectableGroup(),
        ) {
            listOf(
                OpenAiEndpointMode.CHAT_COMPLETIONS to "Chat Completions API",
                OpenAiEndpointMode.RESPONSES to "Responses API",
            ).forEach { (mode, label) ->
                val checked = selected == mode
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            else androidx.compose.ui.graphics.Color.Transparent)
                        .selectable(
                            selected = checked,
                            enabled = enabled,
                            role = Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (!checked) {
                                TouchHaptics.click(view)
                                onSelect(mode)
                            }
                        }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    RadioButton(selected = checked, onClick = null, enabled = enabled)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .semantics { stateDescription = detailsState }
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    TouchHaptics.click(view)
                    detailsExpanded = !detailsExpanded
                }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ui_compress_endpoint_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (detailsExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = detailsExpanded) {
            Text(
                text = stringResource(R.string.ui_compress_endpoint_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompressConversationDialog(
    show: Boolean,
    isCompressing: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (
        providerId: String?,
        modelId: String?,
        onFinished: (Boolean) -> Unit,
    ) -> Unit,
) {
    val prefs = remember { Prefs.localAgentPreferences() }
    val scope = rememberCoroutineScope()
    var endpointMode by remember { mutableStateOf(OpenAiEndpointMode.CHAT_COMPLETIONS) }
    var selectedModel by remember { mutableStateOf<AgentModelOptionUi?>(null) }
    var customModelEnabled by remember { mutableStateOf(false) }
    var modelPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showMissingWindowDialog by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    val imeBottom = rememberActivityImeBottomDp()
    val configuration = LocalConfiguration.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val dialogImeOffset = -(imeBottom / 2)
    val maxBodyHeight = (configuration.screenHeightDp.dp - imeBottom - CompressDialogChrome)
        .coerceIn(140.dp, 360.dp)

    LaunchedEffect(show, prefs) {
        if (!show) {
            showModelDialog = false
            return@LaunchedEffect
        }
        endpointMode = storedManualCompressionEndpoint()
        isLoadingModels = true
        customModelEnabled = Prefs.isCustomCompressModelEnabled(prefs)
        val pickerState = withContext(Dispatchers.IO) {
            buildManualCompressModelPickerState()
        }
        modelPickerState = pickerState
        selectedModel = pickerState.selectedModel.takeIf { customModelEnabled }
        isLoadingModels = false
    }

    WindowDialog(
        show = show,
        title = stringResource(R.string.action_compress_conversation),
        modifier = Modifier.offset(y = dialogImeOffset),
        onDismissRequest = onDismiss,
    ) {
        val scrollState = rememberScrollState()
        val dialogFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            dialogFocus.requestFocus()
            keyboard?.hide()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(dialogFocus)
                .focusable(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    .verticalScroll(scrollState)
                    .alpha(if (isCompressing) 0.42f else 1f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.ui_custom_compress_model_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    val customModelLabel = stringResource(R.string.ui_custom_compress_model_title)
                    Switch(
                        checked = customModelEnabled,
                        modifier = Modifier.semantics { contentDescription = customModelLabel },
                        enabled = !isCompressing && !isLoadingModels,
                        onCheckedChange = { value ->
                            TouchHaptics.click(view)
                            customModelEnabled = value
                            Prefs.putBoolean(Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED, value)
                            if (value && selectedModel == null) {
                                selectedModel = modelPickerState.selectedModel
                            }
                            if (value && !selectedModel.hasCompressContextWindow() && selectedModel != null) {
                                showMissingWindowDialog = true
                            }
                        },
                    )
                }
                if (customModelEnabled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(
                                enabled = !isCompressing && !isLoadingModels,
                                role = Role.Button,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                TouchHaptics.click(view)
                                showModelDialog = true
                            }
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = if (isLoadingModels) stringResource(R.string.ui_compress_models_loading)
                                else selectedModel?.displayName ?: stringResource(R.string.model_not_selected),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.ui_custom_compress_model_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                CompressionEndpointPreference(
                    selected = endpointMode,
                    enabled = !isCompressing,
                    compact = true,
                    onSelect = { option ->
                        endpointMode = option
                        Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_ENDPOINT_MODE, option)
                    },
                )

            }
            if (isCompressing) {
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
                confirmEnabled = !isCompressing && !isLoadingModels,
                cancelEnabled = !isCompressing,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .alpha(if (isCompressing) 0.42f else 1f),
                onCancel = onDismiss,
                onConfirm = {
                    if (isCompressing) return@MiuixDialogActions
                    focusManager.clearFocus()
                    keyboard?.hide()
                    Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_ENDPOINT_MODE, endpointMode)
                    onConfirm(
                        selectedModel?.providerId.takeIf { customModelEnabled },
                        selectedModel?.id.takeIf { customModelEnabled },
                    ) { ok ->
                        if (ok) onDismiss()
                    }
                },
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
            Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_PROVIDER_ID, providerId)
            Prefs.putString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_ID, modelId)
            val picked = modelPickerState.findCompressModel(providerId, modelId)
            if (!picked.hasCompressContextWindow()) {
                showMissingWindowDialog = true
            }
            scope.launch {
                val pickerState = withContext(Dispatchers.IO) {
                    buildCompressModelPickerState(providerId, modelId)
                }
                modelPickerState = pickerState
                selectedModel = pickerState.selectedModel
            }
        },
    )

    CompressModelMissingWindowDialog(
        show = show && showMissingWindowDialog,
        onDismiss = { showMissingWindowDialog = false },
    )
}


internal fun AgentModelOptionUi?.hasCompressContextWindow(): Boolean =
    this?.contextWindow?.let { it > 0 } == true

internal fun AgentModelPickerUiState.findCompressModel(providerId: String, modelId: String): AgentModelOptionUi? =
    providerGroups.firstOrNull { it.providerId == providerId }?.models?.firstOrNull { it.id == modelId }

@Composable
internal fun CompressModelMissingWindowDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    if (!show) return
    WindowDialog(
        show = true,
        title = stringResource(R.string.compress_model_missing_window_title),
        summary = stringResource(R.string.compress_model_missing_window_summary),
        onDismissRequest = onDismiss,
    ) {
        TextButton(
            text = stringResource(R.string.ui_knew_cb63c6),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
internal fun CompressModelPickerDialog(
    state: AgentModelPickerUiState,
    show: Boolean,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit,
    title: String? = null,
) {
    val view = LocalView.current
    var expandedProviderIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(show, state.selectedModel?.providerId, state.providerGroups) {
        if (!show) return@LaunchedEffect
        expandedProviderIds = defaultExpandedModelProviderIds(state.selectedModel).ifEmpty {
            state.providerGroups.singleOrNull()?.providerId?.let(::setOf).orEmpty()
        }
    }
    WindowDialog(
        show = show,
        title = title ?: stringResource(R.string.ui_compress_model_title),
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
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) {
                                TouchHaptics.click(view)
                                expandedProviderIds = if (expanded) {
                                    emptySet()
                                } else {
                                    setOf(group.providerId)
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
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                    ) {
                                        TouchHaptics.click(view)
                                        onModelSelected(model.providerId, model.id)
                                    }
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

internal suspend fun buildManualCompressModelPickerState(): AgentModelPickerUiState {
    val prefs = Prefs.localAgentPreferences()
    val manualProviderId = prefs?.getString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_PROVIDER_ID, null)
    val manualModelId = prefs?.getString(Prefs.Keys.AGENT_MANUAL_COMPRESS_MODEL_ID, null)
    if (!manualProviderId.isNullOrBlank() && !manualModelId.isNullOrBlank()) {
        return buildCompressModelPickerState(manualProviderId, manualModelId)
    }
    val settings = SettingsDataStore.settings()
    return buildCompressModelPickerState(settings.selectedProviderId, settings.selectedModelId)
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

