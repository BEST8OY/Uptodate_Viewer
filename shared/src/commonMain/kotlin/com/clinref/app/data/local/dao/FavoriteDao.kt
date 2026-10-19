package com.clinref.app.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.clinref.app.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY timestamp DESC")
    fun getAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT COUNT(*) FROM favorites")
    fun getCount(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE topicId = :topicId)")
    fun isFavorite(topicId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE topicId = :topicId")
    suspend fun delete(topicId: String)

    @Query("DELETE FROM favorites")
    suspend fun deleteAll()
}
