package com.clinref.app.data.local

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.clinref.app.data.local.dao.ConversationDao
import com.clinref.app.data.local.dao.FavoriteDao
import com.clinref.app.data.local.dao.HistoryDao
import com.clinref.app.data.local.dao.MessageDao
import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.data.local.entity.FavoriteEntity
import com.clinref.app.data.local.entity.HistoryEntity
import com.clinref.app.data.local.entity.MessageEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        HistoryEntity::class,
        FavoriteEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun historyDao(): HistoryDao
    abstract fun favoriteDao(): FavoriteDao
}
