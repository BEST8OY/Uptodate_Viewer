package com.uptodate.viewer.di

import android.content.Context
import com.uptodate.viewer.data.database.DatabaseManager
import com.uptodate.viewer.data.database.assets.AssetRepository
import com.uptodate.viewer.data.database.content.ContentRepository
import com.uptodate.viewer.data.database.search.SearchRepository
import com.uptodate.viewer.data.database.toc.TocRepository
import com.uptodate.viewer.data.repository.AssetRepositoryImpl
import com.uptodate.viewer.data.repository.ContentRepositoryImpl
import com.uptodate.viewer.data.repository.SearchRepositoryImpl
import com.uptodate.viewer.data.repository.TocRepositoryImpl
import com.uptodate.viewer.domain.persistence.DataStoreFavoritesRepository
import com.uptodate.viewer.domain.persistence.DataStoreHistoryRepository
import com.uptodate.viewer.domain.persistence.FavoritesRepository
import com.uptodate.viewer.domain.persistence.HistoryRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope = applicationScope

    @Provides
    @Singleton
    fun provideTocRepository(dbManager: DatabaseManager): TocRepository {
        return TocRepositoryImpl(dbManager)
    }

    @Provides
    @Singleton
    fun provideContentRepository(dbManager: DatabaseManager): ContentRepository {
        return ContentRepositoryImpl(dbManager)
    }

    @Provides
    @Singleton
    fun provideSearchRepository(dbManager: DatabaseManager): SearchRepository {
        return SearchRepositoryImpl(dbManager)
    }

    @Provides
    @Singleton
    fun provideAssetRepository(dbManager: DatabaseManager): AssetRepository {
        return AssetRepositoryImpl(dbManager)
    }

    @Provides
    @Singleton
    fun provideFavoritesRepository(
        @ApplicationContext context: Context,
        applicationScope: CoroutineScope
    ): FavoritesRepository {
        return DataStoreFavoritesRepository(context, applicationScope)
    }

    @Provides
    @Singleton
    fun provideHistoryRepository(
        @ApplicationContext context: Context,
        applicationScope: CoroutineScope
    ): HistoryRepository {
        return DataStoreHistoryRepository(context, applicationScope)
    }
}
