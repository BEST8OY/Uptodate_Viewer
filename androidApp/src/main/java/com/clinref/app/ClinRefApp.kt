package com.clinref.app

import android.app.Application
import com.clinref.app.di.androidPlatformModule
import com.clinref.shared.di.commonModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ClinRefApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ClinRefApp)
            modules(commonModules + androidPlatformModule)
        }
    }
}
