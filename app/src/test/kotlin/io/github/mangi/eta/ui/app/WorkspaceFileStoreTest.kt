package io.github.mangi.eta.ui.app

import android.app.Application
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WorkspaceFileStoreTest {
    @Test
    fun freshWorkspaceCanBeListedWithoutRuntimeInitialization() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = WorkspaceFileStore(context)

        assertTrue(store.list("").isEmpty())
        assertTrue(TerminalPrivateStorage.workspace(context.filesDir).isDirectory)
    }

    @Test
    fun existingWorkspaceFilesRemainVisible() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val legacy = File(context.filesDir, "terminal/workspace").apply { mkdirs() }
        File(legacy, "saved.txt").writeText("saved")

        val entries = WorkspaceFileStore(context).list("")

        assertEquals(listOf("saved.txt"), entries.map { it.path })
        assertEquals("saved", File(legacy, "saved.txt").readText())
    }

    @Test
    fun chrootUsesRootWorkspaceBind() {
        val host = resolveWorkspaceHost(
            filesDir = RuntimeEnvironment.getApplication().filesDir,
            linuxReady = true,
            backend = LinuxExecutionBackend.CHROOT,
        )
        assertEquals("/data/local/tmp/eta", host.path.replace("\\", "/"))
    }

    @Test
    fun prootKeepsPrivateWorkspace() {
        val context = RuntimeEnvironment.getApplication()
        val host = resolveWorkspaceHost(
            filesDir = context.filesDir,
            linuxReady = true,
            backend = LinuxExecutionBackend.PROOT,
        )
        assertEquals(TerminalPrivateStorage.workspace(context.filesDir), host)
    }

    @Test
    fun guestPathStaysInsideWorkspace() {
        assertEquals("/workspace", guestWorkspacePath(""))
        assertEquals("/workspace/imports/a.txt", guestWorkspacePath("imports/a.txt"))
    }
}
