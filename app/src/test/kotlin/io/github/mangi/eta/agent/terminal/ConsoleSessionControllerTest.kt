package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleSessionControllerTest {

    @Test
    fun openFailureLeavesNoSessionBehind() {
        // 缺失 setsid 的 supervisor 保证进程启动失败，不依赖宿主机是否装有 BusyBox。
        val controller = ConsoleSessionController(
            logger = NoopLogger,
            processSupervisor = ShellProcessSupervisor(
                allowTreeFallback = false,
                setsidCommand = "eta-test-missing-setsid",
            ),
        )
        try {
            val result = controller.open(
                environment = TerminalEnvironment.ANDROID,
                cols = 80,
                rows = 24,
                onOutput = { _, _ -> },
                onExit = { },
            )

            assertTrue("$result", result is ConsoleSessionController.OpenResult.Failed)
            assertEquals(
                "PROCESS_START_FAILED",
                (result as ConsoleSessionController.OpenResult.Failed).code,
            )
            assertTrue(controller.listSessions().isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun failedOpenDoesNotConsumeSessionLimit() {
        val controller = ConsoleSessionController(
            logger = NoopLogger,
            processSupervisor = ShellProcessSupervisor(
                allowTreeFallback = false,
                setsidCommand = "eta-test-missing-setsid",
            ),
        )
        try {
            repeat(8) {
                val result = controller.open(
                    environment = TerminalEnvironment.ANDROID,
                    cols = 80,
                    rows = 24,
                    onOutput = { _, _ -> },
                    onExit = { },
                )
                // 失败路径必须先于上限检查清理自身；反复失败不得退化出 SESSION_LIMIT_REACHED。
                assertEquals(
                    "PROCESS_START_FAILED",
                    (result as ConsoleSessionController.OpenResult.Failed).code,
                )
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun closeIsIdempotentAndRejectsFurtherSessions() {
        val supervisor = ShellProcessSupervisor(
            allowTreeFallback = false,
            setsidCommand = "eta-test-missing-setsid",
        )
        val controller = ConsoleSessionController(logger = NoopLogger, processSupervisor = supervisor)

        controller.close()
        controller.close()

        assertTrue(controller.listSessions().isEmpty())
        assertFalse(controller.sessionAlive("missing"))
        // close 后 supervisor 已 closing，任何 open 都不再启动进程。
        val result = controller.open(
            environment = TerminalEnvironment.ANDROID,
            cols = 80,
            rows = 24,
            onOutput = { _, _ -> },
            onExit = { },
        )
        assertTrue(result is ConsoleSessionController.OpenResult.Failed)
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
