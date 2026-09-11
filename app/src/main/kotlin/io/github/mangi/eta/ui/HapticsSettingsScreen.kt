package io.github.mangi.eta.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun HapticsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember { Prefs.localAgentPreferences() }
    var touchEnabled by remember {
        mutableStateOf(Prefs.isEnabled(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK))
    }
    var messageGenerationEnabled by remember {
        mutableStateOf(Prefs.isEnabled(Prefs.Keys.HAPTIC_MESSAGE_GENERATION))
    }

    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            when (key) {
                Prefs.Keys.HAPTIC_TOUCH_FEEDBACK -> {
                    touchEnabled = changed.getBoolean(
                        key,
                        Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key),
                    )
                }
                Prefs.Keys.HAPTIC_MESSAGE_GENERATION -> {
                    messageGenerationEnabled = changed.getBoolean(
                        key,
                        Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key),
                    )
                }
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun write(key: String, value: Boolean): Boolean {
        val target = prefs ?: return false
        return runCatching { target.edit().putBoolean(key, value).commit() }.getOrDefault(false)
    }

    fun onToggle(key: String, value: Boolean, current: Boolean): Boolean {
        if (current) TouchHaptics.click(view)
        if (!write(key, value)) {
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.settings_write_failed),
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        if (!current && value) TouchHaptics.click(view)
        return true
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.haptics_title),
        onBack = onBack,
    ) {
        item(key = "haptics_switches") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.haptics_touch_feedback),
                    summary = stringResource(R.string.haptics_touch_feedback_summary),
                    checked = touchEnabled,
                    onCheckedChange = { value ->
                        if (onToggle(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK, value, touchEnabled)) {
                            touchEnabled = value
                        }
                    },
                    startAction = { PreferenceIcon(icon = Icons.Rounded.Vibration) },
                )
                SwitchPreference(
                    title = stringResource(R.string.haptics_message_generation),
                    summary = stringResource(R.string.haptics_message_generation_summary),
                    checked = touchEnabled && messageGenerationEnabled,
                    onCheckedChange = { value ->
                        if (onToggle(
                                Prefs.Keys.HAPTIC_MESSAGE_GENERATION,
                                value,
                                touchEnabled && messageGenerationEnabled,
                            )
                        ) {
                            messageGenerationEnabled = value
                        }
                    },
                    startAction = {
                        PreferenceIcon(icon = Icons.Rounded.GraphicEq, enabled = touchEnabled)
                    },
                    enabled = touchEnabled,
                )
            }
        }
    }
}
