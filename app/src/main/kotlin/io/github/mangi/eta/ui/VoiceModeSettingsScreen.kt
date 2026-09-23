package io.github.mangi.eta.ui

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import io.github.mangi.eta.agent.voice.DoubaoRealtimeVoices
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import top.yukonga.miuix.kmp.preference.SwitchPreference
import io.github.mangi.eta.agent.voice.VoiceModeController
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun VoiceModeSettingsScreen(onBack: () -> Unit, onOpenReadAloud: () -> Unit) {
    val config by DoubaoVoiceConfig.state.collectAsState()
    var providerPicker by remember { mutableStateOf(false) }
    var voicePicker by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var catalogStatus by remember { mutableStateOf(0) }
    var voices by remember { mutableStateOf(DoubaoRealtimeVoices.catalog) }
    val scope = rememberCoroutineScope()
    var providerId by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_PROVIDER_ID),
        )
    }
    var voice by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_VOICE).let {
                if (it.startsWith("etaClone") || it.startsWith("S_")) it else DoubaoRealtimeVoices.selectedId(it)
            },
        )
    }
    var instructions by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_INSTRUCTIONS)
                .ifBlank { VoiceModeController.DEFAULT_DUPLEX_INSTRUCTIONS },
        )
    }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val speechProviders = remember(providers) { providers.filter(SpeechSynthesisModels::isRealtimeVoiceProvider) }
    val provider = speechProviders.firstOrNull { it.id == providerId }

    val context = androidx.compose.ui.platform.LocalContext.current
    val personal by io.github.mangi.eta.agent.voice.doubao.PersonalVoices.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { DoubaoVoiceConfig.load(context); io.github.mangi.eta.agent.voice.doubao.PersonalVoices.load(context) }
    val availableVoices = voices + personal.filter { it.tts && it.accepted && it.account ==
        io.github.mangi.eta.agent.voice.doubao.PersonalVoices.account(provider?.apiKey.orEmpty()) }
        .map { io.github.mangi.eta.agent.voice.tts.SpeechVoice(it.id, it.name, personal = true) }

    MiuixScaffoldPage(title = stringResource(R.string.voice_mode_title), onBack = onBack) {
        item(key = "universal") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.voice_mode_universal_enable),
                    checked = config.conversationEnabled,
                    onCheckedChange = { DoubaoVoiceConfig.save(context, config.copy(conversationEnabled = it)) },
                    insideMargin = PaddingValues(16.dp),
                )
                ArrowPreference(
                    title = stringResource(R.string.voice_mode_universal),
                    summary = stringResource(R.string.voice_mode_universal_settings_summary),
                    insideMargin = PaddingValues(16.dp),
                    onClick = onOpenReadAloud,
                )
                // Material3 Text 未显式着色时取 LocalContentColor（默认近黑），
                // 在 Miuix 深色卡片上不可读，这里显式跟随主题。
                Text(
                    text = stringResource(R.string.voice_mode_universal_settings_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item(key = "doubao") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.voice_mode_doubao_enable),
                    checked = config.duplexEnabled,
                    onCheckedChange = { DoubaoVoiceConfig.save(context, config.copy(duplexEnabled = it)) },
                    insideMargin = PaddingValues(16.dp),
                )
                ArrowPreference(
                    title = stringResource(R.string.voice_mode_doubao_provider),
                    summary = provider?.name ?: stringResource(R.string.voice_mode_doubao_provider_missing),
                    insideMargin = PaddingValues(16.dp),
                    onClick = { providerPicker = true },
                )
                ArrowPreference(
                    title = stringResource(R.string.realtime_choose_voice),
                    summary = availableVoices.firstOrNull { it.id == voice }?.name
                        ?: stringResource(R.string.realtime_choose_voice),
                    onClick = { voicePicker = true },
                )
                ArrowPreference(
                    title = stringResource(if (refreshing) R.string.realtime_refreshing else R.string.realtime_refresh_voices),
                    summary = stringResource(when (catalogStatus) {
                        1 -> R.string.realtime_catalog_updated
                        2 -> R.string.realtime_catalog_failed
                        else -> R.string.realtime_catalog_builtin
                    }),
                    onClick = {
                        if (!refreshing) scope.launch {
                            refreshing = true
                            try { voices = DoubaoRealtimeVoices.refresh(); catalogStatus = 1 }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { catalogStatus = 2 }
                            finally { refreshing = false }
                        }
                    },
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = {
                        instructions = it
                        Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_INSTRUCTIONS, it)
                    },
                    label = { Text(stringResource(R.string.voice_mode_doubao_instructions)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Text(
                    text = stringResource(R.string.voice_mode_doubao_settings_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    TtsVoicePickerDialog(
        show = voicePicker,
        voices = availableVoices,
        selectedId = voice,
        title = stringResource(R.string.realtime_choose_voice),
        onDismiss = { voicePicker = false },
        onSelected = { value ->
            voice = value
            Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_VOICE, value)
            voicePicker = false
        },
    )
    SpeechRadioPickerDialog(
        show = providerPicker,
        title = stringResource(R.string.voice_mode_doubao_provider),
        rows = speechProviders.map { it.id to it.name },
        selectedId = providerId,
        emptyText = stringResource(R.string.voice_mode_doubao_provider_missing),
        onDismiss = { providerPicker = false },
        onSelected = { value ->
            providerId = value
            Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_PROVIDER_ID, value)
            providerPicker = false
        },
    )
}
