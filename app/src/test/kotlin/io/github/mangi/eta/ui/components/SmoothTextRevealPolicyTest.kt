package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothTextRevealPolicyTest {
    @Test
    fun revealPathsAppendBatchesButRebuildForNewLayoutsOrRewinds() {
        assertEquals(true, canAppendRevealPath(true, 10, 11))
        assertEquals(true, canAppendRevealPath(true, 10, 30))
        assertEquals(true, canAppendRevealPath(true, 0, 4))
        assertEquals(false, canAppendRevealPath(false, 10, 30))
        assertEquals(false, canAppendRevealPath(true, -1, 4))
        assertEquals(false, canAppendRevealPath(true, 10, 10))
        assertEquals(false, canAppendRevealPath(true, 10, 9))
    }

    @Test
    fun coordinatorTracksBackgroundAnimationSuspension() {
        val coordinator = SmoothTextRevealCoordinator()

        assertEquals(false, coordinator.isAnimationPaused)
        coordinator.pauseAnimationsAndCatchUp()
        assertEquals(true, coordinator.isAnimationPaused)
        coordinator.resumeAnimationsAfterCatchUp()
        assertEquals(false, coordinator.isAnimationPaused)
        coordinator.pauseAnimationsAndCatchUp()
        coordinator.resumeAnimationsWithoutCatchingUp()
        assertEquals(false, coordinator.isAnimationPaused)
    }

    @Test
    fun emptyAndOrdinaryTextExposeEveryGraphemeBoundary() {
        assertArrayEquals(intArrayOf(0), graphemeBoundaries(""))
        assertArrayEquals(intArrayOf(0, 1, 2, 3), graphemeBoundaries("A中B"))
    }

    @Test
    fun emojiSurrogatePairIsOneGrapheme() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("A😀B"),
        )
    }

    @Test
    fun extendedEmojiSequencesAreNeverSplit() {
        assertArrayEquals(
            intArrayOf(0, 1, 12, 13),
            graphemeBoundaries("A👨‍👩‍👧‍👦B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 5, 6),
            graphemeBoundaries("A👍🏽B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 5, 6),
            graphemeBoundaries("A🇨🇳B"),
        )
    }

    @Test
    fun combiningMarkAndCrLfStayInOneGrapheme() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("Ae\u0301B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("A\r\nB"),
        )
    }

    @Test
    fun appendedTextOnlyRebuildsTheLastPotentiallyExtendedGrapheme() {
        val familyPrefix = "A👨‍👩"
        val prefixBoundaries = graphemeBoundaries(familyPrefix)
        val family = "$familyPrefix‍👧‍👦B"

        assertArrayEquals(
            graphemeBoundaries(family),
            updateGraphemeBoundaries(familyPrefix, prefixBoundaries, family),
        )
        assertArrayEquals(
            graphemeBoundaries("A\r\nB"),
            updateGraphemeBoundaries("A\r", graphemeBoundaries("A\r"), "A\r\nB"),
        )
    }

    @Test
    fun nonAppendReplacementFallsBackToACompleteBoundaryScan() {
        val previous = "alpha 😀"
        val replacement = "beta 👨‍👩‍👧‍👦"

        assertArrayEquals(
            graphemeBoundaries(replacement),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    @Test
    fun commonPrefixNeverEndsInsideAChangedSurrogatePair() {
        assertEquals(0, commonUtf16PrefixLength("😀 alpha", "😁 beta"))
        assertEquals(3, commonUtf16PrefixLength("A😀x", "A😀y"))
        assertEquals(0, commonUtf16PrefixLength("first", "second"))
    }

    @Test
    fun changedExtendedGraphemeSnapsPreservedPrefixToPreviousBoundary() {
        val previous = "👨‍👩X"
        val replacement = "👨‍👧Y"
        val commonPrefixEnd = commonUtf16PrefixLength(previous, replacement)
        val preservedBoundary = graphemeBoundaries(replacement)
            .last { boundary -> boundary <= commonPrefixEnd }

        assertEquals(3, commonPrefixEnd)
        assertEquals(0, preservedBoundary)
    }

    @Test
    fun revealSpeedUsesBaseRateThenCatchesUpAndCaps() {
        assertEquals(36f, smoothRevealSpeed(totalBacklog = 0f), FLOAT_TOLERANCE)
        assertEquals(45f, smoothRevealSpeed(totalBacklog = 9f), FLOAT_TOLERANCE)
        assertEquals(100f, smoothRevealSpeed(totalBacklog = 20f), FLOAT_TOLERANCE)
        assertEquals(240f, smoothRevealSpeed(totalBacklog = 1_000f), FLOAT_TOLERANCE)
    }

    @Test
    fun normalFrameAdvancesFractionallyAtBaseRate() {
        assertEquals(
            0.6f,
            advanceSmoothReveal(
                current = 0f,
                target = 10f,
                elapsedSeconds = 1f / 60f,
                totalBacklog = 1f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun catchUpAdvancesMultipleGraphemesWithinSpeedCap() {
        // 240 字/秒的速度上限 × 单帧最大 50ms，一帧最多推进 12 个字素。
        assertEquals(
            15f,
            advanceSmoothReveal(
                current = 3f,
                target = 100f,
                elapsedSeconds = 0.05f,
                totalBacklog = 1_000f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun frameAdvanceClampsInvalidTimeAndTargetBounds() {
        assertEquals(
            2f,
            advanceSmoothReveal(
                current = 2f,
                target = 10f,
                elapsedSeconds = -1f,
                totalBacklog = 10f,
            ),
            FLOAT_TOLERANCE,
        )
        assertEquals(
            5f,
            advanceSmoothReveal(
                current = 4.75f,
                target = 5f,
                elapsedSeconds = 1f,
                totalBacklog = 100f,
            ),
            FLOAT_TOLERANCE,
        )
        assertEquals(
            5f,
            advanceSmoothReveal(
                current = 7f,
                target = 5f,
                elapsedSeconds = 1f,
                totalBacklog = 100f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun markdownBatchEndsOnlyAfterCompleteGraphemes() {
        val content = "A👨‍👩‍👧‍👦中B"

        assertEquals(12, streamingMarkdownBatchEnd(content, start = 0, maxGraphemes = 2))
        assertEquals(13, streamingMarkdownBatchEnd(content, start = 12, maxGraphemes = 1))
        assertEquals(content.length, streamingMarkdownBatchEnd(content, start = 13, maxGraphemes = 8))
    }

    @Test
    fun markdownBatchCompletesGraphemeExtendedAcrossPreviousChunk() {
        val content = "A\u0301B"

        assertEquals(2, streamingMarkdownBatchEnd(content, start = 1, maxGraphemes = 1))
        assertEquals(0, streamingMarkdownBatchEnd(content, start = -2, maxGraphemes = 0))
        assertEquals(content.length, streamingMarkdownBatchEnd(content, start = 99, maxGraphemes = 4))
    }

    @Test
    fun markdownBatchSizeCatchesUpWithoutFloodingAFrame() {
        assertEquals(24, streamingMarkdownBatchSize(backlogChars = 1))
        assertEquals(40, streamingMarkdownBatchSize(backlogChars = 64))
        assertEquals(64, streamingMarkdownBatchSize(backlogChars = 160))
        assertEquals(96, streamingMarkdownBatchSize(backlogChars = 384))
    }

    @Test
    fun markdownDocumentCollapsesSourceBlankLinesIntoSemanticBlocks() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "第一段\n\n\n第二段",
            isComplete = true,
        )

        assertEquals(
            listOf(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.PARAGRAPH),
            topLevelMarkdownBlocks(snapshot.state.node).map { node -> node.type },
        )
    }

    @Test
    fun tableRevealKeysCorrespondToRenderedHeaderAndBodyCells() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "| A | B |\n| --- | --- |\n| C | D |",
            isComplete = true,
        )
        val table = topLevelMarkdownBlocks(snapshot.state.node).single()
        assertEquals(GFMElementTypes.TABLE, table.type)
        val header = table.findChildOfType(GFMElementTypes.HEADER)?.children
            ?.filter { it.type == CELL }.orEmpty()
        val rows = table.children.filter { it.type == GFMElementTypes.ROW }
        val actuallyRenderedCells = header + rows.flatMap { row ->
            row.children.filter { it.type == CELL }
        }
        assertEquals(4, actuallyRenderedCells.size)
        assertEquals(
            actuallyRenderedCells.map { it.startOffset }.toSet(),
            snapshot.state.revealBlockKeys().map { it.sourceOffset }.toSet(),
        )
    }

    @Test
    fun markdownBlockSpacingBuildsReadableHierarchyWithoutLeadingGap() {
        assertEquals(0.sp, markdownBlockSpacing(null, MarkdownElementTypes.PARAGRAPH))
        assertEquals(
            16.sp,
            markdownBlockSpacing(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.PARAGRAPH),
        )
        assertEquals(
            24.sp,
            markdownBlockSpacing(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.ATX_2),
        )
        assertEquals(
            10.sp,
            markdownBlockSpacing(MarkdownElementTypes.ATX_2, MarkdownElementTypes.PARAGRAPH),
        )
        assertEquals(
            16.sp,
            markdownBlockSpacing(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.UNORDERED_LIST),
        )
        assertEquals(
            16.sp,
            markdownBlockSpacing(GFMElementTypes.TABLE, MarkdownElementTypes.PARAGRAPH),
        )
    }

    @Test
    fun streamingListMarkerWaitsForItsOwnContentToStart() {
        val currentItem = RevealBlockKey(10)

        assertEquals(
            false,
            streamingListMarkerVisible(
                coordinatorActive = true,
                firstRevealKey = currentItem,
                startedRevealKeys = emptySet(),
                containsImage = false,
            ),
        )
        assertEquals(
            true,
            streamingListMarkerVisible(
                coordinatorActive = true,
                firstRevealKey = currentItem,
                startedRevealKeys = setOf(currentItem),
                containsImage = false,
            ),
        )
    }

    @Test
    fun streamingListMarkerKeepsImageItemsVisibleAndSuppressesEmptyItems() {
        assertEquals(
            true,
            streamingListMarkerVisible(
                coordinatorActive = true,
                firstRevealKey = null,
                startedRevealKeys = emptySet(),
                containsImage = true,
            ),
        )
        assertEquals(
            false,
            streamingListMarkerVisible(
                coordinatorActive = true,
                firstRevealKey = null,
                startedRevealKeys = emptySet(),
                containsImage = false,
            ),
        )
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }

    @Test
    fun streamingMarkdownWaitsUntilPreviousBlockFinishes() {
        val current = RevealBlockKey(0)
        val next = RevealBlockKey(40)
        assertEquals(
            true,
            streamingMarkdownBlockVisible(
                coordinatorActive = true,
                firstRevealKey = current,
                startedRevealKeys = setOf(current),
                nextRevealKey = current,
            ),
        )
        assertEquals(
            false,
            streamingMarkdownBlockVisible(
                coordinatorActive = true,
                firstRevealKey = next,
                startedRevealKeys = setOf(current),
                nextRevealKey = current,
            ),
        )
        assertEquals(
            true,
            streamingMarkdownBlockVisible(
                coordinatorActive = true,
                firstRevealKey = next,
                startedRevealKeys = setOf(current),
                completedRevealKeys = setOf(current),
                nextRevealKey = next,
            ),
        )
        assertEquals(
            false,
            streamingMarkdownBlockVisible(
                coordinatorActive = true,
                firstRevealKey = RevealBlockKey(80),
                startedRevealKeys = setOf(current),
                nextRevealKey = next,
            ),
        )
        assertEquals(
            true,
            streamingMarkdownBlockVisible(
                coordinatorActive = false,
                firstRevealKey = RevealBlockKey(80),
                startedRevealKeys = emptySet(),
                nextRevealKey = next,
            ),
        )
    }

    @Test
    fun fastPublishedListDoesNotAllocateUnstartedItemRows() {
        val current = RevealBlockKey(10)
        val future = RevealBlockKey(40)
        val rowVisible = { key: RevealBlockKey, started: Set<RevealBlockKey> ->
            streamingListItemVisible(true, key.sourceOffset, key, started, emptySet(), current)
        }
        assertTrue(rowVisible(current, emptySet()))
        assertFalse(rowVisible(future, emptySet()))
        assertTrue(rowVisible(future, setOf(future)))
        assertTrue(streamingListItemVisible(false, 40, future, emptySet(), emptySet(), current))
        assertTrue(streamingListItemVisible(true, 40, future, emptySet(), setOf(future), current))
    }

    @Test
    fun stoppedThoughtWaitsForTheFinalSnapshotAndTypewriterDrain() {
        assertTrue(keepThinkingRevealUntilSettled(false, "正文", null, false, true))
        assertTrue(keepThinkingRevealUntilSettled(false, "正文", "旧正文", true, true))
        assertTrue(keepThinkingRevealUntilSettled(false, "正文", "正文", true, false))
        assertFalse(keepThinkingRevealUntilSettled(false, "正文", "正文", true, true))
        assertTrue(keepThinkingRevealUntilSettled(true, "正文", "正文", true, true))
    }

    @Test
    fun onlyFullyRevealedBlocksFreezeOnceANewTailExists() {
        assertFalse(shouldFreezeStreamingMarkdownBlock(0, 40, blockComplete = false))
        assertTrue(shouldFreezeStreamingMarkdownBlock(0, 40, blockComplete = true))
        assertFalse(shouldFreezeStreamingMarkdownBlock(40, 40, blockComplete = true))
        assertFalse(shouldFreezeStreamingMarkdownBlock(0, null, blockComplete = true))
    }

}
