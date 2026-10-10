package io.github.mangi.eta.ui.model

import io.github.mangi.eta.ui.components.AgentTimelineEntry
import io.github.mangi.eta.ui.components.toTimelineEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTerminalMessageOrderTest {
    @Test
    fun alreadyOrderedTerminalBodiesRetainTheOriginalListAndMessageInstances() {
        val runId = "already-ordered"
        val text = assistant("assistant-$runId-1-0", "full saved body\n".repeat(4_096))
        val body = listOf(user(runId), text, thinking(runId, 1), tool(runId, 1))
        for (code in terminalCodes) {
            val notice = SystemNoticeMessageUi("interrupted-$runId", code, "saved detail")
            val input = body + notice

            assertSame(input, normalizeTerminalRunMessages(runId, input))
            assertSame(input, input.withTerminalBodiesInOrder())
            assertSame(notice, input.withTerminalBodiesInOrder().last())
        }
    }

    @Test
    fun equalRepeatedSnapshotsStillDeduplicateAndRetainTheLatestBodyInstance() {
        val runId = "equal-replay"
        val text = assistant("assistant-$runId-1-0", "identical payload")
        val latestText = text.copy()
        val notice = SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.Stopped, "detail")
        val latestNotice = notice.copy()
        assertNotSame(text, latestText)
        assertEquals(text, latestText)
        val input = listOf(user(runId), text, latestText, notice, latestNotice)
        val expected = listOf(input.first(), latestText, latestNotice)

        assertNormalized(runId, input, expected)
        assertProjected(input, expected)
        val actual = input.withTerminalBodiesInOrder()
        assertNotSame(input, actual)
        assertSame(latestText, actual[1])
    }

    @Test
    fun stoppedNoticeFollowsAllRecordedAssistantThinkingAndToolContent() {
        assertTerminalBodyOrder(SystemNoticeCode.Stopped, "assistant-order-run-4-result")
    }

    @Test
    fun runtimeFailedNoticeFollowsAllRecordedAssistantThinkingAndToolContent() {
        assertTerminalBodyOrder(SystemNoticeCode.RuntimeFailed, "assistant-order-run-4-result")
    }

    @Test
    fun emptyResultNoticeFollowsAllRecordedAssistantThinkingAndToolContent() {
        assertTerminalBodyOrder(SystemNoticeCode.EmptyResult, "assistant-order-run-4-result")
    }

    @Test
    fun completedNoticeFollowsAllRecordedAssistantThinkingAndToolContent() {
        assertTerminalBodyOrder(SystemNoticeCode.Completed, "virtual-completed-order-run")
    }

    @Test
    fun interruptedNoticeFollowsAllRecordedAssistantThinkingAndToolContent() {
        assertTerminalBodyOrder(SystemNoticeCode.Interrupted, "interrupted-order-run")
    }

    @Test
    fun replacingRoundUsagePlaceholderWithNoticeKeepsTheWholeBodyBeforeIt() {
        val runId = "usage-run"
        val user = user(runId)
        val partial = assistant("assistant-$runId-7-0", "正文已经到达，不能被空结果吞掉。\n")
        val placeholder = AgentMessageUi(
            id = "assistant-$runId-7-usage",
            content = "",
            usage = TokenUsageUi(inputTokens = 321, outputTokens = 45),
        )
        val lateThinking = thinking(runId, 7)
        val lateTool = tool(runId, 7)
        val beforeReplacement = listOf(user, partial, placeholder, lateThinking, lateTool)

        for (code in terminalCodes) {
            // Reproduce the terminal projection replacing the existing usage slot.
            // The sorter receives real UI messages, not a reduced ID-only fixture.
            val notice = SystemNoticeMessageUi(placeholder.id, code, "terminal detail: $code")
            val replaced = beforeReplacement.map { if (it.id == placeholder.id) notice else it }
            val expected = listOf(user, partial, lateThinking, lateTool, notice)

            assertNormalized(runId, replaced, expected)
            assertProjected(replaced, expected)
        }

        assertEquals("The fixture's original placeholder must not be mutated", placeholder, beforeReplacement[2])
    }

    @Test
    fun everyAssistantIdShapeAndAllBodyMetadataSurviveNormalization() {
        val runId = "body-run"
        val user = user(runId)
        val legacy = assistant("assistant-$runId", "旧版正文\n")
        val round = assistant("assistant-$runId-1", "round-only body")
        val firstBlock = assistant("assistant-$runId-2-0", "第一段\n```kotlin\nval n = 1\n```\n")
        val secondBlock = assistant("assistant-$runId-2-1", "第二段保留末尾空格  \n")
        val result = assistant("assistant-$runId-3-result", "[最终正文](https://example.invalid/result)")
        val usage = AgentMessageUi(
            id = "assistant-$runId-3-usage",
            content = "",
            usage = TokenUsageUi(contextTokens = 902, inputTokens = 700, outputTokens = 202, cachedTokens = 90),
            generatedAtMillis = 123_456L,
        )
        val notice = SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.RuntimeFailed, "disconnect detail")
        val thinking = thinking(runId, 3)
        val tool = tool(runId, 3)
        val input = listOf(user, legacy, round, firstBlock, notice, thinking, tool, secondBlock, result, usage)
        val expected = listOf(user, legacy, round, firstBlock, thinking, tool, secondBlock, result, usage, notice)

        assertNormalized(runId, input, expected)
        assertProjected(input, expected)
    }

    @Test
    fun repeatedIdsKeepLatestFullSnapshotsInTheirOriginalBodySlots() {
        val runId = "replay-run"
        val user = user(runId)
        val firstText = assistant("assistant-$runId-1-0", "初稿").copy(isStreaming = true, usage = null)
        val firstThinking = thinking(runId, 1).copy(content = "思考中", isStreaming = true, elapsedSeconds = 1)
        val firstTool = tool(runId, 1).copy(status = ToolActivityStatusUi.Running, resultSummary = null, imageCount = 0)
        val firstNotice = SystemNoticeMessageUi("assistant-$runId-2-usage", SystemNoticeCode.Stopped, "first detail")
        val nextUser = user("next-run")
        val nextText = assistant("assistant-next-run-1-0", "另一个 run 的正文")
        val latestText = firstText.copy(
            content = "初稿\n补全正文，不得丢失。  \n",
            isStreaming = false,
            usage = TokenUsageUi(inputTokens = 88, outputTokens = 34, reasoningTokens = 12),
            generatedAtMillis = 987_654L,
        )
        val latestThinking = firstThinking.copy(content = "完整思考\n第二行", isStreaming = false, elapsedSeconds = 17)
        val latestTool = firstTool.copy(status = ToolActivityStatusUi.Success, resultSummary = "完整结果\nEND", imageCount = 2)
        val latestNotice = firstNotice.copy(detail = "latest terminal detail")
        val input = listOf(
            user, firstText, firstThinking, firstNotice, firstTool, nextUser, nextText,
            latestThinking, latestTool, latestText, latestNotice, latestTool, latestNotice,
        )
        val expected = listOf(user, latestText, latestThinking, latestTool, latestNotice, nextUser, nextText)

        assertNormalized(runId, input, expected)
        assertProjected(input, expected)
    }

    @Test
    fun incrementalReplayUpdatesExistingIdsWithoutGrowingOrLosingTheBody() {
        val runId = "incremental-run"
        val user = user(runId)
        val text = assistant("assistant-$runId-1-0", "已保存正文")
        val thinking = thinking(runId, 1)
        val tool = tool(runId, 1)
        val notice = SystemNoticeMessageUi("assistant-$runId-2-usage", SystemNoticeCode.RuntimeFailed, "transport closed")
        val expected = listOf(user, text, thinking, tool, notice)
        val initial = listOf(user, text, notice, thinking, tool)
        val once = normalizeTerminalRunMessages(runId, initial)
        assertEquals(expected, once)

        var replayed = once
        repeat(3) {
            replayed = normalizeTerminalRunMessages(runId, replayed + listOf(thinking, tool, text, notice))
            assertEquals("Replay must keep every payload exactly once", expected, replayed)
            assertUniqueIds(replayed)
        }
        assertEquals(expected, replayed.withTerminalBodiesInOrder())
    }

    @Test
    fun modelRetryAloneIsNotATerminalBoundary() {
        val runId = "retry-run"
        val retry = SystemNoticeMessageUi(
            id = "assistant-$runId-2-usage",
            code = SystemNoticeCode.ModelRetry,
            detail = "attempt 1 of 3; MODEL_TIMEOUT",
        )
        val input = listOf(
            user(runId),
            assistant("assistant-$runId-1-0", "重试前正文"),
            retry,
            thinking(runId, 2),
            tool(runId, 2),
            assistant("assistant-$runId-2-0", "重试后的正文"),
        )

        assertNormalized(runId, input, input)
        assertProjected(input, input)
    }

    @Test
    fun modelRetryDetailRemainsInPlaceWhenALaterTerminalNoticeClosesTheRun() {
        val runId = "retry-terminal-run"
        val user = user(runId)
        val retry = SystemNoticeMessageUi("assistant-$runId-1-result", SystemNoticeCode.ModelRetry, "retry payload")
        val text = assistant("assistant-$runId-2-0", "重试后部分正文")
        val terminal = SystemNoticeMessageUi("assistant-$runId-3-usage", SystemNoticeCode.Stopped, "stop payload")
        val lateTool = tool(runId, 2)
        val input = listOf(user, retry, text, terminal, lateTool)
        val expected = listOf(user, retry, text, lateTool, terminal)

        assertNormalized(runId, input, expected)
        assertProjected(input, expected)
    }

    @Test
    fun otherRunsAndUserMessagesKeepTheirPayloadsAndRelativePositions() {
        val runId = "active-run"
        val oldUser = user("old-run")
        val oldText = assistant("assistant-old-run-1-0", "历史正文")
        val oldNotice = SystemNoticeMessageUi("assistant-old-run-2-usage", SystemNoticeCode.Completed, "old detail")
        val user = user(runId)
        val text = assistant("assistant-$runId-1-0", "本轮正文")
        val supplement = UserMessageUi("user-$runId-supplement-1", "用户补充，不能移动到终态后")
        val notice = SystemNoticeMessageUi("assistant-$runId-2-usage", SystemNoticeCode.Stopped, "active detail")
        val lateThinking = thinking(runId, 2)
        val lateTool = tool(runId, 2)
        val nextUser = user("next-run")
        val nextThinking = thinking("next-run", 1)
        val nextText = assistant("assistant-next-run-1-0", "下一轮正文")
        val nextNotice = SystemNoticeMessageUi("assistant-next-run-2-usage", SystemNoticeCode.EmptyResult, "next detail")
        val input = listOf(
            oldUser, oldText, oldNotice, user, text, supplement, notice,
            nextUser, nextThinking, nextText, lateThinking, lateTool, nextNotice,
        )
        val expected = listOf(
            oldUser, oldText, oldNotice, user, text, supplement, lateThinking, lateTool, notice,
            nextUser, nextThinking, nextText, nextNotice,
        )

        assertNormalized(runId, input, expected)
        assertProjected(input, expected)
        val untouchedIds = setOf(oldUser.id, oldText.id, oldNotice.id, user.id, supplement.id,
            nextUser.id, nextThinking.id, nextText.id, nextNotice.id)
        assertEquals(
            input.filter { it.id in untouchedIds },
            normalizeTerminalRunMessages(runId, input).filter { it.id in untouchedIds },
        )
    }

    @Test
    fun explicitRunNormalizationDoesNotStealAssistantBodiesFromNumericSuffixRun() {
        val fixture = numericSuffixRuns()
        assertNormalized("build", fixture.input, fixture.expected)
    }

    @Test
    fun inferredRunNormalizationDoesNotStealAssistantBodiesFromNumericSuffixRun() {
        val fixture = numericSuffixRuns()
        assertProjected(fixture.input, fixture.expected)
    }

    @Test
    fun explicitRunNormalizationDoesNotConsumeNumericSuffixRunsTerminalNotice() {
        val fixture = numericSuffixTerminal()
        assertNormalized("build", fixture.input, fixture.expected)
    }

    @Test
    fun inferredRunNormalizationDoesNotConsumeNumericSuffixRunsTerminalNotice() {
        val fixture = numericSuffixTerminal()
        assertProjected(fixture.input, fixture.expected)
    }

    @Test
    fun storedProjectionCanInferOwnerFromThinkingOrToolWithoutAUserMessage() {
        val runId = "restored-42"
        val text = assistant("assistant-$runId-1-0", "持久化的完整正文")
        val notice = SystemNoticeMessageUi("assistant-$runId-2-usage", SystemNoticeCode.RuntimeFailed, "restore detail")
        val workItems: List<AgentChatMessageUi> = listOf(thinking(runId, 2), tool(runId, 2))
        for (work in workItems) {
            val input = listOf(text, notice, work)
            val expected = listOf(text, work, notice)
            assertNormalized(runId, input, expected)
            assertProjected(input, expected)
        }
    }

    @Test
    fun storedProjectionCanInferOwnerFromInterruptedAndVirtualCompletedIds() {
        val runId = "restored-run"
        val text = assistant("assistant-$runId-1-0", "无需用户锚点也要保留正文")
        val notices = listOf(
            SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.Interrupted, "interrupted detail"),
            SystemNoticeMessageUi("virtual-completed-$runId", SystemNoticeCode.Completed, "completed detail"),
        )
        for (notice in notices) {
            assertProjected(listOf(notice, text), listOf(text, notice))
        }
    }

    @Test
    fun unrecognizedLegacyIdsAreNotAssignedToARunByAdjacency() {
        val runId = "known-run"
        val input = listOf(
            user(runId),
            assistant("legacy-answer", "legacy body remains visible"),
            SystemNoticeMessageUi("legacy-notice", SystemNoticeCode.Stopped, "legacy detail"),
            thinking(runId, 1),
            tool(runId, 1),
        )

        assertNormalized(runId, input, input)
        assertProjected(input, input)
    }

    @Test
    fun emptyBlankRunAndNonterminalInputsRemainUnchanged() {
        assertNormalized("empty-run", emptyList(), emptyList())
        assertProjected(emptyList(), emptyList())
        val runId = "open-run"
        val open = listOf(user(runId), assistant("assistant-$runId-1-0", "仍在继续"), thinking(runId, 1), tool(runId, 1))
        assertNormalized(runId, open, open)
        assertProjected(open, open)
        val closed = open.take(2) + SystemNoticeMessageUi("assistant-$runId-2-usage", SystemNoticeCode.Stopped) + open.drop(2)
        assertNormalized("", closed, closed)
        assertNormalized("   ", closed, closed)
    }

    @Test
    fun timelineGroupsLateWorkBeforeTerminalWithoutHidingAnyBodyContent() {
        val runId = "timeline-run"
        val user = user(runId)
        val partial = assistant("assistant-$runId-1-0", "终态前已生成的正文\n")
        val notice = SystemNoticeMessageUi("assistant-$runId-2-usage", SystemNoticeCode.Stopped, "timeline terminal detail")
        val thinking = thinking(runId, 2)
        val tool = tool(runId, 2)
        val input = listOf(user, partial, notice, thinking, tool)
        val expected = listOf(user, partial, thinking, tool, notice)

        val entries = input.toTimelineEntries()

        assertEquals(expected, entries.flattenMessages())
        assertEquals(
            listOf(
                AgentTimelineEntry.Message(user),
                AgentTimelineEntry.Message(partial),
                AgentTimelineEntry.WorkProcess("work-${thinking.id}", listOf(thinking, tool)),
                AgentTimelineEntry.Message(notice),
            ),
            entries,
        )
        assertEquals(entries, expected.toTimelineEntries())
        assertEquals(entries, entries.flattenMessages().toTimelineEntries())
    }

    private fun assertTerminalBodyOrder(code: SystemNoticeCode, noticeId: String) {
        val runId = "order-run"
        val user = user(runId)
        val partial = assistant("assistant-$runId-1-0", "第一段正文\n保留换行。\n")
        val notice = SystemNoticeMessageUi(noticeId, code, "diagnostic payload: $code")
        val lateTool = tool(runId, 2)
        val lateThinking = thinking(runId, 3)
        val lateText = assistant("assistant-$runId-3-0", "最后一段正文  \n")
        val input = listOf(user, partial, notice, lateTool, lateThinking, lateText)
        val expected = listOf(user, partial, lateTool, lateThinking, lateText, notice)

        assertNormalized(runId, input, expected)
        assertProjected(input, expected)
    }

    private fun numericSuffixRuns(): OrderFixture {
        val user = user("build")
        val text = assistant("assistant-build-1-0", "short run body")
        val notice = SystemNoticeMessageUi("assistant-build-2-usage", SystemNoticeCode.Stopped, "short run detail")
        val lateTool = tool("build", 2)
        val otherUser = user("build-7")
        // This is round 1 of build-7, not segment 1 of build's round 7.
        val otherText = assistant("assistant-build-7-1", "numeric-suffix run body must stay after its own user")
        val otherThinking = thinking("build-7", 1)
        val otherTool = tool("build-7", 1)
        val otherNotice = SystemNoticeMessageUi("assistant-build-7-2-usage", SystemNoticeCode.Completed, "long run detail")
        return OrderFixture(
            input = listOf(user, text, notice, lateTool, otherUser, otherText, otherThinking, otherTool, otherNotice),
            expected = listOf(user, text, lateTool, notice, otherUser, otherText, otherThinking, otherTool, otherNotice),
        )
    }

    private fun numericSuffixTerminal(): OrderFixture {
        val user = user("build")
        val text = assistant("assistant-build-1-0", "short run body")
        val notice = SystemNoticeMessageUi("interrupted-build", SystemNoticeCode.Stopped, "short run must remain stopped")
        val lateThinking = thinking("build", 1)
        val otherUser = user("build-7")
        val otherTool = tool("build-7", 1)
        // A legacy round-only notice is valid for build-7. It must not overwrite
        // build's notice merely because its suffix also resembles round-segment.
        val otherNotice = SystemNoticeMessageUi("assistant-build-7-1", SystemNoticeCode.RuntimeFailed, "long run failure detail")
        return OrderFixture(
            input = listOf(user, text, notice, lateThinking, otherUser, otherTool, otherNotice),
            expected = listOf(user, text, lateThinking, notice, otherUser, otherTool, otherNotice),
        )
    }

    private fun assertNormalized(
        runId: String,
        input: List<AgentChatMessageUi>,
        expected: List<AgentChatMessageUi>,
    ) {
        val original = input.toList()
        val actual = normalizeTerminalRunMessages(runId, input)
        // DTO equality checks all text, notice detail, tool arguments/results,
        // token usage, timestamps and thinking metadata, not only IDs or counts.
        assertEquals("Full message payloads and order for $runId", expected, actual)
        assertUniqueIds(actual)
        assertSame("Already normalized input must retain its identity", actual, normalizeTerminalRunMessages(runId, actual))
        assertEquals("Normalization must not mutate the caller's snapshot", original, input)
    }

    private fun assertProjected(input: List<AgentChatMessageUi>, expected: List<AgentChatMessageUi>) {
        val original = input.toList()
        val actual = input.withTerminalBodiesInOrder()
        assertEquals("Stored projection must preserve full message payloads and ownership", expected, actual)
        assertUniqueIds(actual)
        assertSame("Already projected input must retain its identity", actual, actual.withTerminalBodiesInOrder())
        assertEquals("Stored projection must not mutate its input", original, input)
    }

    private fun assertUniqueIds(messages: List<AgentChatMessageUi>) {
        assertTrue("Repeated events must not duplicate any message ID", messages.groupingBy { it.id }.eachCount().values.all { it == 1 })
    }

    private fun user(runId: String) = UserMessageUi(
        id = "user-$runId",
        content = "用户问题: $runId\n完整内容",
        images = listOf("content://fixture/$runId/image"),
        isEdited = true,
        imageSources = listOf("fixture-$runId.png"),
        imageIsVideo = listOf(false),
        imageDurationsMs = listOf(null),
    )

    private fun assistant(id: String, content: String) = AgentMessageUi(
        id = id,
        content = content,
        isStreaming = false,
        renderMarkdown = true,
        usage = TokenUsageUi(contextTokens = 100, inputTokens = 70, outputTokens = 30, reasoningTokens = 12, cachedTokens = 9),
        generatedAtMillis = 123_456L,
    )

    private fun thinking(runId: String, round: Int) = ThinkingMessageUi(
        id = "$runId-thinking-$round-0",
        content = "思考正文: $runId / $round\nreasoning END",
        isStreaming = false,
        elapsedSeconds = 9,
        collapsed = true,
    )

    private fun tool(runId: String, round: Int) = ToolActivityMessageUi(
        id = "$runId-tool-$round-call-$round",
        toolName = "workspace_file",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "read report-$round.txt for $runId",
        command = "cat report-$round.txt",
        resultSummary = "工具结果: $runId / $round\nresult END",
        imageCount = 1,
    )

    private fun List<AgentTimelineEntry>.flattenMessages(): List<AgentChatMessageUi> = flatMap { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> listOf(entry.message)
            is AgentTimelineEntry.WorkProcess -> entry.messages
        }
    }

    private data class OrderFixture(
        val input: List<AgentChatMessageUi>,
        val expected: List<AgentChatMessageUi>,
    )

    private val terminalCodes = listOf(
        SystemNoticeCode.Stopped,
        SystemNoticeCode.RuntimeFailed,
        SystemNoticeCode.EmptyResult,
        SystemNoticeCode.Completed,
    )
}
