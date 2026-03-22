package com.routeme.app.auto

import android.content.Intent
import android.os.Build
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.routeme.app.LocationTrackingService
import com.routeme.app.RouteDirection
import com.routeme.app.TrackingEventBus
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.domain.SuggestionUseCase
import com.routeme.app.domain.SyncSettingsUseCase
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class HomeScreen(carContext: CarContext) : Screen(carContext), KoinComponent {

    private val prefs: PreferencesRepository by inject()
    private val syncUseCase: SyncSettingsUseCase by inject()
    private val clientRepository: ClientRepository by inject()
    private val suggestionUseCase: SuggestionUseCase by inject()
    private val trackingEventBus: TrackingEventBus by inject()

    private var syncing = false
    private var syncStatus: String? = null

    override fun onGetTemplate(): Template {
        val direction = prefs.routeDirection
        val isTracking = trackingEventBus.isTracking.value
        val errandsMode = prefs.errandsModeEnabled

        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Suggested Clients")
                    .addText(if (errandsMode) "Errands mode active" else "Clients due for service")
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(SuggestionListScreen(carContext, showAll = false))
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(if (syncing) "Syncing…" else "Refresh Sheet")
                    .addText(syncStatus ?: "Sync clients from Google Sheets")
                    .setOnClickListener { if (!syncing) syncSheet() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(
                        when (direction) {
                            RouteDirection.OUTWARD -> "Direction: Outward ↑"
                            RouteDirection.HOMEWARD -> "Direction: Homeward ↩"
                        }
                    )
                    .addText("Tap to toggle")
                    .setOnClickListener {
                        prefs.routeDirection = when (direction) {
                            RouteDirection.OUTWARD -> RouteDirection.HOMEWARD
                            RouteDirection.HOMEWARD -> RouteDirection.OUTWARD
                        }
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Destinations")
                    .addText(
                        if (errandsMode) "Errands ON — tap for stops"
                        else "${prefs.savedDestinations.size} saved stops"
                    )
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(SuggestionListScreen(carContext, showAll = false))
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(if (isTracking) "Stop Tracking" else "Start Tracking")
                    .addText(if (isTracking) "GPS tracking active" else "Tap to begin route tracking")
                    .setOnClickListener { toggleTracking(isTracking) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("All Clients")
                    .addText("Browse full client list")
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(SuggestionListScreen(carContext, showAll = true))
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setSingleList(list)
            .setTitle("RouteMe")
            .build()
    }

    private fun syncSheet() {
        syncing = true
        syncStatus = null
        invalidate()
        lifecycleScope.launch {
            val url = prefs.sheetsReadUrl
            val result = syncUseCase.syncFromSheets(url)
            syncStatus = when (result) {
                is SyncSettingsUseCase.SyncFromSheetsResult.Success ->
                    result.statusMessage
                is SyncSettingsUseCase.SyncFromSheetsResult.Error ->
                    result.message
            }
            // Sync property stats (pulls lat/lng from property sheet)
            val propUrl = prefs.propertySheetWriteUrl
            if (propUrl.isNotBlank()) {
                syncStatus = "Syncing property data\u2026"
                invalidate()
                val propResult = clientRepository.syncPropertyDataFromSheet(propUrl)
                syncStatus = propResult.message
            }
            syncing = false
            invalidate()
        }
    }

    private fun toggleTracking(currentlyTracking: Boolean) {
        val ctx = carContext
        if (currentlyTracking) {
            ctx.stopService(Intent(ctx, LocationTrackingService::class.java))
            trackingEventBus.setTrackingActive(false)
        } else {
            val intent = Intent(ctx, LocationTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            trackingEventBus.setTrackingActive(true)
        }
        invalidate()
    }
}
