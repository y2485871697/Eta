package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryIsolationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        AgentMemoryRepository.init(context)
    }

    @Test
    fun assistantsKeepSeparateMemoryFiles() {
        AgentMemoryRepository.replaceAll("助手A的记忆", "assistant-a")
        AgentMemoryRepository.replaceAll("助手B的记忆", "assistant-b")

        assertEquals("助手A的记忆", AgentMemoryRepository.snapshot("assistant-a").content)
        assertEquals("助手B的记忆", AgentMemoryRepository.snapshot("assistant-b").content)

        AgentMemoryRepository.replaceAll("助手A更新", "assistant-a")
        assertEquals("助手A更新", AgentMemoryRepository.snapshot("assistant-a").content)
        assertEquals("助手B的记忆", AgentMemoryRepository.snapshot("assistant-b").content)
    }
}
