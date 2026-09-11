package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingEffortSliderTest {
    @Test
    fun tapMapsToNearestDiscreteIndex() {
        assertEquals(0, discreteSliderIndexForTap(x = 0f, width = 100f, count = 5))
        assertEquals(4, discreteSliderIndexForTap(x = 100f, width = 100f, count = 5))
        assertEquals(2, discreteSliderIndexForTap(x = 50f, width = 100f, count = 5))
        assertEquals(1, discreteSliderIndexForTap(x = 30f, width = 100f, count = 5))
        assertEquals(0, discreteSliderIndexForTap(x = -8f, width = 100f, count = 5))
        assertEquals(4, discreteSliderIndexForTap(x = 140f, width = 100f, count = 5))
    }

    @Test
    fun tapOnSingleStepOrInvalidWidthStaysAtZero() {
        assertEquals(0, discreteSliderIndexForTap(x = 80f, width = 100f, count = 1))
        assertEquals(0, discreteSliderIndexForTap(x = 80f, width = 0f, count = 5))
    }
}
