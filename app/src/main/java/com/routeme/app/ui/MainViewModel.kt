package com.routeme.app.ui

import android.location.Location
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routeme.app.Client
import com.routeme.app.RouteDirection
import com.routeme.app.SavedDestination
import com.routeme.app.ServiceRecord
import com.routeme.app.ServiceType
import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.SheetsWriteBack
import com.routeme.app.DailyRecordRow
import com.routeme.app.NonClientStop
import com.routeme.app.suggestedStepsForDate
import com.routeme.app.TrackingEvent
import com.routeme.app.TrackingEventBus
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.domain.RoutingEngine
import com.routeme.app.util.DateUtils
import java.util.Calendar
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainViewModel(
    private val clientRepository: ClientRepository,
    private val preferencesRepository: PreferencesRepository,
    private val routingEngine: RoutingEngine,
    private val savedStateHandle: SavedStateHandle,
    private val retryQueue: com.routeme.app.data.WriteBackRetryQueue
) : ViewModel() {
    companion object {
        private const val KEY_SERVICE_TYPE = "service_type"
        private const val KEY_MIN_DAYS = "min_days"
        private const val KEY_CU_OVERRIDE = "cu_override"
        private const val KEY_ROUTE_DIRECTION = "route_direction"
        private const val KEY_SUGGESTION_OFFSET = "suggestion_offset"
        private const val KEY_SELECTED_CLIENT_ID = "selected_client_id"
        private const val KEY_ARRIVAL_STARTED_AT = "arrival_started_at"
        private const val KEY_ARRIVAL_LAT = "arrival_lat"
        private const val KEY_ARRIVAL_LNG = "arrival_lng"
        private const val KEY_IS_TRACKING = "is_tracking"
        private const val KEY_DEST_QUEUE = "dest_queue"
        private const val KEY_DEST_INDEX = "dest_index"
    }

    private val pageSize = 5
    private val routeExportTopN = 12
    private val maxGoogleWaypoints = 8
    private var lastSuggestionLocation: Location? = null
    private var pendingActionAfterStaleResolve: (() -> Unit)? = null

    /** Clients the user tapped "Skip Today" on — excluded from suggestions until midnight. */
    private val skippedTodayIds = mutableSetOf<String>()
    private var skipDateEpochDay: Long = System.currentTimeMillis() / 86_400_000L

    /**
     * Determine the initial step selection on startup:
     * 1. If savedStateHandle has an in-process selection (config change / process death), use it.
     * 2. If SharedPrefs has a selection saved *today*, restore it (same work day).
     * 3. Otherwise, auto-select based on the seasonal date window,
     *    falling back to ROUND_1 if no window matches.
     */
    private fun resolveInitialSteps(): Set<ServiceType> {
        // 1. SavedStateHandle survives config changes / process death
        val fromSavedState = savedStateHandle.get<String>(KEY_SERVICE_TYPE)
            ?.split(",")
            ?.mapNotNull { runCatching { ServiceType.valueOf(it.trim()) }.getOrNull() }
            ?.toSet()
            ?.ifEmpty { null }
        if (fromSavedState != null) return fromSavedState

        // 2. SharedPreferences survives full app close — use if saved today
        val todayEpochDay = System.currentTimeMillis() / 86_400_000L
        val savedDate = preferencesRepository.selectedStepsDate
        if (savedDate == todayEpochDay) {
            val fromPrefs = preferencesRepository.selectedSteps
                .split(",")
                .mapNotNull { runCatching { ServiceType.valueOf(it.trim()) }.getOrNull() }
                .toSet()
                .ifEmpty { null }
            if (fromPrefs != null) return fromPrefs
        }

        // 3. New day or first launch — auto-select from seasonal date windows
        return suggestedStepsForDate() ?: setOf(ServiceType.ROUND_1)
    }

    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedServiceTypes = resolveInitialSteps(),
            minDays = savedStateHandle.get<Int>(KEY_MIN_DAYS) ?: 21,
            cuOverrideEnabled = savedStateHandle.get<Boolean>(KEY_CU_OVERRIDE) ?: false,
            routeDirection = savedStateHandle.get<String>(KEY_ROUTE_DIRECTION)
                ?.let { runCatching { RouteDirection.valueOf(it) }.getOrNull() }
                ?: RouteDirection.OUTWARD,
            suggestionOffset = savedStateHandle.get<Int>(KEY_SUGGESTION_OFFSET) ?: 0,
            arrivalStartedAtMillis = savedStateHandle.get<Long?>(KEY_ARRIVAL_STARTED_AT),
            arrivalLat = savedStateHandle.get<Double?>(KEY_ARRIVAL_LAT),
            arrivalLng = savedStateHandle.get<Double?>(KEY_ARRIVAL_LNG),
            isTracking = savedStateHandle.get<Boolean>(KEY_IS_TRACKING) ?: false,
            destinationQueue = savedStateHandle.get<String>(KEY_DEST_QUEUE)
                ?.takeIf { it.isNotBlank() }
                ?.split("|")
                ?.mapNotNull { entry ->
                    val parts = entry.split(",", limit = 5)
                    if (parts.size == 5) SavedDestination(
                        parts[0], parts[1], parts[2],
                        parts[3].toDoubleOrNull() ?: return@mapNotNull null,
                        parts[4].toDoubleOrNull() ?: return@mapNotNull null
                    ) else null
                } ?: emptyList(),
            activeDestinationIndex = savedStateHandle.get<Int>(KEY_DEST_INDEX) ?: 0
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>()
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    init {
        loadSyncSettings()
        loadClients()
        loadSavedDestinations()
    }

    fun loadClients() {
        viewModelScope.launch {
            setLoading(true)
            setStatus("Loading clients…", emitSnackbar = false)
            try {
                val loaded = clientRepository.loadAllClients()
                val restoredSelectedId = savedStateHandle.get<String>(KEY_SELECTED_CLIENT_ID)
                val restoredSelectedClient = loaded.firstOrNull { it.id == restoredSelectedId }
                _uiState.update {
                    it.copy(
                        clients = loaded,
                        summaryText = buildSummaryText(loaded),
                        completedSteps = computeCompletedSteps(loaded),
                        selectedClient = restoredSelectedClient,
                        selectedClientDetails = restoredSelectedClient?.let(routingEngine::buildClientDetails)
                            ?: it.selectedClientDetails
                    )
                }
                persistCriticalState(_uiState.value)
                if (loaded.isEmpty()) {
                    setStatus("No clients found. Import or sync to begin.")
                } else {
                    setStatus("Loaded ${loaded.size} client(s).")
                }
            } catch (e: Exception) {
                setStatus("Load failed: ${e.message ?: "Unknown error"}")
            } finally {
                setLoading(false)
            }
        }
    }

    fun importClients(uri: Uri) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val result = clientRepository.importFromUri(uri)
                setStatus(result.message)
                if (result.clients.isNotEmpty()) {
                    val updated = _uiState.value.clients.toMutableList().apply { addAll(result.clients) }
                    _uiState.update {
                        it.copy(
                            clients = updated,
                            summaryText = buildSummaryText(updated),
                            completedSteps = computeCompletedSteps(updated)
                        )
                    }
                    persistCriticalState(_uiState.value)
                }
            } catch (e: Exception) {
                setStatus("Import failed: ${e.message ?: "Unknown error"}")
            } finally {
                setLoading(false)
            }
        }
    }

    fun syncFromSheets(url: String) {
        viewModelScope.launch {
            setLoading(true)
            setStatus("Syncing from Google Sheets…", emitSnackbar = false)
            try {
                val result = clientRepository.syncFromSheets(url)
                setStatus(result.message)
                if (result.clients.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            clients = result.clients,
                            summaryText = buildSummaryText(result.clients),
                            completedSteps = computeCompletedSteps(result.clients),
                            suggestions = emptyList(),
                            suggestionOffset = 0,
                            selectedClient = null,
                            selectedClientDetails = "",
                            arrivalStartedAtMillis = null,
                            arrivalLat = null,
                            arrivalLng = null
                        )
                    }
                    persistCriticalState(_uiState.value)
                    // Auto-geocode any clients missing coordinates after sync
                    geocodeMissingClientCoordinates()
                }
            } catch (e: Exception) {
                setStatus("Sync failed: ${e.message ?: "Unknown error"}")
            } finally {
                setLoading(false)
            }
        }
    }

    fun geocodeMissingClientCoordinates() {
        viewModelScope.launch {
            val clients = _uiState.value.clients
            val withoutCoords = clients.filter { it.latitude == null || it.longitude == null }
            if (withoutCoords.isEmpty()) {
                setStatus("All clients already have coordinates.")
                return@launch
            }

            setLoading(true)
            setStatus("Geocoding ${withoutCoords.size} client(s)…", emitSnackbar = false)
            try {
                val result = clientRepository.geocodeClients(clients)
                setStatus(result.message)
                _uiState.update {
                    it.copy(
                        clients = clients,
                        summaryText = buildSummaryText(clients),
                        completedSteps = computeCompletedSteps(clients)
                    )
                }
                persistCriticalState(_uiState.value)
            } catch (e: Exception) {
                setStatus("Geocoding failed: ${e.message ?: "Unknown error"}")
            } finally {
                setLoading(false)
            }
        }
    }

    fun updateSyncSettings(readUrl: String, writeUrl: String) {
        preferencesRepository.sheetsReadUrl = readUrl
        preferencesRepository.sheetsWriteUrl = writeUrl
        SheetsWriteBack.webAppUrl = writeUrl
        _uiState.update {
            it.copy(
                sheetsReadUrl = readUrl,
                sheetsWriteUrl = writeUrl
            )
        }
    }

    fun setTrackingActive(active: Boolean) {
        _uiState.update { it.copy(isTracking = active) }
        savedStateHandle[KEY_IS_TRACKING] = active
    }

    fun postStatus(message: String, emitSnackbar: Boolean = true) {
        viewModelScope.launch {
            setStatus(message, emitSnackbar)
        }
    }

    fun setServiceTypes(serviceTypes: Set<ServiceType>) {
        _uiState.update { it.copy(selectedServiceTypes = serviceTypes) }
        savedStateHandle[KEY_SERVICE_TYPE] = serviceTypes.joinToString(",") { it.name }
        // Persist to SharedPreferences so selection survives full app close
        preferencesRepository.selectedSteps = serviceTypes.joinToString(",") { it.name }
        preferencesRepository.selectedStepsDate = System.currentTimeMillis() / 86_400_000L
    }

    fun setMinDays(minDays: Int) {
        _uiState.update { it.copy(minDays = minDays) }
        savedStateHandle[KEY_MIN_DAYS] = minDays
    }

    fun toggleCuOverride() {
        _uiState.update { it.copy(cuOverrideEnabled = !it.cuOverrideEnabled) }
        savedStateHandle[KEY_CU_OVERRIDE] = _uiState.value.cuOverrideEnabled
    }

    fun toggleRouteDirection() {
        _uiState.update {
            it.copy(
                routeDirection = when (it.routeDirection) {
                    RouteDirection.OUTWARD -> RouteDirection.HOMEWARD
                    RouteDirection.HOMEWARD -> RouteDirection.OUTWARD
                }
            )
        }
        savedStateHandle[KEY_ROUTE_DIRECTION] = _uiState.value.routeDirection.name
    }

    // ─── Non-client stop logging settings ──────────────────────

    /** Whether non-client stop logging is currently enabled. */
    fun isNonClientLoggingEnabled(): Boolean = preferencesRepository.nonClientLoggingEnabled

    fun toggleNonClientLogging() {
        val newValue = !preferencesRepository.nonClientLoggingEnabled
        preferencesRepository.nonClientLoggingEnabled = newValue
        viewModelScope.launch {
            setStatus(if (newValue) "Non-client stop logging ON" else "Non-client stop logging OFF")
        }
    }

    /** Current threshold in minutes. */
    fun getNonClientStopThreshold(): Int = preferencesRepository.nonClientStopThresholdMinutes

    fun setNonClientStopThreshold(minutes: Int) {
        preferencesRepository.nonClientStopThresholdMinutes = minutes.coerceIn(1, 30)
        viewModelScope.launch {
            setStatus("Non-client stop threshold: ${minutes}min")
        }
    }

    // ─── Destination queue management ──────────────────────────

    private fun loadSavedDestinations() {
        _uiState.update { it.copy(savedDestinations = preferencesRepository.savedDestinations) }
    }

    fun addSavedDestination(dest: SavedDestination) {
        val updated = preferencesRepository.savedDestinations + dest
        preferencesRepository.savedDestinations = updated
        _uiState.update { it.copy(savedDestinations = updated) }
    }

    fun removeSavedDestination(id: String) {
        val updated = preferencesRepository.savedDestinations.filter { it.id != id }
        preferencesRepository.savedDestinations = updated
        _uiState.update { it.copy(savedDestinations = updated) }
    }

    fun addToDestinationQueue(dest: SavedDestination) {
        _uiState.update { it.copy(destinationQueue = it.destinationQueue + dest) }
        syncActiveDestinationToPrefs()
        viewModelScope.launch { setStatus("Added ${dest.name} to destinations") }
    }

    fun removeFromDestinationQueue(index: Int) {
        _uiState.update { state ->
            val queue = state.destinationQueue.toMutableList().apply { removeAt(index) }
            val newIndex = when {
                queue.isEmpty() -> 0
                state.activeDestinationIndex >= queue.size -> queue.size - 1
                index < state.activeDestinationIndex -> state.activeDestinationIndex - 1
                else -> state.activeDestinationIndex
            }
            state.copy(destinationQueue = queue, activeDestinationIndex = newIndex)
        }
        syncActiveDestinationToPrefs()
    }

    fun moveDestinationInQueue(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            val queue = state.destinationQueue.toMutableList()
            val item = queue.removeAt(fromIndex)
            queue.add(toIndex, item)
            // Track where the active index moved
            val oldActive = state.activeDestinationIndex
            val newActive = when {
                fromIndex == oldActive -> toIndex
                fromIndex < oldActive && toIndex >= oldActive -> oldActive - 1
                fromIndex > oldActive && toIndex <= oldActive -> oldActive + 1
                else -> oldActive
            }
            state.copy(destinationQueue = queue, activeDestinationIndex = newActive)
        }
        syncActiveDestinationToPrefs()
    }

    fun clearDestinationQueue() {
        _uiState.update { it.copy(destinationQueue = emptyList(), activeDestinationIndex = 0) }
        preferencesRepository.activeDestination = null
        viewModelScope.launch { setStatus("Destinations cleared") }
    }

    fun skipDestination() {
        advanceDestinationQueue()
    }

    fun optimizeDestinationQueue(currentLocation: Location?) {
        val state = _uiState.value
        if (state.destinationQueue.size <= 1) return
        val startLat = currentLocation?.latitude ?: SHOP_LAT
        val startLng = currentLocation?.longitude ?: SHOP_LNG
        val optimized = routingEngine.optimizeDestinationOrder(state.destinationQueue, startLat, startLng)
        _uiState.update { it.copy(destinationQueue = optimized, activeDestinationIndex = 0) }
        syncActiveDestinationToPrefs()
        viewModelScope.launch { setStatus("Optimized ${optimized.size} destinations") }
    }

    /** Called when tracking detects arrival at the active destination. */
    fun onDestinationReached(destinationName: String) {
        viewModelScope.launch {
            val state = _uiState.value
            val remaining = state.destinationQueue.size - state.activeDestinationIndex - 1
            if (remaining > 0) {
                val nextDest = state.destinationQueue.getOrNull(state.activeDestinationIndex + 1)
                _events.emit(MainEvent.ShowSnackbar(
                    "Arrived at $destinationName — next: ${nextDest?.name} ($remaining remaining)"
                ))
            } else {
                _events.emit(MainEvent.ShowSnackbar(
                    "Arrived at $destinationName — all destinations reached"
                ))
            }
            advanceDestinationQueue()
        }
    }

    private fun advanceDestinationQueue() {
        _uiState.update { state ->
            val nextIndex = state.activeDestinationIndex + 1
            if (nextIndex >= state.destinationQueue.size) {
                state.copy(destinationQueue = emptyList(), activeDestinationIndex = 0)
            } else {
                state.copy(activeDestinationIndex = nextIndex)
            }
        }
        syncActiveDestinationToPrefs()
    }

    /** Push current active destination to SharedPrefs so the tracking service can read it. */
    private fun syncActiveDestinationToPrefs() {
        preferencesRepository.activeDestination = _uiState.value.activeDestination
    }

    fun suggestNextClients(currentLocation: Location?) {
        if (checkAndPromptStaleArrival { suggestNextClients(currentLocation) }) return
        viewModelScope.launch {
            val state = _uiState.value
            val location = currentLocation ?: lastSuggestionLocation
            lastSuggestionLocation = location

            // Auto-clear skip set on date rollover
            val todayEpoch = System.currentTimeMillis() / 86_400_000L
            if (todayEpoch != skipDateEpochDay) {
                skippedTodayIds.clear()
                skipDateEpochDay = todayEpoch
                clearDestinationQueue()
            }

            val ranked = routingEngine.rankClients(
                clients = state.clients,
                serviceTypes = state.selectedServiceTypes,
                minDays = state.minDays,
                lastLocation = location,
                cuOverrideEnabled = state.cuOverrideEnabled,
                routeDirection = state.routeDirection,
                skippedClientIds = skippedTodayIds,
                destination = state.activeDestination
            )

            if (ranked.isEmpty()) {
                _uiState.update {
                    it.copy(
                        suggestions = emptyList(),
                        suggestionOffset = 0,
                        selectedClient = null,
                        selectedClientDetails = ""
                    )
                }
                val stepsLabel = state.selectedServiceTypes.joinToString("+") { it.label }
                setStatus("No eligible clients for $stepsLabel (min ${state.minDays} days).")
                return@launch
            }

            val first = ranked.first().client
            _uiState.update {
                it.copy(
                    suggestions = ranked,
                    suggestionOffset = 0,
                    selectedClient = first,
                    selectedClientDetails = routingEngine.buildClientDetails(first)
                )
            }
            persistCriticalState(_uiState.value)
            setStatus("Selected ${first.name}")
            fetchDrivingTimesForCurrentPage()
        }
    }

    fun nextSuggestionPage() {
        val state = _uiState.value
        if (state.suggestions.isEmpty()) return
        val nextOffset = state.suggestionOffset + pageSize
        val wrapped = if (nextOffset >= state.suggestions.size) 0 else nextOffset
        _uiState.update { it.copy(suggestionOffset = wrapped) }
        savedStateHandle[KEY_SUGGESTION_OFFSET] = wrapped
        viewModelScope.launch { fetchDrivingTimesForCurrentPage() }
    }

    fun previousSuggestionPage() {
        val state = _uiState.value
        if (state.suggestions.isEmpty()) return
        val prevOffset = (state.suggestionOffset - pageSize).coerceAtLeast(0)
        _uiState.update { it.copy(suggestionOffset = prevOffset) }
        savedStateHandle[KEY_SUGGESTION_OFFSET] = prevOffset
        viewModelScope.launch { fetchDrivingTimesForCurrentPage() }
    }

    fun selectSuggestion(clientId: String) {
        if (checkAndPromptStaleArrival { selectSuggestion(clientId) }) return
        val suggestion = _uiState.value.suggestions.firstOrNull { it.client.id == clientId } ?: return
        _uiState.update {
            it.copy(
                selectedClient = suggestion.client,
                selectedClientDetails = routingEngine.buildClientDetails(suggestion.client),
                arrivalStartedAtMillis = null,
                arrivalLat = null,
                arrivalLng = null
            )
        }
        persistCriticalState(_uiState.value)
        viewModelScope.launch { setStatus("Selected ${suggestion.client.name}") }
    }

    /**
     * Skip the currently selected client for the rest of today.
     * They'll reappear when suggestions are run after midnight (or on manual clear).
     */
    fun skipSelectedClientToday() {
        val state = _uiState.value
        val client = state.selectedClient ?: run {
            viewModelScope.launch { setStatus("Pick a client first") }
            return
        }
        skippedTodayIds.add(client.id)
        val remaining = state.suggestions.filter { it.client.id != client.id }
        val next = remaining.firstOrNull()
        _uiState.update {
            it.copy(
                suggestions = remaining,
                suggestionOffset = 0,
                selectedClient = next?.client,
                selectedClientDetails = next?.client?.let(routingEngine::buildClientDetails) ?: "",
                arrivalStartedAtMillis = null,
                arrivalLat = null,
                arrivalLng = null
            )
        }
        persistCriticalState(_uiState.value)
        viewModelScope.launch {
            setStatus("Skipped ${client.name} for today (${skippedTodayIds.size} skipped)")
        }
    }

    /** Returns number of clients currently skipped today. */
    fun skippedCount(): Int = skippedTodayIds.size

    /** Clears all skip-today flags. */
    fun clearSkippedClients() {
        skippedTodayIds.clear()
        viewModelScope.launch { setStatus("Cleared all skips") }
    }

    fun exportTopRouteToMaps() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.suggestions.isEmpty()) {
                setStatus("Run suggestions first")
                return@launch
            }

            val routeExport = buildTopRouteExportFromSuggestions(state)

            if (routeExport == null) {
                setStatus("No mappable clients in current suggestions")
                return@launch
            }

            _events.emit(MainEvent.OpenMapsRoute(routeExport.uri))

            val clipped = routeExport.requestedStops - routeExport.includedStops
            if (clipped > 0) {
                setStatus("Opened Maps route with ${routeExport.includedStops} of ${routeExport.requestedStops} stops")
            } else {
                setStatus("Opened Maps route with ${routeExport.includedStops} stops")
            }
        }
    }

    internal fun buildTopRouteExportForTests(): Pair<String, Int>? {
        val routeExport = buildTopRouteExportFromSuggestions(_uiState.value) ?: return null
        return routeExport.uri to routeExport.includedStops
    }

    fun startArrivalForSelected(currentLocation: Location?) {
        val state = _uiState.value
        val client = state.selectedClient
        if (client == null) {
            viewModelScope.launch { setStatus("Pick a client first") }
            return
        }
        if (currentLocation == null) {
            viewModelScope.launch { setStatus("Unable to get current location") }
            return
        }
        markArrivalForClient(client, currentLocation, System.currentTimeMillis())
    }

    fun cancelArrival() {
        val state = _uiState.value
        val started = state.arrivalStartedAtMillis
        if (started == null) return
        val name = state.selectedClient?.name ?: "client"
        _uiState.update { it.copy(arrivalStartedAtMillis = null, arrivalLat = null, arrivalLng = null) }
        savedStateHandle[KEY_ARRIVAL_STARTED_AT] = null
        viewModelScope.launch { setStatus("Cancelled arrival for $name") }
    }

    /**
     * Returns true (and emits a prompt) if there's a pending arrival the user hasn't confirmed.
     * The [deferredAction] will run automatically after the user resolves the prompt.
     */
    private fun checkAndPromptStaleArrival(deferredAction: () -> Unit): Boolean {
        val state = _uiState.value
        val started = state.arrivalStartedAtMillis ?: return false
        val clientName = state.selectedClient?.name ?: return false
        val minutes = ((System.currentTimeMillis() - started) / 60_000L).coerceAtLeast(1)
        pendingActionAfterStaleResolve = deferredAction
        viewModelScope.launch {
            _events.emit(MainEvent.StaleArrivalPrompt(clientName, minutes))
        }
        return true
    }

    /**
     * Called when the user resolves the stale-arrival dialog.
     * [markComplete] true  → save the record then continue.
     * [markComplete] false → discard the arrival then continue.
     * If the user cancels the dialog, don't call this — the pending action is just dropped.
     */
    fun resolveStaleArrival(markComplete: Boolean, currentLocation: Location? = null, visitNotes: String = "") {
        val action = pendingActionAfterStaleResolve
        pendingActionAfterStaleResolve = null
        if (markComplete) {
            // Save the service record using the current confirm flow
            confirmSelectedClientService(currentLocation, visitNotes)
        } else {
            // Discard — just clear arrival state
            val name = _uiState.value.selectedClient?.name ?: "client"
            _uiState.update { it.copy(arrivalStartedAtMillis = null, arrivalLat = null, arrivalLng = null) }
            savedStateHandle[KEY_ARRIVAL_STARTED_AT] = null
            viewModelScope.launch { setStatus("Discarded arrival for $name") }
        }
        // Run whatever the user was originally trying to do
        action?.invoke()
    }

    fun dropPendingStaleAction() {
        pendingActionAfterStaleResolve = null
    }

    fun markArrivalForClient(client: Client, location: Location, startedAtMillis: Long = System.currentTimeMillis()) {
        lastSuggestionLocation = location
        _uiState.update {
            it.copy(
                selectedClient = client,
                selectedClientDetails = routingEngine.buildClientDetails(client),
                arrivalStartedAtMillis = startedAtMillis,
                arrivalLat = location.latitude,
                arrivalLng = location.longitude
            )
        }
        persistCriticalState(_uiState.value)
        viewModelScope.launch {
            setStatus("Arrival started for ${client.name} at ${DateUtils.formatTimestamp(startedAtMillis)}")
        }
    }

    fun currentPageSuggestions(): List<com.routeme.app.ClientSuggestion> {
        val state = _uiState.value
        return state.suggestions.drop(state.suggestionOffset).take(pageSize)
    }

    fun canShowMoreSuggestions(): Boolean {
        val state = _uiState.value
        return state.suggestionOffset + pageSize < state.suggestions.size
    }

    fun canShowPreviousSuggestions(): Boolean {
        return _uiState.value.suggestionOffset > 0
    }

    fun remainingSuggestionCount(): Int {
        val state = _uiState.value
        return (state.suggestions.size - state.suggestionOffset - pageSize).coerceAtLeast(0)
    }

    fun confirmSelectedClientService(currentLocation: Location?, visitNotes: String = "") {
        viewModelScope.launch {
            val state = _uiState.value
            val client = state.selectedClient
            if (client == null) {
                setStatus("Pick a client first")
                return@launch
            }
            val arrivalStartedAtMillis = state.arrivalStartedAtMillis
            if (arrivalStartedAtMillis == null) {
                setStatus("Tap Arrived first")
                return@launch
            }

            val finishedAt = System.currentTimeMillis()
            val durationMinutes = (((finishedAt - arrivalStartedAtMillis) / 60000.0).toLong()).coerceAtLeast(1)

            // Determine which steps to confirm: use the suggestion's eligible steps if
            // available, otherwise fall back to the first selected type.
            val selectedSuggestion = state.suggestions.find { it.client.id == client.id }
            val stepsToConfirm = if (selectedSuggestion != null && selectedSuggestion.eligibleSteps.isNotEmpty()) {
                selectedSuggestion.eligibleSteps
            } else {
                // Fallback: first selected service type only
                setOf(state.selectedServiceTypes.first())
            }

            for (serviceType in stepsToConfirm) {
                val record = ServiceRecord(
                    serviceType = serviceType,
                    arrivedAtMillis = arrivalStartedAtMillis,
                    completedAtMillis = finishedAt,
                    durationMinutes = durationMinutes,
                    lat = state.arrivalLat ?: currentLocation?.latitude,
                    lng = state.arrivalLng ?: currentLocation?.longitude,
                    notes = visitNotes.trim()
                )

                client.records.add(record)
                try {
                    clientRepository.saveServiceRecord(client.id, record)
                } catch (e: Exception) {
                    setStatus("Save record failed: ${e.message ?: "Unknown error"}")
                    return@launch
                }
            }

            val updatedClients = state.clients.map { if (it.id == client.id) client else it }
            _uiState.update {
                it.copy(
                    clients = updatedClients,
                    summaryText = buildSummaryText(updatedClients),
                    completedSteps = computeCompletedSteps(updatedClients),
                    selectedClient = client,
                    selectedClientDetails = routingEngine.buildClientDetails(client),
                    arrivalStartedAtMillis = null,
                    arrivalLat = null,
                    arrivalLng = null
                )
            }
            persistCriticalState(_uiState.value)

            val stepsLabel = stepsToConfirm.joinToString("+") { it.label }
            val statusMsg = "Confirmed $stepsLabel for ${client.name} at ${DateUtils.formatTimestamp(finishedAt)} (${durationMinutes}m)"
            setStatus(statusMsg)
            _events.emit(MainEvent.ServiceConfirmed)
            _events.emit(MainEvent.UndoConfirmation(client.name, client.id, finishedAt))

            if (SheetsWriteBack.webAppUrl.isNotBlank()) {
                for (serviceType in stepsToConfirm) {
                    try {
                        val result = clientRepository.writeBackServiceCompletion(client.name, serviceType, finishedAt)
                        if (result.success) {
                            drainRetryQueueQuietly()
                        } else {
                            retryQueue.enqueue(client.name, serviceTypeToColumn(serviceType), "\u221A${formatCheckValue(finishedAt)}")
                        }
                    } catch (e: Exception) {
                        retryQueue.enqueue(client.name, serviceTypeToColumn(serviceType), "\u221A${formatCheckValue(finishedAt)}")
                    }
                }
                if (stepsToConfirm.size > 1) {
                    _events.emit(MainEvent.ShowSnackbar("Sheet updated for ${stepsToConfirm.size} steps"))
                } else {
                    setStatus("Sheet updated. $statusMsg")
                }
            }
        }
    }

    /**
     * Batch-confirm a cluster of 2+ clients that were all nearby.
     * [selectedMembers] is the subset the user checked in the dialog.
     */
    fun confirmClusterService(selectedMembers: List<com.routeme.app.ClusterMember>) {
        viewModelScope.launch {
            val state = _uiState.value
            val serviceTypes = state.selectedServiceTypes
            val finishedAt = System.currentTimeMillis()
            val confirmedNames = mutableListOf<String>()
            var updatedClients = state.clients

            for (member in selectedMembers) {
                val client = updatedClients.find { it.id == member.client.id } ?: continue
                val arrivedAt = member.arrivedAtMillis
                val durationMinutes = ((finishedAt - arrivedAt) / 60_000L).coerceAtLeast(1)

                // Use matching suggestion's eligible steps if available
                val suggestion = state.suggestions.find { it.client.id == client.id }
                val stepsForClient = if (suggestion != null && suggestion.eligibleSteps.isNotEmpty()) {
                    suggestion.eligibleSteps
                } else {
                    setOf(serviceTypes.first())
                }

                for (serviceType in stepsForClient) {
                    val record = ServiceRecord(
                        serviceType = serviceType,
                        arrivedAtMillis = arrivedAt,
                        completedAtMillis = finishedAt,
                        durationMinutes = durationMinutes,
                        lat = member.location.latitude,
                        lng = member.location.longitude,
                        notes = ""
                    )

                    client.records.add(record)
                    try {
                        clientRepository.saveServiceRecord(client.id, record)
                    } catch (e: Exception) {
                        setStatus("Save failed for ${client.name}: ${e.message ?: "Unknown"}")
                        continue
                    }

                    // Sheet write-back
                    if (SheetsWriteBack.webAppUrl.isNotBlank()) {
                        try {
                            val result = clientRepository.writeBackServiceCompletion(client.name, serviceType, finishedAt)
                            if (!result.success) {
                                retryQueue.enqueue(client.name, serviceTypeToColumn(serviceType), "\u221A${formatCheckValue(finishedAt)}")
                            }
                        } catch (_: Exception) {
                            retryQueue.enqueue(client.name, serviceTypeToColumn(serviceType), "\u221A${formatCheckValue(finishedAt)}")
                        }
                    }
                }

                confirmedNames.add(client.name)
                updatedClients = updatedClients.map { if (it.id == client.id) client else it }
            }

            _uiState.update {
                it.copy(
                    clients = updatedClients,
                    summaryText = buildSummaryText(updatedClients),
                    completedSteps = computeCompletedSteps(updatedClients),
                    arrivalStartedAtMillis = null,
                    arrivalLat = null,
                    arrivalLng = null
                )
            }
            persistCriticalState(_uiState.value)

            val confirmedIds = selectedMembers
                .filter { m -> confirmedNames.contains(m.client.name) }
                .map { it.client.id }

            val stepsLabel = serviceTypes.joinToString("+") { it.label }
            val msg = "Confirmed $stepsLabel for ${confirmedNames.joinToString(", ")} (${confirmedNames.size} stops)"
            setStatus(msg)
            _events.emit(MainEvent.ServiceConfirmed)
            if (confirmedIds.isNotEmpty()) {
                _events.emit(MainEvent.UndoClusterConfirmation(confirmedNames.toList(), confirmedIds, finishedAt))
            }
        }
    }

    /**
     * Undo the most recently confirmed service record.
     * Deletes it from the DB and removes it from the in-memory client records list.
     */
    fun undoLastConfirmation(clientId: String, completedAtMillis: Long) {
        viewModelScope.launch {
            try {
                clientRepository.deleteServiceRecord(clientId, completedAtMillis)

                // Remove from in-memory records
                val state = _uiState.value
                val client = state.clients.find { it.id == clientId }
                if (client != null) {
                    client.records.removeAll { it.completedAtMillis == completedAtMillis }
                    _uiState.update {
                        it.copy(
                            clients = state.clients,
                            summaryText = buildSummaryText(state.clients),
                            completedSteps = computeCompletedSteps(state.clients),
                            selectedClientDetails = if (state.selectedClient?.id == clientId)
                                routingEngine.buildClientDetails(client) else it.selectedClientDetails
                        )
                    }
                }
                setStatus("Undone — record removed")
            } catch (e: Exception) {
                setStatus("Undo failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    /**
     * Undo a cluster of service records that were batch-confirmed together.
     */
    fun undoClusterConfirmation(clientIds: List<String>, completedAtMillis: Long) {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                for (clientId in clientIds) {
                    clientRepository.deleteServiceRecord(clientId, completedAtMillis)
                    val client = state.clients.find { it.id == clientId }
                    client?.records?.removeAll { it.completedAtMillis == completedAtMillis }
                }
                _uiState.update {
                    it.copy(
                        clients = state.clients,
                        summaryText = buildSummaryText(state.clients),
                        completedSteps = computeCompletedSteps(state.clients)
                    )
                }
                setStatus("Undone — ${clientIds.size} records removed")
            } catch (e: Exception) {
                setStatus("Undo failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    /**
     * Opens the edit-notes dialog for the currently selected client.
     */
    fun editSelectedClientNotes() {
        val client = _uiState.value.selectedClient ?: run {
            viewModelScope.launch { setStatus("Pick a client first") }
            return
        }
        viewModelScope.launch {
            _events.emit(MainEvent.EditClientNotes(client.id, client.name, client.notes))
        }
    }

    /**
     * Saves updated persistent notes for a client (local DB + sheet write-back).
     */
    fun saveClientNotes(clientId: String, notes: String) {
        viewModelScope.launch {
            try {
                clientRepository.updateClientNotes(clientId, notes.trim())

                // Update in-memory model
                val state = _uiState.value
                val updatedClients = state.clients.map { c ->
                    if (c.id == clientId) c.copy(notes = notes.trim()) else c
                }
                // If we changed the data class, update the mutable list reference too
                val client = updatedClients.find { it.id == clientId }
                _uiState.update {
                    it.copy(
                        clients = updatedClients,
                        selectedClient = if (it.selectedClient?.id == clientId) client else it.selectedClient,
                        selectedClientDetails = if (it.selectedClient?.id == clientId && client != null)
                            routingEngine.buildClientDetails(client) else it.selectedClientDetails
                    )
                }
                setStatus("Notes saved for ${client?.name ?: "client"}")

                // Write back to sheet if configured
                if (SheetsWriteBack.webAppUrl.isNotBlank() && client != null) {
                    try {
                        val result = clientRepository.writeBackClientNotes(client.name, notes.trim())
                        if (result.success) {
                            setStatus("Notes synced to sheet for ${client.name}")
                            drainRetryQueueQuietly()
                        } else {
                            retryQueue.enqueue(client.name, "Notes", notes.trim())
                        }
                    } catch (_: Exception) {
                        retryQueue.enqueue(client.name, "Notes", notes.trim())
                    }
                }
            } catch (e: Exception) {
                setStatus("Save notes failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    private suspend fun fetchDrivingTimesForCurrentPage() {
        val location = lastSuggestionLocation ?: return
        val page = currentPageSuggestions()
        if (page.isEmpty()) return
        if (page.none { it.drivingTime == null }) return

        setStatus("Fetching driving times...", emitSnackbar = false)
        try {
            val drivingInfos = clientRepository.fetchDrivingTimes(
                location.latitude,
                location.longitude,
                page.map { it.client }
            )
            page.forEachIndexed { index, suggestion ->
                val info = drivingInfos.getOrNull(index)
                suggestion.drivingTime = info?.durationText
                suggestion.drivingDistance = info?.distanceText
            }
            _uiState.update { it.copy(suggestions = it.suggestions.toList()) }
            val selected = _uiState.value.selectedClient
            if (selected != null) {
                setStatus("Selected ${selected.name}", emitSnackbar = false)
            }
        } catch (e: Exception) {
            setStatus("Fetch times failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun loadSyncSettings() {
        val readUrl = preferencesRepository.sheetsReadUrl
        val writeUrl = preferencesRepository.sheetsWriteUrl
        SheetsWriteBack.webAppUrl = writeUrl
        _uiState.update {
            it.copy(
                sheetsReadUrl = readUrl,
                sheetsWriteUrl = writeUrl
            )
        }
    }

    private data class RouteExportResult(
        val uri: String,
        val includedStops: Int,
        val requestedStops: Int
    )

    private fun buildTopRouteExportFromSuggestions(state: MainUiState): RouteExportResult? {
        val mappableClients = state.suggestions
            .map { it.client }
            .filter { (it.latitude != null && it.longitude != null) || it.address.isNotBlank() }

        if (mappableClients.isEmpty()) return null

        val requestedStops = minOf(routeExportTopN, mappableClients.size)
        val topStops = mappableClients.take(requestedStops)
        return buildMapsRouteExport(topStops, state.routeDirection, state.activeDestination)
    }

    private fun buildMapsRouteExport(
        clients: List<Client>,
        routeDirection: RouteDirection,
        activeDestination: SavedDestination? = null
    ): RouteExportResult? {
        if (clients.isEmpty()) return null

        val origin = lastSuggestionLocation?.let { "${it.latitude},${it.longitude}" } ?: "$SHOP_LAT,$SHOP_LNG"

        // When navigating toward a destination, always end at that destination
        if (activeDestination != null) {
            val usableStops = clients.take(maxGoogleWaypoints)
            val destination = "${activeDestination.lat},${activeDestination.lng}"
            val waypoints = usableStops.mapNotNull { locationToken(it) }
            val uri = buildMapsDirectionsUrl(
                origin = origin,
                destination = destination,
                waypoints = waypoints
            )
            return RouteExportResult(uri, usableStops.size, clients.size)
        }

        return when (routeDirection) {
            RouteDirection.OUTWARD -> {
                val usableStops = clients.take(maxGoogleWaypoints + 1)
                if (usableStops.isEmpty()) return null

                val destination = locationToken(usableStops.last()) ?: return null
                val waypoints = usableStops.dropLast(1).mapNotNull { locationToken(it) }
                val uri = buildMapsDirectionsUrl(
                    origin = origin,
                    destination = destination,
                    waypoints = waypoints
                )

                RouteExportResult(uri, usableStops.size, clients.size)
            }

            RouteDirection.HOMEWARD -> {
                val usableStops = clients.take(maxGoogleWaypoints)
                val destination = "$SHOP_LAT,$SHOP_LNG"
                val waypoints = usableStops.mapNotNull { locationToken(it) }
                val uri = buildMapsDirectionsUrl(
                    origin = origin,
                    destination = destination,
                    waypoints = waypoints
                )

                RouteExportResult(uri, usableStops.size, clients.size)
            }
        }
    }

    private fun buildMapsDirectionsUrl(
        origin: String,
        destination: String,
        waypoints: List<String>
    ): String {
        val base = StringBuilder("https://www.google.com/maps/dir/?api=1")
        base.append("&travelmode=driving")
        base.append("&origin=").append(encodeQueryParam(origin))
        base.append("&destination=").append(encodeQueryParam(destination))
        if (waypoints.isNotEmpty()) {
            base.append("&waypoints=").append(encodeQueryParam(waypoints.joinToString("|")))
        }
        return base.toString()
    }

    private fun encodeQueryParam(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }

    private fun locationToken(client: Client): String? {
        val lat = client.latitude
        val lng = client.longitude
        if (lat != null && lng != null) {
            return "$lat,$lng"
        }

        val address = client.address.trim()
        return address.takeIf { it.isNotBlank() }
    }

    private suspend fun setStatus(message: String, emitSnackbar: Boolean = true) {
        _uiState.update { it.copy(statusText = message) }
        if (emitSnackbar) {
            _events.emit(MainEvent.ShowSnackbar(message))
        }
    }

    private fun setLoading(value: Boolean) {
        _uiState.update { it.copy(isLoading = value) }
    }

    private fun buildSummaryText(clients: List<com.routeme.app.Client>): String {
        val totalClients = clients.size
        val totalServices = clients.sumOf { it.records.size }
        return "$totalClients clients • $totalServices services"
    }

    /**
     * Determine which steps are fully completed — i.e. every client subscribed
     * to that step already has at least one service record for it.
     */
    private fun computeCompletedSteps(clients: List<com.routeme.app.Client>): Set<ServiceType> {
        if (clients.isEmpty()) return emptySet()
        val stepsToCheck = listOf(
            ServiceType.ROUND_1, ServiceType.ROUND_2, ServiceType.ROUND_3,
            ServiceType.ROUND_4, ServiceType.ROUND_5, ServiceType.ROUND_6,
            ServiceType.GRUB
        )
        return stepsToCheck.filter { step ->
            val subscribedClients = clients.filter { client ->
                when (step) {
                    ServiceType.GRUB -> client.hasGrub
                    else -> client.subscribedSteps.contains(step.stepNumber)
                }
            }
            // Only mark as completed if there are actually subscribers
            subscribedClients.isNotEmpty() && subscribedClients.all { client ->
                routingEngine.daysSinceLast(client, step) != null
            }
        }.toSet()
    }

    fun showDailySummary() {
        viewModelScope.launch {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startMillis = cal.timeInMillis
            val endMillis = startMillis + 86_400_000L

            try {
                val rows = clientRepository.getDailyRecords(startMillis, endMillis)
                val nonClientStops = clientRepository.getNonClientStops(startMillis, endMillis)
                if (rows.isEmpty() && nonClientStops.isEmpty()) {
                    setStatus("No completed services today")
                    return@launch
                }
                val summary = buildDailySummaryText(rows, nonClientStops)
                _events.emit(MainEvent.ShowDailySummary(summary))
            } catch (e: Exception) {
                setStatus("Summary failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    private fun buildDailySummaryText(rows: List<DailyRecordRow>, nonClientStops: List<NonClientStop> = emptyList()): String {
        val sb = StringBuilder()
        val totalStops = rows.size
        val totalMinutes = rows.sumOf { it.durationMinutes }
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        val durationLabel = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

        sb.appendLine("Today's Route Summary")
        sb.appendLine("─────────────────────")
        sb.appendLine("$totalStops stops  •  $durationLabel total")
        sb.appendLine()

        rows.forEachIndexed { index, row ->
            val timeStr = row.arrivedAtMillis?.let { DateUtils.formatTime(it) } ?: "—"
            val endStr = DateUtils.formatTime(row.completedAtMillis)
            sb.appendLine("${index + 1}. ${row.clientName}")
            sb.appendLine("   ${row.serviceType}")
            sb.appendLine("   $timeStr → $endStr")
            if (row.notes.isNotBlank()) {
                sb.appendLine("   \uD83D\uDCDD ${row.notes}")
            }
            if (index < rows.size - 1) sb.appendLine()
        }

        appendNonClientStopsSummary(sb, nonClientStops)
        return sb.toString()
    }

    // ─── Route History ─────────────────────────────────────────

    /** Cached ordered list of days that have at least one service record (newest first). */
    private var historyDates: List<Long> = emptyList()

    /** Show route history starting at today (or the most recent recorded day). */
    fun showRouteHistory() {
        viewModelScope.launch {
            try {
                val serviceDates = clientRepository.getDistinctServiceDates()
                val stopDates = clientRepository.getDistinctNonClientStopDates()
                historyDates = (serviceDates + stopDates).distinct().sortedDescending()
                if (historyDates.isEmpty()) {
                    setStatus("No service history yet")
                    return@launch
                }
                // Start on the most recent day (index 0 = newest)
                showHistoryForIndex(0)
            } catch (e: Exception) {
                setStatus("History failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    /** Navigate to a specific day in route history by its epoch millis. */
    fun showRouteHistoryForDate(dateMillis: Long) {
        viewModelScope.launch {
            try {
                // Refresh the list in case new records were added
                historyDates = clientRepository.getDistinctServiceDates()
                val index = historyDates.indexOf(dateMillis)
                if (index == -1) {
                    setStatus("No records for that date")
                    return@launch
                }
                showHistoryForIndex(index)
            } catch (e: Exception) {
                setStatus("History failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    /** Navigate forward or backward in history. delta = -1 (newer), +1 (older). */
    fun navigateHistory(currentDateMillis: Long, delta: Int) {
        viewModelScope.launch {
            val currentIndex = historyDates.indexOf(currentDateMillis)
            if (currentIndex == -1) return@launch
            val newIndex = currentIndex + delta
            if (newIndex !in historyDates.indices) return@launch
            showHistoryForIndex(newIndex)
        }
    }

    private suspend fun showHistoryForIndex(index: Int) {
        val dateMillis = historyDates[index]
        val startMillis = dateMillis
        val endMillis = dateMillis + 86_400_000L
        val rows = clientRepository.getDailyRecords(startMillis, endMillis)
        val nonClientStops = clientRepository.getNonClientStops(startMillis, endMillis)
        if (rows.isEmpty() && nonClientStops.isEmpty()) {
            setStatus("No records for ${DateUtils.formatDate(dateMillis)}")
            return
        }
        val summary = buildHistorySummaryText(dateMillis, rows, nonClientStops)
        val dateLabel = DateUtils.formatDateFull(dateMillis)
        _events.emit(
            MainEvent.ShowRouteHistory(
                summary = summary,
                dateLabel = dateLabel,
                dateMillis = dateMillis,
                hasPrevDay = index < historyDates.size - 1,  // older day exists
                hasNextDay = index > 0                        // newer day exists
            )
        )
    }

    private fun buildHistorySummaryText(@Suppress("UNUSED_PARAMETER") dateMillis: Long, rows: List<DailyRecordRow>, nonClientStops: List<NonClientStop> = emptyList()): String {
        val sb = StringBuilder()
        val totalStops = rows.size
        val totalMinutes = rows.sumOf { it.durationMinutes }
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        val durationLabel = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

        sb.appendLine("$totalStops stops  •  $durationLabel total")
        sb.appendLine()

        rows.forEachIndexed { index, row ->
            val timeStr = row.arrivedAtMillis?.let { DateUtils.formatTime(it) } ?: "—"
            val endStr = DateUtils.formatTime(row.completedAtMillis)
            sb.appendLine("${index + 1}. ${row.clientName}")
            sb.appendLine("   ${row.serviceType}")
            sb.appendLine("   $timeStr → $endStr  (${row.durationMinutes}m)")
            if (row.notes.isNotBlank()) {
                sb.appendLine("   \uD83D\uDCDD ${row.notes}")
            }
            if (index < rows.size - 1) sb.appendLine()
        }

        appendNonClientStopsSummary(sb, nonClientStops)
        return sb.toString()
    }

    /** Format non-client stops split into destination stops and breaks. */
    private fun appendNonClientStopsSummary(sb: StringBuilder, nonClientStops: List<NonClientStop>) {
        val destinationStops = nonClientStops.filter { it.label != null }
        val breakStops = nonClientStops.filter { it.label == null }

        if (destinationStops.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄")
            sb.appendLine("Destination Stops \uD83D\uDCE6")
            sb.appendLine()
            val destMinutes = destinationStops.sumOf { it.durationMinutes }
            val dHours = destMinutes / 60
            val dMins = destMinutes % 60
            val destLabel = if (dHours > 0) "${dHours}h ${dMins}m" else "${dMins}m"
            sb.appendLine("${destinationStops.size} destination(s)  •  $destLabel total")
            sb.appendLine()
            destinationStops.forEachIndexed { i, stop ->
                val name = stop.label ?: "Destination"
                val arriveStr = DateUtils.formatTime(stop.arrivedAtMillis)
                val departStr = stop.departedAtMillis?.let { DateUtils.formatTime(it) } ?: "ongoing"
                sb.appendLine("\uD83D\uDCCD $name")
                sb.appendLine("   $arriveStr → $departStr  (${stop.durationMinutes}m)")
                if (i < destinationStops.size - 1) sb.appendLine()
            }
        }

        if (breakStops.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄")
            sb.appendLine("Breaks / Non-Client Stops")
            sb.appendLine()
            val breakMinutes = breakStops.sumOf { it.durationMinutes }
            val bHours = breakMinutes / 60
            val bMins = breakMinutes % 60
            val breakLabel = if (bHours > 0) "${bHours}h ${bMins}m" else "${bMins}m"
            sb.appendLine("${breakStops.size} break(s)  •  $breakLabel total")
            sb.appendLine()
            breakStops.forEachIndexed { i, stop ->
                val addr = stop.address ?: "Unknown location"
                val arriveStr = DateUtils.formatTime(stop.arrivedAtMillis)
                val departStr = stop.departedAtMillis?.let { DateUtils.formatTime(it) } ?: "ongoing"
                sb.appendLine("☕ $addr")
                sb.appendLine("   $arriveStr → $departStr  (${stop.durationMinutes}m)")
                if (i < breakStops.size - 1) sb.appendLine()
            }
        }
    }

    private fun persistCriticalState(state: MainUiState) {
        savedStateHandle[KEY_SERVICE_TYPE] = state.selectedServiceTypes.joinToString(",") { it.name }
        savedStateHandle[KEY_MIN_DAYS] = state.minDays
        savedStateHandle[KEY_CU_OVERRIDE] = state.cuOverrideEnabled
        savedStateHandle[KEY_ROUTE_DIRECTION] = state.routeDirection.name
        savedStateHandle[KEY_SUGGESTION_OFFSET] = state.suggestionOffset
        savedStateHandle[KEY_SELECTED_CLIENT_ID] = state.selectedClient?.id
        savedStateHandle[KEY_ARRIVAL_STARTED_AT] = state.arrivalStartedAtMillis
        savedStateHandle[KEY_ARRIVAL_LAT] = state.arrivalLat
        savedStateHandle[KEY_ARRIVAL_LNG] = state.arrivalLng
        savedStateHandle[KEY_IS_TRACKING] = state.isTracking
        // Destination queue
        savedStateHandle[KEY_DEST_QUEUE] = state.destinationQueue.joinToString("|") {
            "${it.id},${it.name},${it.address},${it.lat},${it.lng}"
        }
        savedStateHandle[KEY_DEST_INDEX] = state.activeDestinationIndex
    }

    // ─── Retry-queue helpers ───────────────────────────────────

    /** Map ServiceType to the column header the Apps Script expects. */
    private fun serviceTypeToColumn(type: ServiceType): String = when (type) {
        ServiceType.ROUND_1 -> "Step 1"
        ServiceType.ROUND_2 -> "Step 2"
        ServiceType.ROUND_3 -> "Step 3"
        ServiceType.ROUND_4 -> "Step 4"
        ServiceType.ROUND_5 -> "Step 5"
        ServiceType.ROUND_6 -> "Step 6"
        ServiceType.GRUB    -> "Grub"
        ServiceType.INCIDENTAL -> "Incidental"
    }

    /** Format millis to √M.D check-value. */
    private fun formatCheckValue(dateMillis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return "$month.$day"
    }

    /** Silently attempt to drain the retry queue after a successful write. */
    private fun drainRetryQueueQuietly() {
        viewModelScope.launch {
            try {
                val result = retryQueue.drainQueue()
                if (result.succeeded > 0) {
                    setStatus("Retried ${result.succeeded} queued sheet write(s)", emitSnackbar = false)
                }
            } catch (_: Exception) { /* best effort */ }
        }
    }

    /** Called on init and resume to flush any backed-up writes. */
    fun retryPendingWrites() {
        if (SheetsWriteBack.webAppUrl.isBlank()) return
        drainRetryQueueQuietly()
    }
}
