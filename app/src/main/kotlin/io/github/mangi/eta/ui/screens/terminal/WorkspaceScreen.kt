package io.github.mangi.eta.ui.screens.terminal

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxFileExplorer
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.ui.app.WorkspaceEntry
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import io.github.mangi.eta.ui.app.guestWorkspacePath
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember(appContext) { WorkspaceFileStore(context) }
    val scope = rememberCoroutineScope()
    val distribution by LinuxEnvironmentSettingsRepository.selectedFlow(appContext).collectAsState(
        initial = LinuxEnvironmentSettingsRepository.current(appContext),
    )
    val backendFlow = remember(appContext, distribution) {
        LinuxEnvironmentSettingsRepository.backendFlow(appContext, distribution)
    }
    val backend by backendFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.backend(appContext, distribution),
    )
    val rootfsDir = remember(distribution, backend) {
        LinuxEnvironmentPaths.rootfsDir(appContext, distribution, backend)
    }
    val linuxReady = LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)
    val shellSupervisor = remember { ShellProcessSupervisor() }
    DisposableEffect(Unit) {
        onDispose { shellSupervisor.beginClosing() }
    }
    var path by rememberSaveable { mutableStateOf("") }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingActions by remember { mutableStateOf<FileEntryActions?>(null) }
    var pendingDelete by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var publicAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        publicAccess = Environment.isExternalStorageManager()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                var succeeded = 0
                uris.forEach { if (store.importFile(it)) succeeded++ }
                message = if (succeeded == uris.size) context.getString(R.string.capability_workspace_imported)
                else context.getString(R.string.capability_workspace_partial_import, succeeded)
                if (succeeded > 0) path = "imports"
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.capability_workspace_failed)
            } finally {
                busy = false
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri != null && source != null) scope.launch {
            busy = true
            try {
                val exported = if (linuxReady) {
                    withContext(Dispatchers.IO) {
                        exportWorkspaceLinuxFile(
                            context = appContext,
                            supervisor = shellSupervisor,
                            rootfsDir = rootfsDir,
                            distribution = distribution,
                            relativePath = source,
                            destination = uri,
                        )
                    }
                } else {
                    false
                }
                if (!exported) store.exportFile(source, uri)
                message = context.getString(R.string.capability_workspace_exported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.capability_workspace_failed)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(path, revision, linuxReady, distribution, backend) {
        try {
            entries = if (linuxReady) {
                val result = withContext(Dispatchers.IO) {
                    LinuxFileExplorer.list(
                        shellSupervisor,
                        rootfsDir,
                        guestWorkspacePath(path),
                        distribution.terminalEnvironment,
                        SharedFolderMounts.current(),
                    )
                }
                when (result) {
                    is LinuxFileExplorer.ListResult.Success -> result.entries.map { entry ->
                        WorkspaceEntry(
                            path = if (path.isBlank()) entry.name else path.trimEnd('/') + '/' + entry.name,
                            name = entry.name,
                            directory = entry.isDir,
                            size = entry.sizeBytes,
                        )
                    }
                    else -> store.list(path)
                }
            } else {
                store.list(path)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            entries = emptyList()
            message = context.getString(R.string.capability_workspace_failed)
        }
    }
    MiuixScaffoldPage(title = stringResource(R.string.capability_workspace), onBack = onBack) {
        item(key = "workspace-info") {
            BasicComponent(
                title = stringResource(R.string.capability_workspace_private),
                summary = stringResource(R.string.capability_workspace_private_summary),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        item(key = "actions") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.capability_workspace_import),
                    enabled = !busy,
                    startAction = { PreferenceIcon(Icons.Rounded.DriveFolderUpload, enabled = !busy) },
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )

                ArrowPreference(
                    title = stringResource(R.string.capability_workspace_public),
                    startAction = { PreferenceIcon(Icons.Rounded.FolderOpen) },
                    summary = if (publicAccess) stringResource(R.string.capability_workspace_public_granted) else stringResource(R.string.capability_workspace_public_summary),
                    onClick = {
                        try {
                            accessLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")))
                        } catch (_: android.content.ActivityNotFoundException) {
                            message = context.getString(R.string.capability_workspace_failed)
                        }
                    },
                )
            }
        }
        message?.let { text -> item(key = "message") { BasicComponent(title = text) } }
        item(key = "path") {
            SmallTitle(if (path.isBlank()) stringResource(R.string.capability_workspace_files) else path)
        }
        if (path.isNotBlank()) {
            item(key = "parent") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.capability_workspace_parent),
                        startAction = { PreferenceIcon(Icons.Rounded.FolderOpen) },
                        onClick = { path = path.substringBeforeLast('/', "") },
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.capability_workspace_empty),
                    summary = stringResource(R.string.capability_workspace_empty_summary),
                )
            }
        }
        items(entries, key = { it.path }) { entry ->
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                BasicComponent(
                    modifier = Modifier.combinedClickable(
                        enabled = !busy,
                        onClick = {
                            if (entry.directory) path = entry.path
                        },
                        onLongClick = {
                            pendingActions = FileEntryActions(
                                path = entry.path,
                                name = entry.name,
                                directory = entry.directory,
                                canExport = !entry.directory,
                                canDelete = true,
                            )
                        },
                    ),
                    title = entry.name,
                    summary = if (entry.directory) stringResource(R.string.capability_workspace_directory)
                        else Formatter.formatShortFileSize(context, entry.size),
                    enabled = !busy,
                    startAction = {
                        PreferenceIcon(
                            icon = if (entry.directory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                            enabled = !busy,
                        )
                    },
                )
            }
        }
    }

    pendingActions?.let { target ->
        FileEntryActionsDialog(
            target = target,
            enabled = !busy,
            onExport = {
                pendingActions = null
                pendingExport = target.path
                exportLauncher.launch(target.name)
            },
            onDelete = {
                pendingActions = null
                pendingDelete = WorkspaceEntry(
                    path = target.path,
                    name = target.name,
                    directory = target.directory,
                    size = 0L,
                )
            },
            onDismiss = { pendingActions = null },
        )
    }

    pendingDelete?.let { target ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.capability_workspace_delete_title),
            summary = stringResource(
                if (target.directory) R.string.capability_workspace_delete_dir_message
                else R.string.capability_workspace_delete_message,
                target.name,
            ),
            onDismissRequest = { if (!busy) pendingDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                confirmEnabled = !busy,
                onCancel = { if (!busy) pendingDelete = null },
                onConfirm = {
                    if (busy) return@MiuixDialogActions
                    val deleting = target
                    pendingDelete = null
                    scope.launch {
                        busy = true
                        try {
                            val deleted = if (linuxReady) {
                                val result = withContext(Dispatchers.IO) {
                                    LinuxFileExplorer.delete(
                                        shellSupervisor,
                                        rootfsDir,
                                        guestWorkspacePath(deleting.path),
                                        distribution.terminalEnvironment,
                                        SharedFolderMounts.current(),
                                    )
                                }
                                if (result == LinuxFileExplorer.DeleteResult.Success) true
                                else {
                                    store.delete(deleting.path)
                                    true
                                }
                            } else {
                                store.delete(deleting.path)
                                true
                            }
                            message = context.getString(
                                if (deleted) R.string.capability_workspace_deleted
                                else R.string.capability_workspace_failed,
                            )
                            revision++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            message = context.getString(R.string.capability_workspace_failed)
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

private fun exportWorkspaceLinuxFile(
    context: android.content.Context,
    supervisor: ShellProcessSupervisor,
    rootfsDir: File,
    distribution: io.github.mangi.eta.agent.terminal.LinuxDistribution,
    relativePath: String,
    destination: Uri,
): Boolean {
    val tmp = File(context.cacheDir, "workspace-export-${System.nanoTime()}")
    return try {
        val copied = LinuxFileExplorer.copyToHostFile(
            supervisor = supervisor,
            rootfsDir = rootfsDir,
            linuxPath = guestWorkspacePath(relativePath),
            environment = distribution.terminalEnvironment,
            destination = tmp,
            sharedMounts = SharedFolderMounts.current(),
        )
        if (copied != LinuxFileExplorer.CopyResult.Success || !tmp.isFile) return false
        tmp.inputStream().use { input ->
            val output = context.contentResolver.openOutputStream(destination, "wt") ?: return false
            output.use { input.copyTo(it) }
        }
        true
    } finally {
        tmp.delete()
    }
}
