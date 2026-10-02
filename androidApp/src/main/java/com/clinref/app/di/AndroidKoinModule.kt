package com.clinref.app.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.clinref.app.data.local.AppDatabase
import com.clinref.app.data.secure.SecurePreferences
import com.clinref.shared.secure.SecurePreferences as ISecurePreferences
import org.koin.dsl.module

val androidPlatformModule = module {
    single<AppDatabase> {
        val context: Context = get()
        val dbFile = context.getDatabasePath("clinref_ai.db")
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            dbFile.absolutePath
        )
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    single<ISecurePreferences> {
        SecurePreferences(context = get())
    }
}
