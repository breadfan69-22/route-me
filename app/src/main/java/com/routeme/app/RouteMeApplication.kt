package com.routeme.app

import android.app.Application
import com.routeme.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class RouteMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RouteMeApplication)
            modules(appModule)
        }
    }
}
