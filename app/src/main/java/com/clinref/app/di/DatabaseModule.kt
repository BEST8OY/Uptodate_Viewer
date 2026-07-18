package com.clinref.app.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.clinref.app.data.local.AppDatabase
import com.clinref.app.data.local.dao.ConversationDao
import com.clinref.app.data.local.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        val dbFile = context.getDatabasePath("clinref_ai.db")
        return Room.databaseBuilder<AppDatabase>(
            context = context,
            name = dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideConversationDao(database: AppDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao {
        return database.messageDao()
    }
}
