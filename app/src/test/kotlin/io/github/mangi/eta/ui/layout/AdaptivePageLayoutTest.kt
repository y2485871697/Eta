package io.github.mangi.eta.ui.layout

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePageLayoutTest {
    @Test
    fun wideScreenDecisionUsesTheProvidedUnscaledDensity() {
        val widthPx = 1200

        assertTrue(isWideScreen(widthPx, Density(density = 2f)))
        assertFalse(isWideScreen(widthPx, Density(density = 2.2f)))
    }
}
