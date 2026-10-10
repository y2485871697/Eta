package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
    val folders: List<ConversationFolderUi> = emptyList(),
    val selectedFolderId: String? = null,
    val historyConversations: List<ConversationSummaryUi> = emptyList(),
)

@Immutable
data class ConversationFolderUi(
    val id: String,
    val name: String,
    val sortIndex: Int = 0,
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val updatedAtMillis: Long = 0L,
    val createdAtMillis: Long = 0L,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
    val hasCompletionMarker: Boolean = false,
    val folderId: String? = null,
    // Captured with timeLabel; consumers must not classify it using a live clock.
    val isToday: Boolean = false,
)

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}

fun List<ConversationSummaryUi>.filterForFolder(selectedFolderId: String?): List<ConversationSummaryUi> =
    if (selectedFolderId == null) {
        filter { it.folderId.isNullOrBlank() }
    } else {
        filter { it.folderId == selectedFolderId }
    }

/** Always derive from the complete folder list, never from the previous search results. */
internal fun ConversationPaneUiState.searchResults(): List<ConversationSummaryUi> {
    val query = searchQuery.trim()
    if (query.isEmpty()) return conversations
    return conversations.filter {
        it.title.contains(query, ignoreCase = true) ||
            it.preview.contains(query, ignoreCase = true)
    }
}
