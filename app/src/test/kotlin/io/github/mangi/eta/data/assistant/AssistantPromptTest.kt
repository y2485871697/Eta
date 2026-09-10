package io.github.mangi.eta.data.assistant

import io.github.mangi.eta.data.model.AssistantPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPromptTest {
    @Test
    fun identityUsesAssistantName() {
        val prompt = AssistantPrompt.build("Minis", "")
        assertTrue(prompt.startsWith("你是 Minis"))
        assertFalse(prompt.contains("人格设定"))
    }

    @Test
    fun blankNameFallsBackToEta() {
        assertTrue(AssistantPrompt.build("  ", "").startsWith("你是 Eta"))
    }

    @Test
    fun personalityBodyIsAppended() {
        val prompt = AssistantPrompt.build("Eta", "先做事，少客套。")
        assertTrue(prompt.contains("人格设定："))
        assertTrue(prompt.contains("先做事，少客套。"))
        assertEquals(2, prompt.split("人格设定：").size)
    }
}
