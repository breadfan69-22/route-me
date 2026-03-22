package com.routeme.app.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.routeme.app.ClientSuggestion
import com.routeme.app.ServiceType
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.domain.ServiceCompletionUseCase
import com.routeme.app.TrackingEventBus
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ClientDetailScreen(
    carContext: CarContext,
    private val suggestion: ClientSuggestion
) : Screen(carContext), KoinComponent {

    private val completionUseCase: ServiceCompletionUseCase by inject()
    private val clientRepository: ClientRepository by inject()
    private val prefs: PreferencesRepository by inject()
    private val trackingEventBus: TrackingEventBus by inject()
    private var completing = false

    override fun onGetTemplate(): Template {
        val client = suggestion.client
        val dist = suggestion.distanceToShopMiles?.let { "%.1f mi away" .format(it) } ?: ""
        val steps = suggestion.eligibleSteps.joinToString(", ") { it.label }.ifEmpty { "Service" }

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(client.address)
                    .addText(buildString {
                        if (dist.isNotEmpty()) append(dist)
                        if (steps.isNotEmpty()) { if (isNotEmpty()) append("  ·  "); append(steps) }
                    })
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Navigate")
                    .setOnClickListener { launchNavigation() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(if (completing) "Saving…" else "Complete")
                    .setOnClickListener { if (!completing) markComplete() }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(client.name)
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun launchNavigation() {
        val client = suggestion.client
        val uri = if (client.latitude != null && client.longitude != null) {
            // Use geo: URI with label for coordinates
            android.net.Uri.parse(
                "geo:${client.latitude},${client.longitude}?q=${client.latitude},${client.longitude}(${android.net.Uri.encode(client.name)})"
            )
        } else {
            // Use geo: URI with address search
            android.net.Uri.parse("geo:0,0?q=${android.net.Uri.encode(client.address)}")
        }
        // POI apps can't use startCarApp for navigation - launch via phone context
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            carContext.applicationContext.startActivity(intent)
            // Maps should open on phone and show in AA - stay on this screen
        } catch (e: Exception) {
            android.util.Log.e("ClientDetailScreen", "Navigation launch failed", e)
        }
    }

    private fun markComplete() {
        completing = true
        invalidate()
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val location = trackingEventBus.latestLocation.value
            val clients = clientRepository.loadAllClients()
            val selectedSteps = prefs.selectedSteps
                .split(",")
                .mapNotNull { runCatching { ServiceType.valueOf(it.trim()) }.getOrNull() }
                .toSet()
                .ifEmpty { setOf(ServiceType.ROUND_1) }

            val result = completionUseCase.confirmSelectedClientService(
                ServiceCompletionUseCase.ConfirmSelectedRequest(
                    clients = clients,
                    selectedClient = suggestion.client,
                    arrivalStartedAtMillis = now,
                    arrivalLat = location?.latitude,
                    arrivalLng = location?.longitude,
                    selectedSuggestionEligibleSteps = suggestion.eligibleSteps,
                    selectedServiceTypes = selectedSteps,
                    currentLocation = location?.let {
                        ServiceCompletionUseCase.GeoPoint(it.latitude, it.longitude)
                    },
                    visitNotes = "",
                )
            )
            completing = false
            when (result) {
                is ServiceCompletionUseCase.ConfirmSelectedResult.Success ->
                    screenManager.push(ServiceCompleteScreen(carContext, suggestion.client.name))
                is ServiceCompletionUseCase.ConfirmSelectedResult.Error ->
                    screenManager.pop()  // fall back gracefully
            }
        }
    }
}
