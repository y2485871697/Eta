package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.LinuxFileExplorer
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.runOneShotShell
import io.github.mangi.eta.agent.terminal.shellQuote
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File
import kotlin.coroutines.coroutineContext
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class WorkspaceEntry(val path: String, val name: String, val directory: Boolean, val size: Long)

internal fun resolveWorkspaceHost(
    filesDir: File,
    linuxReady: Boolean,
    backend: LinuxExecutionBackend,
): File = if (linuxReady && backend == LinuxExecutionBackend.CHROOT) {
    File(TerminalRuntime.workspace("root"))
} else {
    TerminalPrivateStorage.workspace(filesDir)
}

internal fun guestWorkspacePath(relative: String): String {
    val raw = if (relative.isBlank()) {
        "/workspace"
    } else {
        "/workspace/" + relative.replace('\\', '/').trim('/')
    }
    val guest = LinuxFileExplorer.normalizeLinuxPath(raw)
        ?: throw IllegalArgumentException("WORKSPACE_PATH_OUTSIDE_ROOT")
    require(guest == "/workspace" || guest.startsWith("/workspace/")) {
        "WORKSPACE_PATH_OUTSIDE_ROOT"
    }
    return guest
}

internal class WorkspaceFileStore(context: Context) {
    private val appContext = context.applicationContext
    private val root: File
        get() {
            val distribution = LinuxEnvironmentSettingsRepository.current(appContext)
            val backend = LinuxEnvironmentSettingsRepository.backend(appContext, distribution)
            val rootfs = LinuxEnvironmentPaths.rootfsDir(appContext, distribution, backend)
            return resolveWorkspaceHost(
                filesDir = appContext.filesDir,
                linuxReady = LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath),
                backend = backend,
            ).canonicalFile
        }

    suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        if (!root.mkdirs() && !root.isDirectory) throw java.io.IOException("WORKSPACE_DIRECTORY_UNAVAILABLE")
        val directory = resolve(path)
        check(directory.isDirectory) { "WORKSPACE_NOT_DIRECTORY" }
        val files = directory.listFiles() ?: throw java.io.IOException("WORKSPACE_UNREADABLE")
        files.mapNotNull { file ->
            val canonical = file.canonicalFile
            if (!canonical.toPath().startsWith(root.toPath()) || (!canonical.isFile && !canonical.isDirectory)) {
                null
            } else {
                WorkspaceEntry(file.relativeTo(root).path, file.name, file.isDirectory, file.length())
            }
        }.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    suspend fun importFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val imported = AgentFileReferenceGateway.importDocumentUri(appContext, uri)
        if (imported !is AgentFileReferenceGateway.Resolution.Success) return@withContext false
        val source = File(imported.reference.absolutePath).canonicalFile
        if (source.toPath().startsWith(root.toPath())) return@withContext true
        val destination = File(root, "imports/${UUID.randomUUID()}/${source.name}")
        copyIntoWorkspace(source, destination)
        true
    }

    suspend fun exportFile(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val source = resolve(path)
        val readable = if (source.isFile && source.canRead()) source else stageReadableCopy(source)
        copyStream(readable, destination)
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        require(path.isNotBlank()) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        val file = resolve(path)
        require(file != root) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        val removed = runCatching {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }.getOrDefault(false)
        if (removed && !file.exists()) return@withContext
        val supervisor = ShellProcessSupervisor()
        try {
            val result = runOneShotShell(
                processSupervisor = supervisor,
                identity = "root",
                command = "rm -rf -- " + shellQuote(file.absolutePath),
                timeoutSeconds = 30,
                environment = TerminalEnvironment.ANDROID,
            )
            check(result.exitCode == 0 && !file.exists()) { "WORKSPACE_DELETE_FAILED" }
        } finally {
            supervisor.beginClosing()
        }
    }

    private suspend fun copyStream(source: File, destination: Uri) {
        source.inputStream().use { input ->
            val output = appContext.contentResolver.openOutputStream(destination, "wt")
                ?: throw java.io.IOException("EXPORT_UNAVAILABLE")
            output.use {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun stageReadableCopy(source: File): File {
        val staged = File(appContext.cacheDir, "workspace-export-${UUID.randomUUID()}")
        copyIntoWorkspace(source, staged)
        check(staged.isFile && staged.canRead()) { "WORKSPACE_NOT_FILE" }
        return staged
    }

    private fun copyIntoWorkspace(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val copied = runCatching {
            source.copyTo(destination, overwrite = true)
            destination.isFile
        }.getOrDefault(false)
        if (copied) return
        val supervisor = ShellProcessSupervisor()
        try {
            val parent = destination.parentFile?.absolutePath
                ?: throw java.io.IOException("WORKSPACE_COPY_FAILED")
            val result = runOneShotShell(
                processSupervisor = supervisor,
                identity = "root",
                command = "mkdir -p " + shellQuote(parent) +
                    " && cp " + shellQuote(source.absolutePath) + " " + shellQuote(destination.absolutePath) +
                    " && chmod 644 " + shellQuote(destination.absolutePath),
                timeoutSeconds = 30,
                environment = TerminalEnvironment.ANDROID,
            )
            check(result.exitCode == 0 && destination.isFile) { "WORKSPACE_COPY_FAILED" }
        } finally {
            supervisor.beginClosing()
        }
    }

    private fun resolve(path: String): File {
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        return file
    }
}
