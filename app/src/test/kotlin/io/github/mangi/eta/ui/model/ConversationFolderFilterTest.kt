package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationFolderFilterTest {
    private val chats = listOf(
        summary("a", folderId = null),
        summary("b", folderId = "work"),
        summary("c", folderId = ""),
        summary("d", folderId = "life"),
    )

    @Test
    fun unfiledViewHidesFolderedConversations() {
        assertEquals(listOf("a", "c"), chats.filterForFolder(null).map { it.id })
    }

    @Test
    fun selectedFolderOnlyShowsItsConversations() {
        assertEquals(listOf("b"), chats.filterForFolder("work").map { it.id })
    }

    private fun summary(id: String, folderId: String?) = ConversationSummaryUi(
        id = id,
        title = id,
        preview = id,
        timeLabel = "now",
        mode = ConversationModeUi.Chat,
        folderId = folderId,
    )
}
