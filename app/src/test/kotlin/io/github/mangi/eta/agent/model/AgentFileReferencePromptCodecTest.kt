package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileReferencePromptCodecTest {
    @Test
    fun formatAndParse_roundTripsFilesDirectoriesAndUnicode() {
        val references = listOf(
            AgentFileReference(
                displayName = "报告 终稿.txt",
                absolutePath = "/storage/emulated/0/Download/报告 终稿.txt",
                kind = AgentFileReferenceKind.File,
            ),
            AgentFileReference(
                displayName = "项目资料",
                absolutePath = "/data/local/tmp/项目资料",
                kind = AgentFileReferenceKind.Directory,
            ),
        )

        val formatted = AgentFileReferencePromptCodec.format("总结这些内容", references)

        assertEquals(
            """# Files mentioned by the user:

## 报告 终稿.txt: /storage/emulated/0/Download/报告 终稿.txt

## 项目资料/: /data/local/tmp/项目资料

## My request:
总结这些内容""",
            formatted,
        )
        assertEquals(
            AgentFileReferencePrompt(request = "总结这些内容", references = references),
            AgentFileReferencePromptCodec.parse(formatted),
        )
    }

    @Test
    fun format_supportsFileOnlyMessageAndDeduplicatesPaths() {
        val reference = AgentFileReference(
            displayName = "archive.zip",
            absolutePath = "/storage/emulated/0/Download/archive.zip",
            kind = AgentFileReferenceKind.File,
        )

        val parsed = AgentFileReferencePromptCodec.parse(
            AgentFileReferencePromptCodec.format("", listOf(reference, reference.copy(displayName = "重复")))
        )

        assertEquals("", parsed.request)
        assertEquals(listOf(reference), parsed.references)
    }

    @Test
    fun parse_preservesOrdinaryOrMalformedUserText() {
        val ordinary = "# Files mentioned by the user:\n\n这只是普通文本"

        assertEquals(
            AgentFileReferencePrompt(request = ordinary, references = emptyList()),
            AgentFileReferencePromptCodec.parse(ordinary),
        )
        assertEquals("原始请求", AgentFileReferencePromptCodec.format("原始请求", emptyList()))
    }

    @Test
    fun policy_requiresTerminalToolsAndUsesFirstFileForEmptyTitle() {
        val reference = AgentFileReference(
            displayName = "device.log",
            absolutePath = "/data/local/tmp/device.log",
            kind = AgentFileReferenceKind.File,
        )

        assertFalse(AgentFileReferencePolicy.canSend(listOf(reference), terminalToolsEnabled = false))
        assertTrue(AgentFileReferencePolicy.canSend(listOf(reference), terminalToolsEnabled = true))
        assertTrue(AgentFileReferencePolicy.canSend(emptyList(), terminalToolsEnabled = false))
        assertEquals("device.log", AgentFileReferencePolicy.titleSource("", listOf(reference)))
        assertEquals("分析日志", AgentFileReferencePolicy.titleSource("分析日志", listOf(reference)))
    }
}
