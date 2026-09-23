package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.data.repository.ProviderBalanceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶部栏与模型选择器共用同一个只读余额指示器。
 *
 * 余额自动刷新继续更新数字，但刷新过程必须完全静默：既没有“刷新中”，
 * 也不显示“已刷新”或任何成功提示，无缓存金额的失败同样不写入提示文本。
 */
class ProviderBalanceIndicatorTest {

    @Test
    fun showsContentOnlyAfterASuccessfulAmountArrives() {
        assertFalse(hasBalanceIndicatorContent(null))
        assertTrue(hasBalanceIndicatorContent(ProviderBalanceState(amount = "¥120.00")))
        assertTrue(
            hasBalanceIndicatorContent(
                ProviderBalanceState(amount = "¥120.00", refreshing = true, updatedAtMillis = 1L),
            ),
        )
        assertTrue(
            hasBalanceIndicatorContent(
                ProviderBalanceState(amount = "¥120.00", error = "Balance query failed; retry shortly"),
            ),
        )
    }

    @Test
    fun refreshingOrFailedWithoutAmountProducesNoPromptText() {
        assertFalse(hasBalanceIndicatorContent(ProviderBalanceState(refreshing = true)))
        assertFalse(
            hasBalanceIndicatorContent(
                ProviderBalanceState(error = "Balance query failed; retry shortly"),
            ),
        )
        assertFalse(
            hasBalanceIndicatorContent(
                ProviderBalanceState(refreshing = true, error = "Balance query failed; retry shortly"),
            ),
        )
    }
}
