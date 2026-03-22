package com.routeme.app

import android.app.Application
import com.routeme.app.di.appModule
import com.routeme.app.network.DistanceMatrixHelper
import com.routeme.app.network.GeocodingHelper
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class RouteMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RouteMeApplication)
            modules(appModule)
        }
        DistanceMatrixHelper.apiKey = BuildConfig.MAPS_API_KEY
        GeocodingHelper.apiKey = BuildConfig.MAPS_API_KEY
    }
}
