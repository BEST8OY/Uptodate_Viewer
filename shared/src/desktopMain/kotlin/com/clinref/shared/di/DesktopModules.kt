package com.clinref.shared.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.clinref.app.data.local.AppDatabase
import com.clinref.app.data.local.AppDatabaseConstructor
import com.clinref.shared.secure.DesktopSecurePreferences
import com.clinref.shared.secure.SecurePreferences
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

val desktopPlatformModule: Module = module {
    single<SecurePreferences> { DesktopSecurePreferences() }

    single<AppDatabase> {
        val baseDir = File(System.getProperty("user.home"), ".clinref")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val dbFile = File(baseDir, "clinref_ai.db")

        Room.databaseBuilder(
            name = dbFile.absolutePath,
            factory = { AppDatabaseConstructor.initialize() }
        )
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}

val desktopAppModules: List<Module> = commonModules + desktopPlatformModule
