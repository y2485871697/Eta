package io.github.mangi.eta.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.view.ViewTreeObserver
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
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

private val CompressTargetTokenOptions = listOf(500, 1000, 2000, 4000)

@OptIn(ExperimentalLayoutApi::class)
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
    var keepRecentField by remember {
        mutableStateOf(TextFieldValue(keepRecent.toString()))
    }
    var selectedModel by remember { mutableStateOf<AgentModelOptionUi?>(null) }
    var modelPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var showModelDialog by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var compressing by remember { mutableStateOf(false) }
    var keepRecentFocused by remember { mutableStateOf(false) }
    val imeBottom = rememberActivityImeBottomDp()
    val configuration = LocalConfiguration.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dialogImeOffset = -(imeBottom / 2)
    val maxBodyHeight = (configuration.screenHeightDp.dp - imeBottom - CompressDialogChrome)
        .coerceIn(140.dp, 360.dp)

    LaunchedEffect(show, prefs) {
        if (!show) {
            showModelDialog = false
            compressing = false
            return@LaunchedEffect
        }
        targetTokens = AgentContextCompactor.DEFAULT_TARGET_TOKENS
        keepRecent = AgentContextCompactor.DEFAULT_KEEP_RECENT
        val seed = keepRecent.toString()
        keepRecentField = TextFieldValue(seed, TextRange(0, seed.length))
        isLoadingModels = true
        val pickerState = withContext(Dispatchers.IO) {
            buildManualCompressModelPickerState()
        }
        modelPickerState = pickerState
        selectedModel = pickerState.selectedModel
        isLoadingModels = false
    }

    WindowDialog(
        show = show,
        title = stringResource(R.string.action_compress_conversation),
        modifier = Modifier.offset(y = dialogImeOffset),
        onDismissRequest = {
            if (!compressing) onDismiss()
        },
    ) {
        val scrollState = rememberScrollState()
        LaunchedEffect(keepRecentFocused, imeBottom, scrollState.maxValue) {
            if (keepRecentFocused && imeBottom > 0.dp) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    .verticalScroll(scrollState),
            ) {
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompressTargetTokenOptions.forEach { value ->
                    val selected = targetTokens == value
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = { targetTokens = value },
                        enabled = !compressing,
                        label = { Text(value.toString()) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.ui_compress_keep_recent_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = keepRecentField,
                onValueChange = { value ->
                    val digits = value.text.filter(Char::isDigit).take(3)
                    if (digits.isEmpty()) {
                        keepRecentField = TextFieldValue("")
                        return@OutlinedTextField
                    }
                    val number = digits.toInt().coerceIn(0, 100)
                    val next = number.toString()
                    keepRecent = number
                    keepRecentField = TextFieldValue(next, TextRange(next.length))
                },
                enabled = !compressing,
                singleLine = true,
                label = { Text(stringResource(R.string.ui_compress_keep_recent_title)) },
                supportingText = { Text(stringResource(R.string.ui_compress_keep_recent_summary)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { keepRecentFocused = it.isFocused },
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
            }

            MiuixDialogActions(
                confirmText = stringResource(R.string.compress_conversation_confirm),
                cancelText = stringResource(R.string.action_cancel),
                confirmEnabled = !compressing,
                cancelEnabled = !compressing,
                onCancel = onDismiss,
                onConfirm = {
                    if (compressing) return@MiuixDialogActions
                    focusManager.clearFocus()
                    keyboard?.hide()
                    compressing = true
                    val parsedKeepRecent = keepRecentField.text.toIntOrNull()?.coerceIn(0, 100) ?: keepRecent
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

internal suspend fun buildManualCompressModelPickerState(): AgentModelPickerUiState {
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

