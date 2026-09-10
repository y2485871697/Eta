package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxFileExplorer
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Linux 环境文件浏览：目录列举、预览、导出和删除都在 Linux 会话中完成，
 * 因此能看到 /workspace 等 bind 挂载里的真实文件。
 * 查看文件时进入屏内查看态，页面返回键先退回列表再退出页面。
 */
@Composable
internal fun LinuxFilesScreen(
    context: Context,
    distribution: String,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val linuxDistribution = remember(distribution) {
        LinuxDistribution.entries.firstOrNull { it.wireName == distribution }
    }
    val rootfsDir = remember(linuxDistribution) {
        linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it) }
    }
    val installed = rootfsDir != null && LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)
    val scope = rememberCoroutineScope()

    val shellSupervisor = remember { ShellProcessSupervisor() }
    DisposableEffect(Unit) {
        onDispose { shellSupervisor.beginClosing() }
    }

    var currentPath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<LinuxFileExplorer.Entry>?>(null) }
    var listError by remember { mutableStateOf<Int?>(null) }
    var listRevision by remember { mutableIntStateOf(0) }
    var openFilePath by remember { mutableStateOf<String?>(null) }
    var fileResult by remember { mutableStateOf<LinuxFileExplorer.ReadResult?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingActions by remember { mutableStateOf<FileEntryActions?>(null) }
    var pendingDelete by remember { mutableStateOf<LinuxPendingDelete?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        val dir = rootfsDir
        val dist = linuxDistribution
        if (uri == null || source == null || dir == null || dist == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val ok = withContext(Dispatchers.IO) {
                    exportLinuxFile(appContext, shellSupervisor, dir, dist, source, uri)
                }
                notice = appContext.getString(
                    if (ok) R.string.linux_files_exported else R.string.linux_files_export_failed,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = appContext.getString(R.string.linux_files_export_failed)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(currentPath, linuxDistribution, listRevision) {
        val dir = rootfsDir ?: return@LaunchedEffect
        val dist = linuxDistribution ?: return@LaunchedEffect
        if (!installed) return@LaunchedEffect
        entries = null
        listError = null
        val result = withContext(Dispatchers.IO) {
            LinuxFileExplorer.list(
                shellSupervisor,
                dir,
                currentPath,
                dist.terminalEnvironment,
                SharedFolderMounts.current(),
            )
        }
        when (result) {
            is LinuxFileExplorer.ListResult.Success -> entries = result.entries
            LinuxFileExplorer.ListResult.NotDirectory ->
                listError = R.string.linux_files_error_not_directory
            LinuxFileExplorer.ListResult.Unreadable,
            LinuxFileExplorer.ListResult.CommandFailed,
            LinuxFileExplorer.ListResult.NotInstalled ->
                listError = R.string.linux_files_error_unreadable
        }
    }

    LaunchedEffect(openFilePath) {
        val path = openFilePath ?: return@LaunchedEffect
        val dir = rootfsDir ?: return@LaunchedEffect
        val dist = linuxDistribution ?: return@LaunchedEffect
        fileResult = null
        fileResult = withContext(Dispatchers.IO) {
            LinuxFileExplorer.readText(
                shellSupervisor,
                dir,
                path,
                dist.terminalEnvironment,
                SharedFolderMounts.current(),
            )
        }
    }

    fun closeFile() {
        openFilePath = null
        fileResult = null
    }

    fun requestExport(path: String) {
        pendingExport = path
        exportLauncher.launch(path.substringAfterLast('/').ifBlank { "file" })
    }

    fun deleteItem(target: LinuxPendingDelete) {
        val dir = rootfsDir ?: return
        val dist = linuxDistribution ?: return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    LinuxFileExplorer.delete(
                        shellSupervisor,
                        dir,
                        target.path,
                        dist.terminalEnvironment,
                        SharedFolderMounts.current(),
                    )
                }
                notice = appContext.getString(
                    when (result) {
                        LinuxFileExplorer.DeleteResult.Success -> R.string.linux_files_deleted
                        LinuxFileExplorer.DeleteResult.Protected -> R.string.linux_files_delete_protected
                        else -> R.string.linux_files_delete_failed
                    },
                )
                if (result == LinuxFileExplorer.DeleteResult.Success) {
                    if (openFilePath == target.path) closeFile()
                    listRevision++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = appContext.getString(R.string.linux_files_delete_failed)
            } finally {
                busy = false
                pendingDelete = null
            }
        }
    }

    val viewerBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = viewerBackState,
        isBackEnabled = openFilePath != null,
        onBackCompleted = { closeFile() },
    )

    MiuixScaffoldPage(
        title = stringResource(R.string.linux_files_title),
        onBack = { if (openFilePath != null) closeFile() else onBack() },
    ) {
        when {
            linuxDistribution == null -> {
                item(key = "invalid-distribution") {
                    StateMessage(stringResource(R.string.linux_files_invalid_distribution))
                }
            }
            !installed -> {
                item(key = "not-installed") {
                    StateMessage(stringResource(R.string.linux_files_not_installed))
                }
            }
            openFilePath != null -> {
                item(key = "viewer-path") {
                    val viewing = openFilePath.orEmpty()
                    PathBar(
                        path = viewing,
                        onLongClick = if (busy) null else ({
                            pendingActions = FileEntryActions(
                                path = viewing,
                                name = viewing.substringAfterLast('/').ifBlank { viewing },
                                directory = false,
                                canExport = true,
                                canDelete = !LinuxFileExplorer.isProtectedPath(viewing),
                            )
                        }),
                    )
                }
                notice?.let { text ->
                    item(key = "viewer-notice") { HintText(text) }
                }
                when (val result = fileResult) {
                    is LinuxFileExplorer.ReadResult.Text -> {
                        if (result.truncated) {
                            item(key = "viewer-truncated") {
                                HintText(stringResource(R.string.linux_files_truncated_hint))
                            }
                        }
                        item(key = "viewer-content") {
                            SelectionContainer {
                                Text(
                                    text = result.content,
                                    style = MiuixTheme.textStyles.footnote1
                                        .copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                            }
                        }
                    }
                    LinuxFileExplorer.ReadResult.Binary -> {
                        item(key = "viewer-binary") {
                            StateMessage(stringResource(R.string.linux_files_binary_hint))
                        }
                    }
                    LinuxFileExplorer.ReadResult.NotFile -> {
                        item(key = "viewer-not-file") {
                            StateMessage(stringResource(R.string.linux_files_error_not_file))
                        }
                    }
                    LinuxFileExplorer.ReadResult.Unreadable,
                    LinuxFileExplorer.ReadResult.CommandFailed,
                    LinuxFileExplorer.ReadResult.NotInstalled -> {
                        item(key = "viewer-error") {
                            StateMessage(stringResource(R.string.linux_files_error_unreadable))
                        }
                    }
                    null -> Unit
                }
            }
            else -> {
                item(key = "path-bar") {
                    PathBar(currentPath)
                }
                notice?.let { text ->
                    item(key = "list-notice") { HintText(text) }
                }
                if (currentPath != "/") {
                    item(key = "..") {
                        FileRow(
                            name = "../",
                            isDir = true,
                            summary = null,
                            onClick = {
                                currentPath = currentPath.trimEnd('/')
                                    .substringBeforeLast('/')
                                    .ifBlank { "/" }
                            },
                        )
                    }
                }
                val currentEntries = entries
                when {
                    listError != null -> {
                        item(key = "list-error") {
                            StateMessage(stringResource(listError ?: R.string.linux_files_error_unreadable))
                        }
                    }
                    currentEntries != null && currentEntries.isEmpty() -> {
                        item(key = "list-empty") {
                            StateMessage(stringResource(R.string.linux_files_empty))
                        }
                    }
                    currentEntries != null -> {
                        items(currentEntries, key = { it.name }) { entry ->
                            val target = joinLinuxPath(currentPath, entry.name)
                            FileRow(
                                name = entry.name,
                                isDir = entry.isDir,
                                summary = if (entry.isDir) {
                                    null
                                } else {
                                    Formatter.formatShortFileSize(appContext, entry.sizeBytes)
                                },
                                enabled = !busy,
                                onLongClick = {
                                    val actions = FileEntryActions(
                                        path = target,
                                        name = entry.name,
                                        directory = entry.isDir,
                                        canExport = !entry.isDir,
                                        canDelete = !LinuxFileExplorer.isProtectedPath(target),
                                    )
                                    if (actions.canExport || actions.canDelete) pendingActions = actions
                                },
                                onClick = {
                                    if (entry.isDir) {
                                        currentPath = target
                                    } else {
                                        openFilePath = target
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingActions?.let { target ->
        FileEntryActionsDialog(
            target = target,
            enabled = !busy,
            onExport = {
                pendingActions = null
                requestExport(target.path)
            },
            onDelete = {
                pendingActions = null
                pendingDelete = LinuxPendingDelete(
                    path = target.path,
                    name = target.name,
                    directory = target.directory,
                )
            },
            onDismiss = { pendingActions = null },
        )
    }

    pendingDelete?.let { target ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.linux_files_delete_title),
            summary = stringResource(
                if (target.directory) R.string.linux_files_delete_dir_message
                else R.string.linux_files_delete_message,
                target.name,
            ),
            onDismissRequest = { if (!busy) pendingDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                confirmEnabled = !busy,
                onCancel = { if (!busy) pendingDelete = null },
                onConfirm = { if (!busy) deleteItem(target) },
            )
        }
    }
}

private data class LinuxPendingDelete(
    val path: String,
    val name: String,
    val directory: Boolean,
)

private fun joinLinuxPath(parent: String, name: String): String =
    if (parent == "/") "/$name" else parent.trimEnd('/') + "/" + name

private fun exportLinuxFile(
    context: Context,
    supervisor: ShellProcessSupervisor,
    rootfsDir: File,
    distribution: LinuxDistribution,
    linuxPath: String,
    destination: Uri,
): Boolean {
    val tmp = File(context.cacheDir, "linux-export-${System.nanoTime()}")
    return try {
        val copied = LinuxFileExplorer.copyToHostFile(
            supervisor = supervisor,
            rootfsDir = rootfsDir,
            linuxPath = linuxPath,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PathBar(
    path: String,
    onLongClick: (() -> Unit)? = null,
) {
    Text(
        text = path,
        style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp)
            .then(
                if (onLongClick == null) Modifier
                else Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
            ),
    )
}

@Composable
private fun StateMessage(message: String) {
    Text(
        text = message,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun HintText(message: String) {
    Text(
        text = message,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    name: String,
    isDir: Boolean,
    summary: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    BasicComponent(
        modifier = Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        title = name,
        summary = summary,
        enabled = enabled,
        startAction = {
            Icon(
                imageVector = if (isDir) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
    )
}
