package io.github.mangi.eta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.mangi.eta", "io.github.mangi.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.mangi.eta:voice", "io.github.mangi.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.mangi.eta:voice_session", "io.github.mangi.eta"))
    }
}
