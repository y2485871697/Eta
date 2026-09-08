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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

@Composable
internal fun ContextCompressionSettingsScreen(context: Context, onBack: () -> Unit) {
    val prefs = remember(context) { Prefs.localAgentPreferences() }
    var enabled by remember { mutableStateOf(prefs?.getBoolean(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED, false) ?: false) }
    var targetTokens by remember { mutableIntStateOf(prefs?.getInt(Prefs.Keys.AGENT_COMPRESS_TARGET_TOKENS, AgentContextCompactor.DEFAULT_TARGET_TOKENS) ?: AgentContextCompactor.DEFAULT_TARGET_TOKENS) }
    var keepRecent by remember { mutableIntStateOf(prefs?.getInt(Prefs.Keys.AGENT_COMPRESS_KEEP_RECENT, AgentContextCompactor.DEFAULT_KEEP_RECENT) ?: AgentContextCompactor.DEFAULT_KEEP_RECENT) }
    var keepRecentInput by remember { mutableStateOf(keepRecent.toString()) }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED -> enabled = prefs?.getBoolean(key, false) ?: false
                Prefs.Keys.AGENT_COMPRESS_TARGET_TOKENS -> targetTokens = prefs?.getInt(key, AgentContextCompactor.DEFAULT_TARGET_TOKENS) ?: AgentContextCompactor.DEFAULT_TARGET_TOKENS
                Prefs.Keys.AGENT_COMPRESS_KEEP_RECENT -> {
                    keepRecent = prefs?.getInt(key, AgentContextCompactor.DEFAULT_KEEP_RECENT) ?: AgentContextCompactor.DEFAULT_KEEP_RECENT
                    keepRecentInput = keepRecent.toString()
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
                        keepRecentInput = value
                        val number = value.toIntOrNull()?.coerceIn(0, 100) ?: keepRecent
                        prefs?.edit()?.putInt(Prefs.Keys.AGENT_COMPRESS_KEEP_RECENT, number)?.apply()
                        keepRecent = number
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
}
