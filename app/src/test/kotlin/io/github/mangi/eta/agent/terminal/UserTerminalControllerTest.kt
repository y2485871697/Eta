package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserTerminalControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sessionKeepsCwdAndEnvironmentAcrossExec() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val subdir = File(temporaryFolder.root, "subdir").apply { mkdirs() }
            val open = controller.openSession(
                TerminalEnvironment.ANDROID,
                cwd = temporaryFolder.root.absolutePath, identity = "user",
            )
            assertTrue("$open", open is UserTerminalController.OpenResult.Ready)
            val sessionId = (open as UserTerminalController.OpenResult.Ready).sessionId

            val export = controller.exec(sessionId, "export ETA_TEST_VALUE=streaming") { _, _ -> }
            assertEquals(0, export.exitCode)

            val echoOutput = StringBuilder()
            val echo = controller.exec(sessionId, "printf %s \"\$ETA_TEST_VALUE\"") { text, _ -> echoOutput.append(text) }
            assertEquals(0, echo.exitCode)
            assertEquals("streaming", echoOutput.toString())

            val cd = controller.exec(sessionId, "cd subdir") { _, _ -> }
            assertEquals(0, cd.exitCode)
            assertEquals(subdir.absolutePath, cd.cwd)

            val pwdOutput = StringBuilder()
            controller.exec(sessionId, "pwd") { text, _ -> pwdOutput.append(text) }
            assertTrue(pwdOutput.toString().trim().endsWith("subdir"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun sessionsCoexistAndKeepIndependentState() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val firstDir = File(temporaryFolder.root, "first").apply { mkdirs() }
            val secondDir = File(temporaryFolder.root, "second").apply { mkdirs() }
            val first = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = firstDir.absolutePath, identity = "user",
            )
            val second = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = secondDir.absolutePath, identity = "user",
            )
            assertTrue("$first", first is UserTerminalController.OpenResult.Ready)
            assertTrue("$second", second is UserTerminalController.OpenResult.Ready)
            val firstId = (first as UserTerminalController.OpenResult.Ready).sessionId
            val secondId = (second as UserTerminalController.OpenResult.Ready).sessionId
            assertNotEquals(firstId, secondId)

            // 第二个会话的 open 不再挤掉第一个会话。
            assertTrue(controller.sessionAlive(firstId))
            assertTrue(controller.sessionAlive(secondId))
            assertEquals(2, controller.listSessions().size)

            controller.exec(firstId, "export ETA_SESSION_MARK=one") { _, _ -> }
            val firstOutput = StringBuilder()
            controller.exec(firstId, "printf %s \"\$ETA_SESSION_MARK\"") { text, _ -> firstOutput.append(text) }
            assertEquals("one", firstOutput.toString())

            // 另一个会话看不到第一个会话的环境变量。
            val secondOutput = StringBuilder()
            controller.exec(secondId, "printf %s \"\${ETA_SESSION_MARK:-empty}\"") { text, _ -> secondOutput.append(text) }
            assertEquals("empty", secondOutput.toString())
        } finally {
            controller.close()
        }
    }

    @Test
    fun stopSessionOnlyTerminatesTargetSession() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val first = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user",
            ) as UserTerminalController.OpenResult.Ready
            val second = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user",
            ) as UserTerminalController.OpenResult.Ready

            controller.stopSession(first.sessionId)

            assertFalse(controller.sessionAlive(first.sessionId))
            assertTrue(controller.sessionAlive(second.sessionId))
            val output = StringBuilder()
            val result = controller.exec(second.sessionId, "echo alive") { text, _ -> output.append(text) }
            assertEquals(0, result.exitCode)
            assertEquals("alive", output.toString().trim())
        } finally {
            controller.close()
        }
    }

    @Test
    fun sessionLimitIsEnforced() {
        val controller = UserTerminalController(NoopLogger)
        try {
            repeat(6) { index ->
                val open = controller.openSession(
                    TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user",
                )
                assertTrue("session $index: $open", open is UserTerminalController.OpenResult.Ready)
            }
            val overflow = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user",
            )
            assertTrue(overflow is UserTerminalController.OpenResult.Failed)
            assertEquals(
                "SESSION_LIMIT_REACHED",
                (overflow as UserTerminalController.OpenResult.Failed).code,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun execStreamsOutputBeforeCompletion() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val open = controller.openSession(
                TerminalEnvironment.ANDROID,
                cwd = temporaryFolder.root.absolutePath, identity = "user",
            )
            assertTrue(open is UserTerminalController.OpenResult.Ready)
            val sessionId = (open as UserTerminalController.OpenResult.Ready).sessionId

            val firstDelta = CountDownLatch(1)
            val output = StringBuilder()
            val execThread = thread(name = "test-user-terminal-exec") {
                controller.exec(sessionId, "echo first; sleep 1; echo second") { text, _ ->
                    output.append(text)
                    if (output.contains("first")) firstDelta.countDown()
                }
            }

            // 命令整体约 1 秒才结束；首段输出必须在这之前流式到达。
            assertTrue("first delta should arrive before exec completes", firstDelta.await(500, TimeUnit.MILLISECONDS))
            execThread.join(5_000)
            assertFalse(execThread.isAlive)
            assertTrue(output.toString().contains("second"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun statusMarkerIsNotLeakedToOutput() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val open = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user",
            ) as UserTerminalController.OpenResult.Ready
            val output = StringBuilder()
            val result = controller.exec(open.sessionId, "echo hello") { text, _ -> output.append(text) }
            assertEquals(0, result.exitCode)
            assertEquals("hello", output.toString().trim())
            assertFalse(output.toString().contains("__ETA_STATUS_"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun nonZeroExitCodeIsReported() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val open = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user",
            ) as UserTerminalController.OpenResult.Ready
            val result = controller.exec(open.sessionId, "(exit 42)") { _, _ -> }
            assertEquals(42, result.exitCode)
            assertFalse(result.sessionClosed)
            assertTrue(controller.sessionAlive(open.sessionId))
        } finally {
            controller.close()
        }
    }

    @Test
    fun exitCommandClosesSessionAndReopenWorks() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val open = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user",
            ) as UserTerminalController.OpenResult.Ready
            val result = controller.exec(open.sessionId, "exit") { _, _ -> }
            assertNull(result.exitCode)
            assertTrue(result.sessionClosed)
            assertFalse(controller.sessionAlive(open.sessionId))

            val reopen = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user",
            )
            assertTrue(reopen is UserTerminalController.OpenResult.Ready)
            val reopenedId = (reopen as UserTerminalController.OpenResult.Ready).sessionId
            val output = StringBuilder()
            val exec = controller.exec(reopenedId, "echo ok") { text, _ -> output.append(text) }
            assertEquals(0, exec.exitCode)
            assertEquals("ok", output.toString().trim())
        } finally {
            controller.close()
        }
    }

    @Test
    fun stopSessionTerminatesLongCommand() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val open = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user",
            ) as UserTerminalController.OpenResult.Ready

            val execResult = arrayOfNulls<UserTerminalController.ExecResult>(1)
            val started = CountDownLatch(1)
            val execThread = thread(name = "test-user-terminal-stop") {
                started.countDown()
                execResult[0] = controller.exec(open.sessionId, "sleep 30") { _, _ -> }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            Thread.sleep(300)

            controller.stopSession(open.sessionId)
            execThread.join(5_000)
            assertFalse(execThread.isAlive)

            val result = execResult[0]
            assertNotNull(result)
            assertTrue(result!!.sessionClosed)
            assertTrue(result.interrupted)
            assertNull(result.exitCode)
            assertFalse(controller.sessionAlive(open.sessionId))
        } finally {
            controller.close()
        }
    }

    @Test
    fun interactiveCommandReadsStdinInputWithoutEatingStatusMarker() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val open = controller.openSession(
                TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user",
            ) as UserTerminalController.OpenResult.Ready

            val output = StringBuilder()
            val execResult = arrayOfNulls<UserTerminalController.ExecResult>(1)
            val execThread = thread(name = "test-user-terminal-input") {
                execResult[0] = controller.exec(open.sessionId, "read x; printf 'got:%s' \"\$x\"") { text, _ ->
                    output.append(text)
                }
            }
            // read 阻塞期间状态标记不在 stdin 中；写入的用户输入完整到达前台命令。
            Thread.sleep(300)
            assertTrue(controller.writeInput(open.sessionId, "hello\n"))
            execThread.join(5_000)
            assertFalse(execThread.isAlive)

            val result = execResult[0]
            assertNotNull(result)
            assertEquals(0, result!!.exitCode)
            assertFalse(result.sessionClosed)
            assertTrue(output.toString().contains("got:hello"))
        } finally {
            controller.close()
        }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
