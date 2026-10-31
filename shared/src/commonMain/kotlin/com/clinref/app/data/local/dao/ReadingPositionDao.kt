package com.clinref.app.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.clinref.app.data.local.entity.ReadingPositionEntity

@Dao
interface ReadingPositionDao {

    @Query("SELECT * FROM reading_position WHERE topicId = :topicId")
    suspend fun get(topicId: String): ReadingPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ReadingPositionEntity>)

    @Query("DELETE FROM reading_position WHERE topicId = :topicId")
    suspend fun delete(topicId: String)

    /** Keeps only the [keep] most recently updated rows. */
    @Query(
        "DELETE FROM reading_position WHERE topicId NOT IN " +
            "(SELECT topicId FROM reading_position ORDER BY updatedAt DESC LIMIT :keep)"
    )
    suspend fun prune(keep: Int)

    @Query("SELECT COUNT(*) FROM reading_position")
    suspend fun count(): Int
}
