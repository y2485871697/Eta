package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import io.github.mangi.eta.agent.device.VirtualDisplayWebPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Read-only on entry; user actions retain the original authenticated recovery safeguards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VirtualDisplayRecoveryScreen(
    onBack: () -> Unit,
    context: Context = LocalContext.current,
    readStatus: (Context) -> JSONObject = VirtualDisplaySession::recoveryStatus,
    recover: (Context) -> JSONObject = VirtualDisplaySession::recoverAndFinishManually,
) {
    val installed = rememberTaskBackendInstalled()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewRunning by remember { mutableStateOf(VirtualDisplayWebPreview.isRunning()) }
    var working by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(installed, working) {
        if (installed == false && !working) {
            VirtualDisplayWebPreview.stop()
            previewRunning = false
            onBack()
        }
    }

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

    // The external browser only pauses this page. Revoke preview on actual disposal,
    // not ON_PAUSE/ON_STOP, so opening the viewer does not kill its own connection.
    DisposableEffect(Unit) {
        onDispose { VirtualDisplayWebPreview.stop() }
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
    BackHandler(enabled = working) { /* Do not abandon an in-flight recovery action. */ }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vd_recovery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !working) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.vd_recovery_explanation), style = MaterialTheme.typography.bodyMedium)
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
            Button(
                enabled = installed == true && !working && snapshot?.optBoolean("present") == true,
                onClick = {
                    if (!working) {
                        working = true
                        scope.launch {
                            try {
                                val uri = withContext(Dispatchers.IO) { VirtualDisplayWebPreview.open(context) }
                                val stillInstalled = withContext(Dispatchers.IO) {
                                    io.github.mangi.eta.agent.device.AgentTaskSurface.moduleInstalled()
                                }
                                check(stillInstalled) { "Backend module removed" }
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
                }) { Text(stringResource(R.string.vd_preview_open)) }
            if (previewRunning) OutlinedButton(
                enabled = !working, onClick = {
                    VirtualDisplayWebPreview.stop()
                    previewRunning = false
                }) { Text(stringResource(R.string.vd_preview_stop)) }
            OutlinedButton(
                enabled = installed == true && !working, onClick = { refresh() }) { Text(stringResource(R.string.vd_recovery_refresh)) }
            Button(
                enabled = installed == true && !working && snapshot != null && snapshot.optBoolean("ok") &&
                    snapshot.optBoolean("present") && snapshot.optBoolean("recoverable"),
                onClick = {
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
            ) {
                Text(stringResource(if (working) R.string.vd_recovery_working else R.string.vd_recovery_action))
            }
        }
    }
}
