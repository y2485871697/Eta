package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface ConversationDao {
    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, created_at, updated_at, folder_id, is_pinned " +
            "FROM conversations ORDER BY updated_at DESC"
    )
    suspend fun conversations(): List<ConversationMetadata>

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, created_at, updated_at, folder_id, is_pinned " +
            "FROM conversations ORDER BY updated_at DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun conversationsPage(limit: Int, offset: Int): List<ConversationMetadata>

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC")
    suspend fun messages(): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC")
    suspend fun conversationEntities(): List<ConversationEntity>

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC")
    suspend fun contextCheckpoints(): List<ConversationContextCheckpointEntity>

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun messagesPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity>

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun messageCount(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun conversationCount(): Int

    /** Visible chat bubbles only; thinking/tool rows are not messages. */
    @Query("SELECT COUNT(*) FROM conversation_messages WHERE type IN ('user', 'assistant')")
    suspend fun totalMessageCount(): Int

    @Query(
        "SELECT type, input_tokens, output_tokens, cached_tokens " +
            "FROM conversation_messages WHERE type = 'assistant'"
    )
    suspend fun usageContentRows(): List<UsageContentRow>

    @Query(
        "SELECT date(created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count " +
            "FROM conversations WHERE created_at >= :startAt GROUP BY day"
    )
    suspend fun conversationCountPerDay(startAt: Long): List<ConversationDayCount>

    @Query("SELECT * FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun contextCheckpoint(conversationId: String): ConversationContextCheckpointEntity?

    @Query("SELECT * FROM conversation_state WHERE id = :id")
    suspend fun state(id: String = ConversationStateEntity.SINGLETON_ID): ConversationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ConversationMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContextCheckpoints(checkpoints: List<ConversationContextCheckpointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: ConversationStateEntity)

    @Query("SELECT * FROM conversation_folders ORDER BY sort_index ASC, created_at ASC")
    suspend fun folders(): List<ConversationFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<ConversationFolderEntity>)

    @Query("DELETE FROM conversation_folders")
    suspend fun deleteFolders()

    @Transaction
    suspend fun replaceFolders(folders: List<ConversationFolderEntity>) {
        deleteFolders()
        if (folders.isNotEmpty()) {
            insertFolders(folders)
        }
    }

    @Query("DELETE FROM conversations")
    suspend fun deleteConversations()

    @Query("DELETE FROM conversation_messages")
    suspend fun deleteMessages()

    @Query("DELETE FROM conversation_context_checkpoints")
    suspend fun deleteContextCheckpoints()

    @Query("DELETE FROM conversation_state")
    suspend fun deleteState()

    @Transaction
    suspend fun replaceAll(
        conversations: List<ConversationEntity>,
        messages: List<ConversationMessageEntity>,
        contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
        state: ConversationStateEntity?,
    ) {
        deleteMessages()
        deleteContextCheckpoints()
        deleteConversations()
        deleteState()
        insertConversations(conversations)
        insertContextCheckpoints(contextCheckpoints)
        insertMessages(messages)
        state?.let { insertState(it) }
    }
}


internal data class UsageContentRow(
    val type: String,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Int? = null,
)

internal data class ConversationDayCount(
    val day: String,
    val count: Int,
)
