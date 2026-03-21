package com.routeme.app.ui

import com.routeme.app.Client
import com.routeme.app.ClientSuggestion
import com.routeme.app.RouteDirection
import com.routeme.app.SavedDestination
import com.routeme.app.ServiceType

data class MainUiState(
    val clients: List<Client> = emptyList(),
    val summaryText: String = "",
    val statusText: String = "",
    val isLoading: Boolean = false,
    val isSuggestionsLoading: Boolean = false,
    val isTracking: Boolean = false,
    val sheetsReadUrl: String = "",
    val sheetsWriteUrl: String = "",
    val suggestions: List<ClientSuggestion> = emptyList(),
    val suggestionOffset: Int = 0,
    val selectedClient: Client? = null,
    val selectedClientDetails: String = "",
    val arrivalStartedAtMillis: Long? = null,
    val arrivalLat: Double? = null,
    val arrivalLng: Double? = null,
    val arrivalWeatherTempF: Int? = null,
    val arrivalWeatherWindMph: Int? = null,
    val arrivalWeatherDesc: String? = null,
    val selectedServiceTypes: Set<ServiceType> = setOf(ServiceType.ROUND_1),
    /** Steps where every subscribed client has already been serviced. */
    val completedSteps: Set<ServiceType> = emptySet(),
    val minDays: Int = 21,
    val cuOverrideEnabled: Boolean = false,
    val errandsModeEnabled: Boolean = false,
    val routeDirection: RouteDirection = RouteDirection.OUTWARD,
    /** Ordered queue of destinations for today. */
    val destinationQueue: List<SavedDestination> = emptyList(),
    /** Index into [destinationQueue] for the current active destination. */
    val activeDestinationIndex: Int = 0,
    /** Persistent list of saved destination presets. */
    val savedDestinations: List<SavedDestination> = emptyList(),

    // Dashboard hero fields
    /** Weather for live dashboard display (sourced from last stop or NWS fallback). */
    val currentWeatherTempF: Int? = null,
    val currentWeatherIconDesc: String? = null,
    val currentWeatherWindMph: Int? = null,
    val currentWeatherWindGust: Int? = null,
    val currentWeatherWindDirection: String? = null,
    /** Hourly forecast data (next hour). */
    val forecastTempF: Int? = null,
    val forecastIconDesc: String? = null,
    val forecastWindMph: Int? = null,
    val forecastWindDirection: String? = null,
    val forecastTimeLabel: String? = null,
    /** True = show current weather, false = show hourly forecast. */
    val showCurrentWeather: Boolean = true,
    /** True if current time is between sunrise and sunset (for day/night weather icons). */
    val isDaytime: Boolean = true,
    /** Total eligible client count (full ranked list size before pagination). */
    val eligibleClientCount: Int = 0,
    /** Name of the client we are currently stopped at, null when driving/idle. */
    val currentStopClientName: String? = null
) {
    /** The current active destination, or null if the queue is empty/exhausted. */
    val activeDestination: SavedDestination?
        get() = destinationQueue.getOrNull(activeDestinationIndex)
}

sealed interface MainEvent {
    data class ShowSnackbar(val message: String) : MainEvent
    data class OpenMapsRoute(val uri: String) : MainEvent
    data object RefreshTrackingClients : MainEvent
    data object ServiceConfirmed : MainEvent
    data class ShowDailySummary(val summary: String) : MainEvent
    /** Route history dialog with prev/next day navigation. */
    data class ShowRouteHistory(
        val summary: String,
        val dateLabel: String,
        val dateMillis: Long,
        val hasPrevDay: Boolean,
        val hasNextDay: Boolean,
        val gapDaysToOlder: Int = 0,
        val gapDaysToNewer: Int = 0
    ) : MainEvent
    /** Week summary dialog. */
    data class ShowWeekSummary(val summary: String) : MainEvent
    /** Weather-aware weekly planner — launch full-screen Activity. */
    data class ShowWeeklyPlanner(val plan: com.routeme.app.model.WeekPlan) : MainEvent
    data class StaleArrivalPrompt(val clientName: String, val minutesElapsed: Long) : MainEvent
    /** Prompt user to batch-confirm 2+ clients that were in the same location cluster. */
    data class ClusterCompletePrompt(val members: List<com.routeme.app.ClusterMember>) : MainEvent
    /** Offer an undo snackbar after confirming a service. */
    data class UndoConfirmation(
        val clientName: String,
        val clientId: String,
        val recordCompletedAtMillis: Long
    ) : MainEvent
    /** Offer an undo snackbar after confirming a cluster of services. */
    data class UndoClusterConfirmation(
        val clientNames: List<String>,
        val clientIds: List<String>,
        val recordCompletedAtMillis: Long
    ) : MainEvent
    /** Prompt the user to edit persistent notes for a client. */
    data class EditClientNotes(val clientId: String, val clientName: String, val currentNotes: String) : MainEvent
    /** Emitted after a successful sheet sync so the UI can auto-refresh suggestions. */
    data object SyncComplete : MainEvent
    /** Nudge user to enter property data for a client with incomplete stats. */
    data class PropertyNudge(val clientId: String, val clientName: String) : MainEvent
}
