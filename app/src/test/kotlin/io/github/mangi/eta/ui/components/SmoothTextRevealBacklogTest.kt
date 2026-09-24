package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 正文与思考同时高速流式输出时，显现速度必须只跟随正在推进的块。
 *
 * 回归：一帧只推进一个块，却把同一条回答里其他块（正文 / 思考）的积压也算进速度，
 * 当前块因此一次跨越整行文字：逐字淡入消失、测量高度（列表行）一次性下跳。
 * 修复后只按当前块自己的待显现字数计算速度，单块积压时的自适应追赶不变。
 */
class SmoothTextRevealBacklogTest {
    @Test
    fun advancingBlockIsNotAcceleratedBySiblingBacklog() {
        // 当前块只剩 5 个字，兄弟块还有 1000 个字积压。
        val bounded = advancingRevealBacklog(
            advancingPendingGraphemes = 5f,
            aggregatePendingGraphemes = 1_005f,
        )
        assertEquals(5f, bounded, 0f)

        // 旧行为：按聚合积压加速到每帧 4 个字素（240 / 60）。
        assertEquals(
            999f,
            advanceSmoothReveal(
                current = 995f,
                target = 1_000f,
                elapsedSeconds = 1f / 60f,
                totalBacklog = 1_005f,
            ),
            0.0001f,
        )
        // 修复后：按当前块自己的积压走基础打字节奏，逐字收尾。
        assertEquals(
            995.6f,
            advanceSmoothReveal(
                current = 995f,
                target = 1_000f,
                elapsedSeconds = 1f / 60f,
                totalBacklog = bounded,
            ),
            0.0001f,
        )
    }

    @Test
    fun startOfBlockKeepsTypewriterCadenceWhenAnotherBlockIsBacklogged() {
        assertEquals(5f, advancingRevealBacklog(5f, 100_000f), 0f)
        assertEquals(
            0.6f,
            advanceSmoothReveal(
                current = 0f,
                target = 5f,
                elapsedSeconds = 1f / 60f,
                totalBacklog = advancingRevealBacklog(5f, 100_000f),
            ),
            0.0001f,
        )
    }

    @Test
    fun singleBackloggedBlockStillCatchesUpWithinTheSpeedCap() {
        // 只有这一个块积压时，速度与既有行为一致：回到 240 字素/秒的上限。
        assertEquals(5_000f, advancingRevealBacklog(5_000f, 5_000f), 0f)
        assertEquals(240f, smoothRevealSpeed(advancingRevealBacklog(5_000f, 5_000f)), 0.0001f)
        assertEquals(
            15f,
            advanceSmoothReveal(
                current = 3f,
                target = 100f,
                elapsedSeconds = 0.05f,
                totalBacklog = advancingRevealBacklog(97f, 5_000f),
            ),
            0.0001f,
        )
    }

    @Test
    fun observedAggregateStillBoundsAndNegativesCollapseToZero() {
        assertEquals(1f, advancingRevealBacklog(10f, 1f), 0f)
        assertEquals(0f, advancingRevealBacklog(-3f, 8f), 0f)
        assertEquals(0f, advancingRevealBacklog(3f, -8f), 0f)
    }
}
