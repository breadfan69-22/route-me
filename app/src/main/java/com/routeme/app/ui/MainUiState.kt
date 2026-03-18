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
    val selectedServiceTypes: Set<ServiceType> = setOf(ServiceType.ROUND_1),
    /** Steps where every subscribed client has already been serviced. */
    val completedSteps: Set<ServiceType> = emptySet(),
    val minDays: Int = 21,
    val cuOverrideEnabled: Boolean = false,
    val routeDirection: RouteDirection = RouteDirection.OUTWARD,
    /** Ordered queue of destinations for today. */
    val destinationQueue: List<SavedDestination> = emptyList(),
    /** Index into [destinationQueue] for the current active destination. */
    val activeDestinationIndex: Int = 0,
    /** Persistent list of saved destination presets. */
    val savedDestinations: List<SavedDestination> = emptyList()
) {
    /** The current active destination, or null if the queue is empty/exhausted. */
    val activeDestination: SavedDestination?
        get() = destinationQueue.getOrNull(activeDestinationIndex)
}

sealed interface MainEvent {
    data class ShowSnackbar(val message: String) : MainEvent
    data class OpenMapsRoute(val uri: String) : MainEvent
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
}
