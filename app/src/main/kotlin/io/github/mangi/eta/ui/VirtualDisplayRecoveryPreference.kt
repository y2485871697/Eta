package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import io.github.mangi.eta.agent.device.VirtualDisplayWebPreview
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.PreferenceIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/** A user-operated recovery surface; never starts an owner or falls back to port 3070. */
@Composable
internal fun VirtualDisplayRecoveryPreference(
    context: Context,
    readStatus: (Context) -> JSONObject = VirtualDisplaySession::recoveryStatus,
    recover: (Context) -> JSONObject = VirtualDisplaySession::recoverAndFinishManually,
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showing by remember { mutableStateOf(false) }
    var previewRunning by remember { mutableStateOf(VirtualDisplayWebPreview.isRunning()) }
    var working by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }

    fun refresh() {
        if (working) return
        working = true
        scope.launch {
            try {
                state = withContext(Dispatchers.IO) { readStatus(context.applicationContext) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                state = JSONObject().put("ok", false).put("error", "RECOVERY_STATE_UNREADABLE")
            } finally { working = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                previewRunning = VirtualDisplayWebPreview.isRunning()
                refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snapshot = state
    val summary = when {
        snapshot == null -> stringResource(R.string.vd_recovery_working)
        !snapshot.optBoolean("ok") -> stringResource(R.string.vd_recovery_unknown)
        !snapshot.optBoolean("present") -> stringResource(R.string.vd_recovery_empty)
        snapshot.optBoolean("busy") -> stringResource(R.string.vd_recovery_busy)
        else -> stringResource(R.string.vd_recovery_record, snapshot.optInt("displayId", -1),
            snapshot.optString("phase", "recovery_pending"))
    }
    ArrowPreference(
        title = stringResource(R.string.vd_recovery_title),
        summary = summary,
        startAction = { PreferenceIcon(icon = Icons.Rounded.Layers) },
        onClick = { showing = true; refresh() },
    )

    if (showing) WindowDialog(
        show = true,
        title = stringResource(R.string.vd_recovery_title),
        summary = stringResource(R.string.vd_recovery_explanation),
        onDismissRequest = { if (!working) showing = false },
    ) {
        Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(summary)
            Text(stringResource(R.string.vd_recovery_scope))
            val lastError = snapshot?.optString("lastError").orEmpty()
            if (lastError.isNotBlank()) {
                Text(lastError)
                val detail = snapshot?.optString("lastDetail").orEmpty()
                if (detail.isNotBlank()) Text(detail)
            }
            result?.let { receipt ->
                if (receipt.optBoolean("ok") && receipt.optBoolean("released")) {
                    Text(stringResource(R.string.vd_recovery_success))
                } else {
                    Text(stringResource(R.string.vd_recovery_failed, receipt.optString("error", "UNKNOWN")))
                    if (receipt.optString("message").isNotBlank()) Text(receipt.optString("message"))
                }
            }
            Text(stringResource(R.string.vd_preview_note))
            TextButton(text = stringResource(R.string.vd_preview_open),
                enabled = !working && snapshot?.optBoolean("present") == true,
                onClick = {
                    if (!working) {
                        working = true
                        scope.launch {
                            try {
                                val uri = withContext(Dispatchers.IO) { VirtualDisplayWebPreview.open(context) }
                                previewRunning = true
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (ex: CancellationException) {
                                VirtualDisplayWebPreview.stop()
                                previewRunning = false
                                throw ex
                            } catch (_: Exception) {
                                VirtualDisplayWebPreview.stop()
                                previewRunning = false
                                result = JSONObject().put("ok", false).put("error", "WEB_PREVIEW_OPEN_FAILED")
                            } finally { working = false }
                        }
                    }
                })
            if (previewRunning) TextButton(text = stringResource(R.string.vd_preview_stop),
                enabled = !working, onClick = {
                    VirtualDisplayWebPreview.stop()
                    previewRunning = false
                })
            TextButton(text = stringResource(R.string.vd_recovery_refresh),
                enabled = !working, onClick = { refresh() })
            MiuixDialogActions(
                modifier = Modifier.padding(top = 4.dp),
                confirmText = stringResource(if (working) R.string.vd_recovery_working else R.string.vd_recovery_action),
                cancelEnabled = !working,
                confirmEnabled = !working && snapshot != null && snapshot.optBoolean("ok") &&
                    snapshot.optBoolean("present") && snapshot.optBoolean("recoverable"),
                onCancel = { showing = false },
                onConfirm = {
                    if (!working) {
                        working = true
                        result = null
                        scope.launch {
                            try {
                                val receipt = withContext(Dispatchers.IO) { recover(context.applicationContext) }
                                result = receipt
                                state = withContext(Dispatchers.IO) { readStatus(context.applicationContext) }
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (_: Exception) {
                                result = JSONObject().put("ok", false).put("error", "RECOVERY_OPERATION_FAILED")
                            } finally { working = false }
                        }
                    }
                },
            )
        }
    }
}
