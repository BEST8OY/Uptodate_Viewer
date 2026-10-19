package com.clinref.app.data.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.clinref.app.data.local.dao.ConversationDao
import com.clinref.app.data.local.dao.FavoriteDao
import com.clinref.app.data.local.dao.HistoryDao
import com.clinref.app.data.local.dao.MessageDao
import com.clinref.app.data.local.dao.ReadingPositionDao
import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.data.local.entity.FavoriteEntity
import com.clinref.app.data.local.entity.HistoryEntity
import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.data.local.entity.ReadingPositionEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        HistoryEntity::class,
        FavoriteEntity::class,
        ReadingPositionEntity::class
    ],
    version = 1,
    exportSchema = false
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun historyDao(): HistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun readingPositionDao(): ReadingPositionDao
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>

