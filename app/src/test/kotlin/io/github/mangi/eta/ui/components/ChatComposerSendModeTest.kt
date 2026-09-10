package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatComposerSendModeTest {
    @Test
    fun streamingEmptyShowsStop() {
        assertEquals(
            "stop",
            resolveChatComposerSendMode(
                isStreaming = true,
                isPaused = false,
                hasSteerContent = false,
                canStartNewSend = false,
            ),
        )
    }

    @Test
    fun streamingWithTextShowsSend() {
        assertEquals(
            "send",
            resolveChatComposerSendMode(
                isStreaming = true,
                isPaused = false,
                hasSteerContent = true,
                canStartNewSend = true,
            ),
        )
    }

    @Test
    fun pausedEmptyShowsContinue() {
        assertEquals(
            "continue",
            resolveChatComposerSendMode(
                isStreaming = true,
                isPaused = true,
                hasSteerContent = false,
                canStartNewSend = false,
            ),
        )
    }

    @Test
    fun pausedWithTextShowsSend() {
        assertEquals(
            "send",
            resolveChatComposerSendMode(
                isStreaming = true,
                isPaused = true,
                hasSteerContent = true,
                canStartNewSend = true,
            ),
        )
    }

    @Test
    fun idleEmptyShowsIdle() {
        assertEquals(
            "idle",
            resolveChatComposerSendMode(
                isStreaming = false,
                isPaused = false,
                hasSteerContent = false,
                canStartNewSend = false,
            ),
        )
    }

    @Test
    fun idleReadyShowsSend() {
        assertEquals(
            "send",
            resolveChatComposerSendMode(
                isStreaming = false,
                isPaused = false,
                hasSteerContent = true,
                canStartNewSend = true,
            ),
        )
    }

    @Test
    fun streamingWithAttachmentsShowsSend() {
        assertEquals(
            "send",
            resolveChatComposerSendMode(
                isStreaming = true,
                isPaused = false,
                hasSteerContent = true,
                canStartNewSend = true,
            ),
        )
    }

    @Test
    fun pausedWithAttachmentsShowsSend() {
        assertEquals(
            "send",
            resolveChatComposerSendMode(
                isStreaming = true,
                isPaused = true,
                hasSteerContent = true,
                canStartNewSend = true,
            ),
        )
    }
}
