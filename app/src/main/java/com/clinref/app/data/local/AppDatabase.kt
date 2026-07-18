package com.clinref.app.data.local

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.clinref.app.data.local.dao.ConversationDao
import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.data.local.entity.MessageEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
}
