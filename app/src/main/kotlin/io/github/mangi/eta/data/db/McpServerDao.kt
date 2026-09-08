package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY sort_order ASC")
    fun serversFlow(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY sort_order ASC")
    suspend fun servers(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun serverById(id: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(server: McpServerEntity)

    @Update
    suspend fun update(server: McpServerEntity): Int

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: String): Int
}
