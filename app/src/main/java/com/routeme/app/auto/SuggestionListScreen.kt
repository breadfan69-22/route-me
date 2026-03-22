package com.routeme.app.auto

import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.routeme.app.ClientSuggestion
import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.SavedDestination
import com.routeme.app.ServiceType
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.domain.SuggestionUseCase
import com.routeme.app.TrackingEventBus
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SuggestionListScreen(
    carContext: CarContext,
    private val showAll: Boolean = false,
    private val showDestinations: Boolean = false
) : Screen(carContext), KoinComponent {

    private val suggestionUseCase: SuggestionUseCase by inject()
    private val clientRepository: ClientRepository by inject()
    private val prefs: PreferencesRepository by inject()
    private val trackingEventBus: TrackingEventBus by inject()

    private var suggestions: List<ClientSuggestion> = emptyList()
    private var destinations: List<SavedDestination> = emptyList()
    private var errandsMode = false
    private var loading = true

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = loadData()
        })
    }

    private fun loadData() {
        loading = true
        invalidate()
        lifecycleScope.launch {
            errandsMode = showDestinations || prefs.errandsModeEnabled
            if (errandsMode) {
                destinations = prefs.savedDestinations
            } else {
                val selectedSteps = prefs.selectedSteps
                    .split(",")
                    .mapNotNull { runCatching { ServiceType.valueOf(it.trim()) }.getOrNull() }
                    .toSet()
                    .ifEmpty { setOf(ServiceType.ROUND_1) }

                val clients = clientRepository.loadAllClients()
                val trackingLoc = trackingEventBus.latestLocation.value
                val lastKnown = if (trackingLoc == null) getLastKnownLocation() else null
                val location = trackingLoc ?: lastKnown ?: shopLocation()
                Log.d("AutoSuggestions", "Location source: ${when {
                    trackingLoc != null -> "tracking"
                    lastKnown != null -> "lastKnown"
                    else -> "shop"
                }} (${location.latitude}, ${location.longitude})")
                Log.d("AutoSuggestions", "Clients: ${clients.size}, with coords: ${clients.count { it.latitude != null }}")

                val result = suggestionUseCase.suggestNextClients(
                    clients = clients,
                    selectedServiceTypes = selectedSteps,
                    minDays = if (showAll) 0 else prefs.minDays,
                    cuOverrideEnabled = prefs.cuOverrideEnabled,
                    routeDirection = prefs.routeDirection,
                    activeDestination = prefs.activeDestination,
                    currentLocation = location
                )
                suggestions = result.suggestions.take(6)
                // Fallback: if nothing is due today, show all clients ranked
                if (suggestions.isEmpty() && !showAll) {
                    val fallback = suggestionUseCase.suggestNextClients(
                        clients = clients,
                        selectedServiceTypes = selectedSteps,
                        minDays = 0,
                        cuOverrideEnabled = prefs.cuOverrideEnabled,
                        routeDirection = prefs.routeDirection,
                        activeDestination = prefs.activeDestination,
                        currentLocation = location
                    )
                    suggestions = fallback.suggestions.take(6)
                }
            }
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        if (loading) {
            return ListTemplate.Builder()
                .setTitle(screenTitle())
                .setLoading(true)
                .build()
        }

        return if (errandsMode) buildErrandsTemplate() else buildSuggestionsTemplate()
    }

    private fun screenTitle() = when {
        errandsMode -> "Errands"
        showAll -> "All Clients"
        else -> "Suggested Clients"
    }

    private fun buildSuggestionsTemplate(): Template {
        val listBuilder = ItemList.Builder()
        if (suggestions.isEmpty()) {
            listBuilder.setNoItemsMessage("No clients found")
        }
        suggestions.forEach { s ->
            val dist = s.distanceToShopMiles?.let { "%.1f mi".format(it) } ?: ""
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(s.client.name)
                    .addText("${s.client.address}  ·  $dist".trim(' ', '·', ' '))
                    .setOnClickListener { screenManager.push(ClientDetailScreen(carContext, s)) }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle(screenTitle())
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun buildErrandsTemplate(): Template {
        val listBuilder = ItemList.Builder()
        if (destinations.isEmpty()) {
            listBuilder.setNoItemsMessage("No destinations queued. Add stops on your phone.")
        }
        destinations.forEach { dest ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(dest.name)
                    .addText(dest.address)
                    .setOnClickListener {
                        val uri = android.net.Uri.parse(
                            "geo:${dest.lat},${dest.lng}?q=${dest.lat},${dest.lng}(${android.net.Uri.encode(dest.name)})"
                        )
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            carContext.applicationContext.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("SuggestionListScreen", "Nav launch failed", e)
                        }
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Errands")
            .setHeaderAction(Action.BACK)
            .build()
    }

    /** Try the phone's cached GPS fix (needs permission already granted). */
    @Suppress("MissingPermission")
    private fun getLastKnownLocation(): Location? = try {
        val lm = carContext.getSystemService(LocationManager::class.java)
        lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } catch (_: SecurityException) { null }

    /** Hardcoded shop coordinates as a final fallback. */
    private fun shopLocation() = Location("shop").apply {
        latitude = SHOP_LAT
        longitude = SHOP_LNG
    }
}
