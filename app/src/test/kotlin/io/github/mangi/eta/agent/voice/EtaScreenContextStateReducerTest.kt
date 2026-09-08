package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaScreenContextStateReducerTest {
    private val available = EtaScreenContextUiState(
        phase = EtaScreenContextPhase.AVAILABLE,
        previewDataUrl = "data:image/png;base64,cHJldmlldw==",
    )

    @Test
    fun `select and remove preserve the prepared preview`() {
        val selected = EtaScreenContextStateReducer.select(
            state = available,
            enabled = true,
            hasAttachment = true,
        )
        assertTrue(selected.selected)
        assertEquals(available.previewDataUrl, selected.previewDataUrl)

        val removed = EtaScreenContextStateReducer.remove(selected, enabled = true)
        assertFalse(removed.selected)
        assertEquals(available.previewDataUrl, removed.previewDataUrl)
    }

    @Test
    fun `busy unavailable or missing attachment cannot be selected`() {
        assertEquals(
            available,
            EtaScreenContextStateReducer.select(available, enabled = false, hasAttachment = true),
        )
        assertEquals(
            available,
            EtaScreenContextStateReducer.select(available, enabled = true, hasAttachment = false),
        )
        val capturing = EtaScreenContextUiState(phase = EtaScreenContextPhase.CAPTURING)
        assertEquals(
            capturing,
            EtaScreenContextStateReducer.select(capturing, enabled = true, hasAttachment = true),
        )
    }

    @Test
    fun `consume clears selection and preview`() {
        assertEquals(
            EtaScreenContextUiState(phase = EtaScreenContextPhase.CONSUMED),
            EtaScreenContextStateReducer.consume(),
        )
    }
}
