package com.routeme.app.di

import com.routeme.app.AppDatabase
import com.routeme.app.TrackingEventBus
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WriteBackRetryQueue
import com.routeme.app.domain.RoutingEngine
import com.routeme.app.ui.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().clientDao() }
    single { get<AppDatabase>().nonClientStopDao() }

    single { ClientRepository(androidContext(), get(), get()) }
    single { PreferencesRepository(androidContext()) }
    single { RoutingEngine() }
    single { TrackingEventBus() }
    single { WriteBackRetryQueue(get()) }

    viewModel { MainViewModel(get(), get(), get(), get(), get()) }
}
