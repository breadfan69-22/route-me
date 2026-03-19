package com.routeme.app.di

import com.routeme.app.AppDatabase
import com.routeme.app.TrackingEventBus
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WriteBackRetryQueue
import com.routeme.app.data.WeatherRepository
import com.routeme.app.domain.ArrivalUseCase
import com.routeme.app.domain.DestinationQueueUseCase
import com.routeme.app.domain.MapsExportUseCase
import com.routeme.app.domain.RouteHistoryUseCase
import com.routeme.app.domain.RoutingEngine
import com.routeme.app.domain.ServiceCompletionUseCase
import com.routeme.app.domain.SuggestionUseCase
import com.routeme.app.domain.SyncSettingsUseCase
import com.routeme.app.ui.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().clientDao() }
    single { get<AppDatabase>().nonClientStopDao() }
    single { get<AppDatabase>().weatherDao() }
    single { get<AppDatabase>().geocodeCacheDao() }

    single { ClientRepository(androidContext(), get(), get(), get()) }
    single { PreferencesRepository(androidContext()) }
    single { WeatherRepository(get()) }
    single { RoutingEngine() }
    factory { ArrivalUseCase(get()) }
    factory { SuggestionUseCase(get()) }
    factory { DestinationQueueUseCase(get(), get()) }
    factory { RouteHistoryUseCase(get()) }
    factory { MapsExportUseCase() }
    single { TrackingEventBus() }
    single { WriteBackRetryQueue(get()) }
    factory { ServiceCompletionUseCase(get(), get(), get()) }
    factory { SyncSettingsUseCase(get(), get(), get()) }

    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
