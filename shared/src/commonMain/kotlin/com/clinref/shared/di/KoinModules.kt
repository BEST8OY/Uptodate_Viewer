package com.clinref.shared.di

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.clinref.app.data.AssetDao
import com.clinref.app.data.ContentDao
import com.clinref.app.data.DatabaseManager
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.SearchDao
import com.clinref.app.data.TocDao
import com.clinref.app.data.ai.RoomChatHistoryProvider
import com.clinref.app.data.local.AppDatabase
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.SecureLogger
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.ConversationRepository
import com.clinref.app.repository.FavoriteRepository
import com.clinref.app.repository.HistoryRepository
import com.clinref.app.repository.SearchRepository
import com.clinref.app.repository.TocRepository
import org.koin.core.module.Module
import org.koin.dsl.module

val commonDatabaseModule: Module = module {
    single<SQLiteDriver> { BundledSQLiteDriver() }
    single { DatabaseManager(driver = get()) }
    single { SearchDao(dbManager = get()) }
    single { TocDao(dbManager = get()) }
    single { ContentDao(dbManager = get()) }
    single { AssetDao(dbManager = get()) }

    single { get<AppDatabase>().conversationDao() }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().historyDao() }
    single { get<AppDatabase>().favoriteDao() }

    single { RoomChatHistoryProvider(messageDao = get()) }
}

val commonRepositoryModule: Module = module {
    single { SearchRepository(searchDao = get()) }
    single { TocRepository(tocDao = get()) }
    single { ContentRepository(contentDao = get(), searchDao = get()) }
    single { AssetRepository(assetDao = get()) }
    single { ConversationRepository(conversationDao = get(), messageDao = get()) }
    single { HistoryRepository(historyDao = get()) }
    single { FavoriteRepository(favoriteDao = get()) }
}

val commonAiModule: Module = module {
    single { SafetyValidator() }
    single { StreamingManager() }
    single { ReliabilityManager() }
    single { SecureLogger() }
    single {
        MedicalDatabaseTools(
            searchRepository = get(),
            contentRepository = get(),
            assetRepository = get()
        )
    }
    single {
        KoogAgentFactory(
            securePreferences = get(),
            medicalDatabaseTools = get(),
            safetyValidator = get(),
            chatHistoryProvider = get(),
            secureLogger = get()
        )
    }
}

val commonViewModelModule: Module = module {
    factory { com.clinref.app.ui.search.SearchViewModel(get()) }
    factory { com.clinref.app.ui.toc.TocViewModel(get()) }
    factory { com.clinref.app.ui.history.HistoryViewModel(get()) }
    factory { com.clinref.app.ui.favorites.FavoritesViewModel(get()) }
    factory { com.clinref.app.ui.conversations.ConversationListViewModel(get()) }
    factory { com.clinref.app.ui.setup.SetupViewModel(get()) }
    factory { com.clinref.app.ui.content.GraphicViewModel(get()) }
    factory { com.clinref.app.ui.content.ContentViewModel(get(), get(), get()) }
    factory { com.clinref.app.ui.settings.SettingsViewModel(get(), get(), get()) }
    factory { com.clinref.app.ui.chat.ChatViewModel(get(), get(), get(), get(), get(), get(), get()) }
}

val commonModules: List<Module> = listOf(
    commonDatabaseModule,
    commonRepositoryModule,
    commonAiModule,
    commonViewModelModule
)
