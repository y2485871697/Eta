package io.github.mangi.eta.hook.xiaoai

import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XiaoAiHandoffTest {
    @Test
    fun handoffRoundTripPreservesSourceDialogAndDismissPolicy() {
        val handoff = XiaoAiHandoff.create(
            runId = "run-1",
            dialogId = "dialog-1",
            prompt = "分析这张图片",
        )

        val roundTripped = AgentRuntimeWire.entryHandoffFromBundle(
            AgentRuntimeWire.toBundle(handoff)
        )
        val archive = AgentExternalArchivePayload.from(roundTripped.payload)

        assertEquals("xiaoai", roundTripped.source)
        assertTrue(roundTripped.dismissEntrySurfaceOnForegroundOperation)
        assertEquals("dialog-1", XiaoAiHandoff.dialogIdFrom(roundTripped))
        assertEquals("分析这张图片", archive?.userText)
        assertEquals("dialog-1", archive?.conversationKey)
    }

    @Test
    fun foreignSourceCannotBeParsedAsAXiaoAiDialog() {
        assertEquals(
            "",
            XiaoAiHandoff.dialogIdFrom(
                AgentRuntimeWire.EntryHandoff(
                    id = "run-1",
                    source = "breeno",
                    payload = "{}",
                )
            ),
        )
    }
}
