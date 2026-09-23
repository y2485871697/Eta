package io.github.mangi.eta.ui

import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.SpeechModelManifest
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by OfflineSpeechPack.state.collectAsState()
    val config by DoubaoVoiceConfig.state.collectAsState()
    var page by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
    if (page != null) {
        DoubaoVoiceSettings(page = page!!, onBack = { page = null })
        return
    }
    var confirmDownload by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { DoubaoVoiceConfig.load(context); OfflineSpeechPack.initialize(context) }
    MiuixScaffoldPage(title = stringResource(R.string.speech_title), onBack = onBack) {
        item(key = "speech_enable") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.speech_enable),
                    summary = "点击聊天输入框的语音按钮，把说话变成文字。",
                    insideMargin = PaddingValues(16.dp),
                    checked = config.inputEnabled,
                    enabled = true,
                    onCheckedChange = { enabled ->
                        TouchHaptics.click(view)
                        DoubaoVoiceConfig.save(context, config.copy(inputEnabled = enabled))
                    },
                )
            }
        }
        item(key = "speech_pack") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(when {
                        state.downloading -> R.string.speech_cancel_download
                        state.ready -> R.string.speech_pack_ready
                        else -> R.string.speech_download
                    }),
                    summary = stringResource(when {
                        state.checking -> R.string.speech_checking
                        state.error -> R.string.speech_download_failed
                        else -> R.string.speech_pack_summary
                    }),
                    insideMargin = PaddingValues(16.dp),
                    enabled = !state.checking && !state.ready,
                    onClick = {
                        TouchHaptics.click(view)
                        if (state.downloading) OfflineSpeechPack.cancelDownload() else confirmDownload = true
                    },
                )
                if (state.downloading) {
                    val progress = (state.downloadedBytes.toFloat() / SpeechModelManifest.totalBytes).coerceIn(0f, 1f)
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        // Material3 Text 未显式着色时取 LocalContentColor（默认近黑），
                        // 在 Miuix 深色卡片上不可读，这里显式跟随主题。
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item(key = "recognition_method") {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(title = "识别方式", summary = if (config.cloudAsr) "豆包识别 · 需要联网" else "本机识别 · 无需账户",
                    insideMargin = PaddingValues(16.dp), onClick = { page = "asr" })
            }
        }
        item(key = "speech_privacy") {
            Text(if (config.cloudAsr) "点击后收音，音频发送至豆包识别；文字留在输入框，不自动发送。离开聊天或切到后台停止收音。" else stringResource(R.string.speech_privacy),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start)
        }
    }
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = confirmDownload,
        title = stringResource(R.string.speech_download),
        onDismissRequest = { confirmDownload = false },
    ) {
        Text(stringResource(R.string.speech_download_confirm), style = MaterialTheme.typography.bodyMedium,
            color = MiuixTheme.colorScheme.onSurface)
        MiuixDialogActions(
            confirmText = stringResource(R.string.speech_download),
            cancelText = stringResource(R.string.action_cancel),
            modifier = Modifier.padding(top = 16.dp),
            onCancel = { confirmDownload = false },
            onConfirm = { confirmDownload = false; OfflineSpeechPack.download(context) },
        )
    }
}
