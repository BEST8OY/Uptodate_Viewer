package com.uptodate.viewer.di

import com.uptodate.viewer.data.AssetDao
import com.uptodate.viewer.data.ContentDao
import com.uptodate.viewer.data.DatabaseManager
import com.uptodate.viewer.data.SearchDao
import com.uptodate.viewer.data.TocDao
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
