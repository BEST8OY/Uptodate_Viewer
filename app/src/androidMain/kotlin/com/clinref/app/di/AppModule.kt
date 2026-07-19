package com.clinref.app.di

import com.clinref.app.data.AssetDao
import com.clinref.app.data.ContentDao
import com.clinref.app.data.DatabaseManager
import com.clinref.app.data.SearchDao
import com.clinref.app.data.TocDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTocDao(databaseManager: DatabaseManager): TocDao {
        return TocDao(databaseManager)
    }

    @Provides
    @Singleton
    fun provideSearchDao(databaseManager: DatabaseManager): SearchDao {
        return SearchDao(databaseManager)
    }

    @Provides
    @Singleton
    fun provideContentDao(databaseManager: DatabaseManager): ContentDao {
        return ContentDao(databaseManager)
    }

    @Provides
    @Singleton
    fun provideAssetDao(databaseManager: DatabaseManager): AssetDao {
        return AssetDao(databaseManager)
    }
}
