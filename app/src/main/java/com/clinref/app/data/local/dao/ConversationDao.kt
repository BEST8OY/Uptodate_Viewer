package com.clinref.app.data.local.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
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
