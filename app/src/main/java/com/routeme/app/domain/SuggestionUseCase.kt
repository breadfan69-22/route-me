package com.routeme.app.domain

import android.location.Location
import com.routeme.app.Client
import com.routeme.app.ClientSuggestion
import com.routeme.app.RouteDirection
import com.routeme.app.SavedDestination
import com.routeme.app.ServiceType

class SuggestionUseCase(
    private val routingEngine: RoutingEngine,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val DAY_MILLIS = 86_400_000L
        const val DEFAULT_PAGE_SIZE = 5
    }

    data class SuggestNextResult(
        val suggestions: List<ClientSuggestion>,
        val selectedClient: Client?,
        val selectedClientDetails: String,
        val suggestionOffset: Int,
        val statusMessage: String,
        val dateRolloverDetected: Boolean,
        val totalEligibleCount: Int = 0
    )

    data class SkipSelectedResult(
        val suggestions: List<ClientSuggestion>,
        val selectedClient: Client?,
        val selectedClientDetails: String,
        val suggestionOffset: Int,
        val statusMessage: String
    )

    private var lastSuggestionLocation: Location? = null
    private val skippedTodayIds = mutableSetOf<String>()
    private var skipDateEpochDay: Long = currentEpochDay()

    fun suggestNextClients(
        clients: List<Client>,
        selectedServiceTypes: Set<ServiceType>,
        minDays: Int,
        cuOverrideEnabled: Boolean,
        routeDirection: RouteDirection,
        activeDestination: SavedDestination?,
        currentLocation: Location?
    ): SuggestNextResult {
        val location = currentLocation ?: lastSuggestionLocation
        lastSuggestionLocation = location

        val todayEpochDay = currentEpochDay()
        val dateRolloverDetected = todayEpochDay != skipDateEpochDay
        if (dateRolloverDetected) {
            skippedTodayIds.clear()
            skipDateEpochDay = todayEpochDay
        }

        val ranked = routingEngine.rankClients(
            clients = clients,
            serviceTypes = selectedServiceTypes,
            minDays = minDays,
            lastLocation = location,
            cuOverrideEnabled = cuOverrideEnabled,
            routeDirection = routeDirection,
            skippedClientIds = skippedTodayIds,
            destination = activeDestination
        )

        if (ranked.isEmpty()) {
            val stepsLabel = selectedServiceTypes.joinToString("+") { it.label }
            return SuggestNextResult(
                suggestions = emptyList(),
                selectedClient = null,
                selectedClientDetails = "",
                suggestionOffset = 0,
                statusMessage = "No eligible clients for $stepsLabel (min $minDays days).",
                dateRolloverDetected = dateRolloverDetected,
                totalEligibleCount = 0
            )
        }

        val selectedClient = ranked.first().client
        return SuggestNextResult(
            suggestions = ranked,
            selectedClient = selectedClient,
            selectedClientDetails = routingEngine.buildClientDetails(selectedClient),
            suggestionOffset = 0,
            statusMessage = "Selected ${selectedClient.name}",
            dateRolloverDetected = dateRolloverDetected,
            totalEligibleCount = ranked.size
        )
    }

    fun nextSuggestionOffset(currentOffset: Int, totalSuggestions: Int): Int {
        if (totalSuggestions <= 0) return 0
        val nextOffset = currentOffset + pageSize
        return if (nextOffset >= totalSuggestions) 0 else nextOffset
    }

    fun previousSuggestionOffset(currentOffset: Int): Int {
        return (currentOffset - pageSize).coerceAtLeast(0)
    }

    fun currentPageSuggestions(suggestions: List<ClientSuggestion>, suggestionOffset: Int): List<ClientSuggestion> {
        return suggestions.drop(suggestionOffset).take(pageSize)
    }

    fun canShowMoreSuggestions(suggestionOffset: Int, totalSuggestions: Int): Boolean {
        return suggestionOffset + pageSize < totalSuggestions
    }

    fun canShowPreviousSuggestions(suggestionOffset: Int): Boolean {
        return suggestionOffset > 0
    }

    fun remainingSuggestionCount(suggestionOffset: Int, totalSuggestions: Int): Int {
        return (totalSuggestions - suggestionOffset - pageSize).coerceAtLeast(0)
    }

    fun skipSelectedClientToday(
        selectedClient: Client?,
        suggestions: List<ClientSuggestion>
    ): SkipSelectedResult? {
        val client = selectedClient ?: return null

        skippedTodayIds.add(client.id)
        val remainingSuggestions = suggestions.filter { it.client.id != client.id }
        val nextSelection = remainingSuggestions.firstOrNull()?.client

        return SkipSelectedResult(
            suggestions = remainingSuggestions,
            selectedClient = nextSelection,
            selectedClientDetails = nextSelection?.let(routingEngine::buildClientDetails) ?: "",
            suggestionOffset = 0,
            statusMessage = "Skipped ${client.name} for today (${skippedTodayIds.size} skipped)"
        )
    }

    fun clearSkippedClients() {
        skippedTodayIds.clear()
    }

    fun skippedCount(): Int = skippedTodayIds.size

    fun lastSuggestionLocation(): Location? = lastSuggestionLocation

    fun updateLastSuggestionLocation(location: Location?) {
        lastSuggestionLocation = location
    }

    private fun currentEpochDay(): Long = nowProvider() / DAY_MILLIS
}
