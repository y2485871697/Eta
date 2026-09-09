package io.github.mangi.eta.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun ContextCompressionSettingsScreen(context: Context, onBack: () -> Unit) {
    val prefs = remember(context) { Prefs.localAgentPreferences() }
    var enabled by remember { mutableStateOf(prefs?.getBoolean(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED, false) ?: false) }
    var targetTokens by remember { mutableIntStateOf(prefs?.getInt(Prefs.Keys.AGENT_COMPRESS_TARGET_TOKENS, AgentContextCompactor.DEFAULT_TARGET_TOKENS) ?: AgentContextCompactor.DEFAULT_TARGET_TOKENS) }
    var keepRecent by remember { mutableIntStateOf(prefs?.getInt(Prefs.Keys.AGENT_COMPRESS_KEEP_RECENT, AgentContextCompactor.DEFAULT_KEEP_RECENT) ?: AgentContextCompactor.DEFAULT_KEEP_RECENT) }
    var keepRecentInput by remember { mutableStateOf(keepRecent.toString()) }

    val scope = rememberCoroutineScope()
    var selectedCompressModel by remember { mutableStateOf<AgentModelOptionUi?>(null) }
    var showModelDialog by remember { mutableStateOf(false) }
    var modelPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var isLoadingModels by remember { mutableStateOf(prefs != null) }

    LaunchedEffect(prefs) {
        prefs?.let { currentPrefs ->
            isLoadingModels = true
            selectedCompressModel = withContext(Dispatchers.IO) { readCompressModelSelection(currentPrefs) }
            modelPickerState = withContext(Dispatchers.IO) { buildCompressModelPickerState(currentPrefs) }
            isLoadingModels = false
        }
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED -> enabled = prefs?.getBoolean(key, false) ?: false
                Prefs.Keys.AGENT_COMPRESS_TARGET_TOKENS -> targetTokens = prefs?.getInt(key, AgentContextCompactor.DEFAULT_TARGET_TOKENS) ?: AgentContextCompactor.DEFAULT_TARGET_TOKENS
                Prefs.Keys.AGENT_COMPRESS_KEEP_RECENT -> {
                    val stored = prefs?.getInt(key, AgentContextCompactor.DEFAULT_KEEP_RECENT)
                        ?: AgentContextCompactor.DEFAULT_KEEP_RECENT
                    keepRecent = stored
                    if (keepRecentInput.isNotEmpty() && keepRecentInput.toIntOrNull() != stored) {
                        keepRecentInput = stored.toString()
                    }
                }
                Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID,
                Prefs.Keys.AGENT_COMPRESS_MODEL_ID -> {
                    prefs?.let { currentPrefs ->
                        scope.launch {
                            selectedCompressModel = withContext(Dispatchers.IO) { readCompressModelSelection(currentPrefs) }
                            modelPickerState = withContext(Dispatchers.IO) { buildCompressModelPickerState(currentPrefs) }
                        }
                    }
                }
            }
        }
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs?.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    MiuixScaffoldPage(title = stringResource(R.string.ui_context_compression_settings_title), onBack = onBack) {
        item(key = "auto_compress") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_auto_compress_context_title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.ui_auto_compress_context_summary), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { value ->
                            prefs?.edit()?.putBoolean(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED, value)?.apply()
                            enabled = value
                        }
                    )
                }
            }
        }

        item(key = "compress_model") {
            SmallTitle(stringResource(R.string.ui_compress_model_title))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.ui_compress_model_title),
                    summary = selectedCompressModel?.displayName
                        ?: stringResource(R.string.model_not_selected),
                    onClick = { showModelDialog = true },
                    holdDownState = showModelDialog,
                )
            }
        }

        item(key = "target_tokens") {
            SmallTitle(stringResource(R.string.ui_compress_target_tokens_title))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf(500, 1000, 2000, 4000).forEach { value ->
                        val selected = targetTokens == value
                        androidx.compose.material3.Button(
                            onClick = {
                                prefs?.edit()?.putInt(Prefs.Keys.AGENT_COMPRESS_TARGET_TOKENS, value)?.apply()
                                targetTokens = value
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(value.toString())
                        }
                    }
                }
            }
        }

        item(key = "keep_recent") {
            SmallTitle(stringResource(R.string.ui_compress_keep_recent_title))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
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
                        if (number != keepRecent) {
                            keepRecent = number
                            prefs?.edit()?.putInt(Prefs.Keys.AGENT_COMPRESS_KEEP_RECENT, number)?.apply()
                        }
                    },
                    label = { Text(stringResource(R.string.ui_compress_keep_recent_title)) },
                    supportingText = { Text(stringResource(R.string.ui_compress_keep_recent_summary)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }

        item(key = "hint") {
            SmallTitle("")
        }
    }

    CompressModelPickerDialog(
        state = modelPickerState,
        show = showModelDialog,
        isLoading = isLoadingModels,
        onDismiss = { showModelDialog = false },
        onModelSelected = { providerId, modelId ->
            prefs?.edit()?.apply {
                putString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, providerId)
                putString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, modelId)
            }?.apply()
            scope.launch {
                prefs?.let { currentPrefs ->
                    selectedCompressModel = withContext(Dispatchers.IO) { readCompressModelSelection(currentPrefs) }
                    modelPickerState = withContext(Dispatchers.IO) { buildCompressModelPickerState(currentPrefs) }
                }
            }
            showModelDialog = false
        },
    )
}
