package com.clinref.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.clinref.app.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET promptTokens = promptTokens + :promptDelta, completionTokens = completionTokens + :completionDelta, toolTokens = toolTokens + :toolDelta, updatedAt = :now WHERE id = :id")
    suspend fun updateTokenCounts(id: String, promptDelta: Int, completionDelta: Int, toolDelta: Int, now: Long = System.currentTimeMillis())

    @Query("SELECT (promptTokens + completionTokens + toolTokens) >= tokenLimit FROM conversations WHERE id = :id")
    suspend fun isOverTokenLimit(id: String): Boolean
}
