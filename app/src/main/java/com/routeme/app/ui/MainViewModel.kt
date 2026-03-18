package com.routeme.app.ui

import android.location.Location
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routeme.app.Client
import com.routeme.app.ClientStopRow
import com.routeme.app.ClientStopStatus
import com.routeme.app.RouteDirection
import com.routeme.app.SavedDestination
import com.routeme.app.ServiceType
import com.routeme.app.NonClientStop
import com.routeme.app.suggestedStepsForDate
import com.routeme.app.TrackingEvent
import com.routeme.app.TrackingEventBus
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.domain.ArrivalUseCase
import com.routeme.app.domain.DestinationQueueUseCase
import com.routeme.app.domain.MapsExportUseCase
import com.routeme.app.domain.RouteHistoryUseCase
import com.routeme.app.domain.RoutingEngine
import com.routeme.app.domain.ServiceCompletionUseCase
import com.routeme.app.domain.SuggestionUseCase
import com.routeme.app.domain.SyncSettingsUseCase
import com.routeme.app.util.DateUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val clientRepository: ClientRepository,
    private val preferencesRepository: PreferencesRepository,
    private val routingEngine: RoutingEngine,
    private val savedStateHandle: SavedStateHandle,
    private val retryQueue: com.routeme.app.data.WriteBackRetryQueue,
    private val suggestionUseCase: SuggestionUseCase = SuggestionUseCase(routingEngine),
    private val arrivalUseCase: ArrivalUseCase = ArrivalUseCase(routingEngine),
    private val serviceCompletionUseCase: ServiceCompletionUseCase = ServiceCompletionUseCase(clientRepository, retryQueue),
    private val destinationQueueUseCase: DestinationQueueUseCase = DestinationQueueUseCase(preferencesRepository, routingEngine),
    private val routeHistoryUseCase: RouteHistoryUseCase = RouteHistoryUseCase(clientRepository),
    private val mapsExportUseCase: MapsExportUseCase = MapsExportUseCase(),
    private val syncSettingsUseCase: SyncSettingsUseCase = SyncSettingsUseCase(clientRepository, preferencesRepository, retryQueue)
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
                when (val result = syncSettingsUseCase.loadClients()) {
                    is SyncSettingsUseCase.LoadClientsResult.Error -> {
                        setStatus(result.message)
                    }

                    is SyncSettingsUseCase.LoadClientsResult.Success -> {
                        val loaded = result.clients
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
                        setStatus(result.statusMessage)
                    }
                }
            } finally {
                setLoading(false)
            }
        }
    }

    fun importClients(uri: Uri) {
        viewModelScope.launch {
            setLoading(true)
            try {
                when (
                    val result = syncSettingsUseCase.importClients(
                        existingClients = _uiState.value.clients,
                        uri = uri
                    )
                ) {
                    is SyncSettingsUseCase.ImportClientsResult.Error -> {
                        setStatus(result.message)
                    }

                    is SyncSettingsUseCase.ImportClientsResult.Success -> {
                        setStatus(result.statusMessage)
                        if (result.didImportClients) {
                            _uiState.update {
                                it.copy(
                                    clients = result.clients,
                                    summaryText = buildSummaryText(result.clients),
                                    completedSteps = computeCompletedSteps(result.clients)
                                )
                            }
                            persistCriticalState(_uiState.value)
                        }
                    }
                }
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
                when (val result = syncSettingsUseCase.syncFromSheets(url)) {
                    is SyncSettingsUseCase.SyncFromSheetsResult.Error -> {
                        setStatus(result.message)
                    }

                    is SyncSettingsUseCase.SyncFromSheetsResult.Success -> {
                        setStatus(result.statusMessage)
                        val syncedClients = result.syncedClients
                        if (syncedClients != null) {
                            _uiState.update {
                                it.copy(
                                    clients = syncedClients,
                                    summaryText = buildSummaryText(syncedClients),
                                    completedSteps = computeCompletedSteps(syncedClients),
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
                            if (result.shouldAutoGeocode) {
                                geocodeMissingClientCoordinates()
                            }
                        }
                    }
                }
            } finally {
                setLoading(false)
            }
        }
    }

    fun geocodeMissingClientCoordinates() {
        viewModelScope.launch {
            val clients = _uiState.value.clients
            val withoutCoordsCount = syncSettingsUseCase.missingCoordinatesCount(clients)
            if (withoutCoordsCount == 0) {
                setStatus("All clients already have coordinates.")
                return@launch
            }

            setLoading(true)
            setStatus("Geocoding ${withoutCoordsCount} client(s)…", emitSnackbar = false)
            try {
                when (val result = syncSettingsUseCase.geocodeMissingClientCoordinates(clients)) {
                    is SyncSettingsUseCase.GeocodeResult.Error -> {
                        setStatus(result.message)
                    }

                    is SyncSettingsUseCase.GeocodeResult.NoMissingCoordinates -> {
                        setStatus(result.statusMessage)
                    }

                    is SyncSettingsUseCase.GeocodeResult.Success -> {
                        setStatus(result.statusMessage)
                        _uiState.update {
                            it.copy(
                                clients = result.clients,
                                summaryText = buildSummaryText(result.clients),
                                completedSteps = computeCompletedSteps(result.clients)
                            )
                        }
                        persistCriticalState(_uiState.value)
                    }
                }
            } finally {
                setLoading(false)
            }
        }
    }

    fun updateSyncSettings(readUrl: String, writeUrl: String) {
        val settings = syncSettingsUseCase.updateSyncSettings(readUrl, writeUrl)
        _uiState.update {
            it.copy(
                sheetsReadUrl = settings.readUrl,
                sheetsWriteUrl = settings.writeUrl
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
    fun isNonClientLoggingEnabled(): Boolean = syncSettingsUseCase.isNonClientLoggingEnabled()

    fun toggleNonClientLogging() {
        val result = syncSettingsUseCase.toggleNonClientLogging()
        viewModelScope.launch {
            setStatus(result.statusMessage)
        }
    }

    /** Current threshold in minutes. */
    fun getNonClientStopThreshold(): Int = syncSettingsUseCase.getNonClientStopThreshold()

    fun setNonClientStopThreshold(minutes: Int) {
        val result = syncSettingsUseCase.setNonClientStopThreshold(minutes)
        viewModelScope.launch {
            setStatus(result.statusMessage)
        }
    }

    // ─── Destination queue management ──────────────────────────

    private fun loadSavedDestinations() {
        _uiState.update { it.copy(savedDestinations = destinationQueueUseCase.loadSavedDestinations()) }
    }

    fun addSavedDestination(dest: SavedDestination) {
        val updated = destinationQueueUseCase.addSavedDestination(dest)
        _uiState.update { it.copy(savedDestinations = updated) }
    }

    fun removeSavedDestination(id: String) {
        val updated = destinationQueueUseCase.removeSavedDestination(id)
        _uiState.update { it.copy(savedDestinations = updated) }
    }

    fun addToDestinationQueue(dest: SavedDestination) {
        val state = _uiState.value
        val result = destinationQueueUseCase.addToDestinationQueue(
            destinationQueue = state.destinationQueue,
            activeDestinationIndex = state.activeDestinationIndex,
            destination = dest
        )
        _uiState.update {
            it.copy(
                destinationQueue = result.destinationQueue,
                activeDestinationIndex = result.activeDestinationIndex
            )
        }
        result.statusMessage?.let { message ->
            viewModelScope.launch { setStatus(message) }
        }
    }

    fun removeFromDestinationQueue(index: Int) {
        val state = _uiState.value
        val result = destinationQueueUseCase.removeFromDestinationQueue(
            destinationQueue = state.destinationQueue,
            activeDestinationIndex = state.activeDestinationIndex,
            indexToRemove = index
        )
        _uiState.update {
            it.copy(
                destinationQueue = result.destinationQueue,
                activeDestinationIndex = result.activeDestinationIndex
            )
        }
    }

    fun moveDestinationInQueue(fromIndex: Int, toIndex: Int) {
        val state = _uiState.value
        val result = destinationQueueUseCase.moveDestinationInQueue(
            destinationQueue = state.destinationQueue,
            activeDestinationIndex = state.activeDestinationIndex,
            fromIndex = fromIndex,
            toIndex = toIndex
        )
        _uiState.update {
            it.copy(
                destinationQueue = result.destinationQueue,
                activeDestinationIndex = result.activeDestinationIndex
            )
        }
    }

    fun clearDestinationQueue() {
        val result = destinationQueueUseCase.clearDestinationQueue()
        _uiState.update {
            it.copy(
                destinationQueue = result.destinationQueue,
                activeDestinationIndex = result.activeDestinationIndex
            )
        }
        result.statusMessage?.let { message ->
            viewModelScope.launch { setStatus(message) }
        }
    }

    fun skipDestination() {
        val state = _uiState.value
        val result = destinationQueueUseCase.skipDestination(
            destinationQueue = state.destinationQueue,
            activeDestinationIndex = state.activeDestinationIndex
        )
        _uiState.update {
            it.copy(
                destinationQueue = result.destinationQueue,
                activeDestinationIndex = result.activeDestinationIndex
            )
        }
    }

    fun optimizeDestinationQueue(currentLocation: Location?) {
        val state = _uiState.value
        val result = destinationQueueUseCase.optimizeDestinationQueue(
            destinationQueue = state.destinationQueue,
            currentLocation = currentLocation?.let {
                DestinationQueueUseCase.GeoPoint(it.latitude, it.longitude)
            }
        ) ?: return

        _uiState.update {
            it.copy(
                destinationQueue = result.destinationQueue,
                activeDestinationIndex = result.activeDestinationIndex
            )
        }
        result.statusMessage?.let { message ->
            viewModelScope.launch { setStatus(message) }
        }
    }

    /** Called when tracking detects arrival at the active destination. */
    fun onDestinationReached(destinationName: String) {
        viewModelScope.launch {
            val state = _uiState.value
            val result = destinationQueueUseCase.onDestinationReached(
                destinationQueue = state.destinationQueue,
                activeDestinationIndex = state.activeDestinationIndex,
                destinationName = destinationName
            )
            _events.emit(MainEvent.ShowSnackbar(result.snackbarMessage))
            _uiState.update {
                it.copy(
                    destinationQueue = result.destinationQueue,
                    activeDestinationIndex = result.activeDestinationIndex
                )
            }
        }
    }

    fun suggestNextClients(currentLocation: Location?) {
        if (checkAndPromptStaleArrival { suggestNextClients(currentLocation) }) return
        viewModelScope.launch {
            val state = _uiState.value
            val result = suggestionUseCase.suggestNextClients(
                clients = state.clients,
                selectedServiceTypes = state.selectedServiceTypes,
                minDays = state.minDays,
                cuOverrideEnabled = state.cuOverrideEnabled,
                routeDirection = state.routeDirection,
                activeDestination = state.activeDestination,
                currentLocation = currentLocation
            )

            if (result.dateRolloverDetected) {
                clearDestinationQueue()
            }

            if (result.suggestions.isEmpty()) {
                _uiState.update {
                    it.copy(
                        suggestions = emptyList(),
                        suggestionOffset = result.suggestionOffset,
                        selectedClient = null,
                        selectedClientDetails = ""
                    )
                }
                setStatus(result.statusMessage)
                return@launch
            }

            _uiState.update {
                it.copy(
                    suggestions = result.suggestions,
                    suggestionOffset = result.suggestionOffset,
                    selectedClient = result.selectedClient,
                    selectedClientDetails = result.selectedClientDetails
                )
            }
            persistCriticalState(_uiState.value)
            setStatus(result.statusMessage)
            fetchDrivingTimesForCurrentPage()
        }
    }

    fun nextSuggestionPage() {
        val state = _uiState.value
        if (state.suggestions.isEmpty()) return
        val wrapped = suggestionUseCase.nextSuggestionOffset(
            currentOffset = state.suggestionOffset,
            totalSuggestions = state.suggestions.size
        )
        _uiState.update { it.copy(suggestionOffset = wrapped) }
        savedStateHandle[KEY_SUGGESTION_OFFSET] = wrapped
        viewModelScope.launch { fetchDrivingTimesForCurrentPage() }
    }

    fun previousSuggestionPage() {
        val state = _uiState.value
        if (state.suggestions.isEmpty()) return
        val prevOffset = suggestionUseCase.previousSuggestionOffset(state.suggestionOffset)
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
        val result = suggestionUseCase.skipSelectedClientToday(
            selectedClient = state.selectedClient,
            suggestions = state.suggestions
        ) ?: run {
            viewModelScope.launch { setStatus("Pick a client first") }
            return
        }

        _uiState.update {
            it.copy(
                suggestions = result.suggestions,
                suggestionOffset = result.suggestionOffset,
                selectedClient = result.selectedClient,
                selectedClientDetails = result.selectedClientDetails,
                arrivalStartedAtMillis = null,
                arrivalLat = null,
                arrivalLng = null
            )
        }
        persistCriticalState(_uiState.value)
        viewModelScope.launch {
            setStatus(result.statusMessage)
        }
    }

    /** Returns number of clients currently skipped today. */
    fun skippedCount(): Int = suggestionUseCase.skippedCount()

    /** Clears all skip-today flags. */
    fun clearSkippedClients() {
        suggestionUseCase.clearSkippedClients()
        viewModelScope.launch { setStatus("Cleared all skips") }
    }

    fun exportTopRouteToMaps() {
        viewModelScope.launch {
            val state = _uiState.value
            when (
                val result = mapsExportUseCase.exportTopRoute(
                    suggestions = state.suggestions,
                    routeDirection = state.routeDirection,
                    activeDestination = state.activeDestination,
                    originLocation = suggestionUseCase.lastSuggestionLocation()?.let {
                        MapsExportUseCase.GeoPoint(it.latitude, it.longitude)
                    }
                )
            ) {
                MapsExportUseCase.ExportResult.NoMappableClients -> {
                    setStatus("No mappable clients in current suggestions")
                }

                MapsExportUseCase.ExportResult.NoSuggestions -> {
                    setStatus("Run suggestions first")
                }

                is MapsExportUseCase.ExportResult.Success -> {
                    val routeExport = result.routeExport
                    _events.emit(MainEvent.OpenMapsRoute(routeExport.uri))

                    val clipped = routeExport.requestedStops - routeExport.includedStops
                    if (clipped > 0) {
                        setStatus("Opened Maps route with ${routeExport.includedStops} of ${routeExport.requestedStops} stops")
                    } else {
                        setStatus("Opened Maps route with ${routeExport.includedStops} stops")
                    }
                }
            }
        }
    }

    internal fun buildTopRouteExportForTests(): Pair<String, Int>? {
        val state = _uiState.value
        val result = mapsExportUseCase.exportTopRoute(
            suggestions = state.suggestions,
            routeDirection = state.routeDirection,
            activeDestination = state.activeDestination,
            originLocation = suggestionUseCase.lastSuggestionLocation()?.let {
                MapsExportUseCase.GeoPoint(it.latitude, it.longitude)
            }
        )
        return (result as? MapsExportUseCase.ExportResult.Success)
            ?.routeExport
            ?.let { it.uri to it.includedStops }
    }

    fun startArrivalForSelected(currentLocation: Location?) {
        when (val result = arrivalUseCase.startArrivalForSelected(
            selectedClient = _uiState.value.selectedClient,
            currentLocation = currentLocation?.let {
                ArrivalUseCase.GeoPoint(it.latitude, it.longitude)
            }
        )) {
            is ArrivalUseCase.StartArrivalResult.Error -> {
                viewModelScope.launch { setStatus(result.message) }
            }
            is ArrivalUseCase.StartArrivalResult.Started -> {
                val location = currentLocation ?: return
                applyArrival(result.arrival, location)
            }
        }
    }

    fun cancelArrival() {
        val state = _uiState.value
        val selectedClient = state.selectedClient
        val arrivalStartedAtMillis = state.arrivalStartedAtMillis
        val statusMessage = arrivalUseCase.cancelArrival(
            arrivalStartedAtMillis = arrivalStartedAtMillis,
            selectedClientName = selectedClient?.name
        ) ?: return

        if (selectedClient != null && arrivalStartedAtMillis != null) {
            recordCancelledClientStop(
                client = selectedClient,
                arrivedAtMillis = arrivalStartedAtMillis,
                reason = "manual_cancel",
                location = state.arrivalLat?.let { lat ->
                    state.arrivalLng?.let { lng ->
                        Location("arrival").apply {
                            latitude = lat
                            longitude = lng
                        }
                    }
                }
            )
        }

        _uiState.update { it.copy(arrivalStartedAtMillis = null, arrivalLat = null, arrivalLng = null) }
        savedStateHandle[KEY_ARRIVAL_STARTED_AT] = null
        viewModelScope.launch { setStatus(statusMessage) }
    }

    /**
     * Returns true (and emits a prompt) if there's a pending arrival the user hasn't confirmed.
     * The [deferredAction] will run automatically after the user resolves the prompt.
     */
    private fun checkAndPromptStaleArrival(deferredAction: () -> Unit): Boolean {
        val prompt = arrivalUseCase.createStaleArrivalPrompt(
            arrivalStartedAtMillis = _uiState.value.arrivalStartedAtMillis,
            selectedClientName = _uiState.value.selectedClient?.name,
            deferredAction = deferredAction
        ) ?: return false

        viewModelScope.launch {
            _events.emit(MainEvent.StaleArrivalPrompt(prompt.clientName, prompt.minutesElapsed))
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
        when (val result = arrivalUseCase.resolveStaleArrival(
            markComplete = markComplete,
            selectedClientName = _uiState.value.selectedClient?.name
        )) {
            is ArrivalUseCase.ResolveStaleResult.ConfirmAndContinue -> {
                confirmSelectedClientService(currentLocation, visitNotes)
                result.deferredAction?.invoke()
            }
            is ArrivalUseCase.ResolveStaleResult.DiscardAndContinue -> {
                val state = _uiState.value
                if (state.selectedClient != null && state.arrivalStartedAtMillis != null) {
                    recordCancelledClientStop(
                        client = state.selectedClient,
                        arrivedAtMillis = state.arrivalStartedAtMillis,
                        reason = "stale_discard",
                        location = state.arrivalLat?.let { lat ->
                            state.arrivalLng?.let { lng ->
                                Location("arrival").apply {
                                    latitude = lat
                                    longitude = lng
                                }
                            }
                        }
                    )
                }
                _uiState.update { it.copy(arrivalStartedAtMillis = null, arrivalLat = null, arrivalLng = null) }
                savedStateHandle[KEY_ARRIVAL_STARTED_AT] = null
                viewModelScope.launch { setStatus(result.statusMessage) }
                result.deferredAction?.invoke()
            }
        }
    }

    fun recordCancelledClientStop(
        client: Client,
        arrivedAtMillis: Long,
        reason: String,
        location: Location? = null
    ) {
        viewModelScope.launch {
            val endedAtMillis = System.currentTimeMillis()
            val elapsedMillis = (endedAtMillis - arrivedAtMillis).coerceAtLeast(0L)
            val durationMinutes = (elapsedMillis / 60_000L).coerceAtLeast(1L)

            runCatching {
                clientRepository.saveClientStopEvent(
                    clientId = client.id,
                    clientName = client.name,
                    arrivedAtMillis = arrivedAtMillis,
                    endedAtMillis = endedAtMillis,
                    durationMinutes = durationMinutes,
                    status = ClientStopStatus.CANCELLED,
                    cancelReason = reason,
                    lat = location?.latitude,
                    lng = location?.longitude
                )
            }
        }
    }

    fun dropPendingStaleAction() {
        arrivalUseCase.dropPendingStaleAction()
    }

    fun markArrivalForClient(client: Client, location: Location, startedAtMillis: Long = System.currentTimeMillis()) {
        val arrival = arrivalUseCase.markArrivalForClient(
            client = client,
            location = ArrivalUseCase.GeoPoint(location.latitude, location.longitude),
            startedAtMillis = startedAtMillis
        )
        applyArrival(arrival, location)
    }

    private fun applyArrival(arrival: ArrivalUseCase.MarkArrivalResult, location: Location) {
        suggestionUseCase.updateLastSuggestionLocation(location)
        _uiState.update {
            it.copy(
                selectedClient = arrival.selectedClient,
                selectedClientDetails = arrival.selectedClientDetails,
                arrivalStartedAtMillis = arrival.arrivalStartedAtMillis,
                arrivalLat = arrival.arrivalLat,
                arrivalLng = arrival.arrivalLng
            )
        }
        persistCriticalState(_uiState.value)
        viewModelScope.launch {
            setStatus(
                "Arrival started for ${arrival.selectedClient.name} at ${DateUtils.formatTimestamp(arrival.arrivalStartedAtMillis)}"
            )
        }
    }

    fun currentPageSuggestions(): List<com.routeme.app.ClientSuggestion> {
        val state = _uiState.value
        return suggestionUseCase.currentPageSuggestions(
            suggestions = state.suggestions,
            suggestionOffset = state.suggestionOffset
        )
    }

    fun canShowMoreSuggestions(): Boolean {
        val state = _uiState.value
        return suggestionUseCase.canShowMoreSuggestions(
            suggestionOffset = state.suggestionOffset,
            totalSuggestions = state.suggestions.size
        )
    }

    fun canShowPreviousSuggestions(): Boolean {
        return suggestionUseCase.canShowPreviousSuggestions(_uiState.value.suggestionOffset)
    }

    fun remainingSuggestionCount(): Int {
        val state = _uiState.value
        return suggestionUseCase.remainingSuggestionCount(
            suggestionOffset = state.suggestionOffset,
            totalSuggestions = state.suggestions.size
        )
    }

    fun confirmSelectedClientService(currentLocation: Location?, visitNotes: String = "") {
        viewModelScope.launch {
            val state = _uiState.value
            val selectedSuggestionEligibleSteps = state.selectedClient
                ?.let { client -> state.suggestions.find { it.client.id == client.id }?.eligibleSteps }
                ?: emptySet()

            when (
                val result = serviceCompletionUseCase.confirmSelectedClientService(
                    ServiceCompletionUseCase.ConfirmSelectedRequest(
                        clients = state.clients,
                        selectedClient = state.selectedClient,
                        arrivalStartedAtMillis = state.arrivalStartedAtMillis,
                        arrivalLat = state.arrivalLat,
                        arrivalLng = state.arrivalLng,
                        selectedSuggestionEligibleSteps = selectedSuggestionEligibleSteps,
                        selectedServiceTypes = state.selectedServiceTypes,
                        currentLocation = currentLocation?.let {
                            ServiceCompletionUseCase.GeoPoint(it.latitude, it.longitude)
                        },
                        visitNotes = visitNotes
                    )
                )
            ) {
                is ServiceCompletionUseCase.ConfirmSelectedResult.Error -> {
                    setStatus(result.message)
                }

                is ServiceCompletionUseCase.ConfirmSelectedResult.Success -> {
                    _uiState.update {
                        it.copy(
                            clients = result.updatedClients,
                            summaryText = buildSummaryText(result.updatedClients),
                            completedSteps = computeCompletedSteps(result.updatedClients),
                            selectedClient = result.selectedClient,
                            selectedClientDetails = routingEngine.buildClientDetails(result.selectedClient),
                            arrivalStartedAtMillis = null,
                            arrivalLat = null,
                            arrivalLng = null
                        )
                    }
                    persistCriticalState(_uiState.value)

                    setStatus(result.statusMessage)
                    if (result.retryDrainSucceeded > 0) {
                        setStatus("Retried ${result.retryDrainSucceeded} queued sheet write(s)", emitSnackbar = false)
                    }
                    _events.emit(MainEvent.ServiceConfirmed)
                    _events.emit(
                        MainEvent.UndoConfirmation(
                            result.selectedClient.name,
                            result.selectedClient.id,
                            result.finishedAt
                        )
                    )

                    result.sheetSnackbarMessage?.let { message ->
                        _events.emit(MainEvent.ShowSnackbar(message))
                    }
                    result.sheetStatusMessage?.let { message ->
                        setStatus(message)
                    }
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
            val eligibleStepsByClientId = state.suggestions
                .associate { suggestion -> suggestion.client.id to suggestion.eligibleSteps }

            when (
                val result = serviceCompletionUseCase.confirmClusterService(
                    ServiceCompletionUseCase.ConfirmClusterRequest(
                        clients = state.clients,
                        selectedServiceTypes = state.selectedServiceTypes,
                        suggestionEligibleStepsByClientId = eligibleStepsByClientId,
                        selectedMembers = selectedMembers.map { member ->
                            ServiceCompletionUseCase.ClusterMemberInput(
                                clientId = member.client.id,
                                clientName = member.client.name,
                                arrivedAtMillis = member.arrivedAtMillis,
                                location = ServiceCompletionUseCase.GeoPoint(
                                    member.location.latitude,
                                    member.location.longitude
                                )
                            )
                        }
                    )
                )
            ) {
                is ServiceCompletionUseCase.ConfirmClusterResult.Error -> {
                    setStatus(result.message)
                }

                is ServiceCompletionUseCase.ConfirmClusterResult.Success -> {
                    result.transientFailureMessages.forEach { message ->
                        setStatus(message)
                    }

                    _uiState.update {
                        it.copy(
                            clients = result.updatedClients,
                            summaryText = buildSummaryText(result.updatedClients),
                            completedSteps = computeCompletedSteps(result.updatedClients),
                            arrivalStartedAtMillis = null,
                            arrivalLat = null,
                            arrivalLng = null
                        )
                    }
                    persistCriticalState(_uiState.value)

                    setStatus(result.statusMessage)
                    _events.emit(MainEvent.ServiceConfirmed)
                    if (result.confirmedIds.isNotEmpty()) {
                        _events.emit(
                            MainEvent.UndoClusterConfirmation(
                                result.confirmedNames,
                                result.confirmedIds,
                                result.finishedAt
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Undo the most recently confirmed service record.
     * Deletes it from the DB and removes it from the in-memory client records list.
     */
    fun undoLastConfirmation(clientId: String, completedAtMillis: Long) {
        viewModelScope.launch {
            when (
                val result = serviceCompletionUseCase.undoLastConfirmation(
                    clients = _uiState.value.clients,
                    clientId = clientId,
                    completedAtMillis = completedAtMillis
                )
            ) {
                is ServiceCompletionUseCase.UndoLastResult.Error -> {
                    setStatus(result.message)
                }

                is ServiceCompletionUseCase.UndoLastResult.Success -> {
                    val state = _uiState.value
                    _uiState.update {
                        it.copy(
                            clients = result.updatedClients,
                            summaryText = buildSummaryText(result.updatedClients),
                            completedSteps = computeCompletedSteps(result.updatedClients),
                            selectedClientDetails = if (state.selectedClient?.id == clientId && result.updatedClient != null)
                                routingEngine.buildClientDetails(result.updatedClient) else it.selectedClientDetails
                        )
                    }
                    setStatus("Undone — record removed")
                }
            }
        }
    }

    /**
     * Undo a cluster of service records that were batch-confirmed together.
     */
    fun undoClusterConfirmation(clientIds: List<String>, completedAtMillis: Long) {
        viewModelScope.launch {
            when (
                val result = serviceCompletionUseCase.undoClusterConfirmation(
                    clients = _uiState.value.clients,
                    clientIds = clientIds,
                    completedAtMillis = completedAtMillis
                )
            ) {
                is ServiceCompletionUseCase.UndoClusterResult.Error -> {
                    setStatus(result.message)
                }

                is ServiceCompletionUseCase.UndoClusterResult.Success -> {
                    _uiState.update {
                        it.copy(
                            clients = result.updatedClients,
                            summaryText = buildSummaryText(result.updatedClients),
                            completedSteps = computeCompletedSteps(result.updatedClients)
                        )
                    }
                    setStatus("Undone — ${clientIds.size} records removed")
                }
            }
        }
    }

    /**
     * Opens the edit-notes dialog for the currently selected client.
     */
    fun editSelectedClientNotes() {
        viewModelScope.launch {
            when (val result = serviceCompletionUseCase.editSelectedClientNotes(_uiState.value.selectedClient)) {
                is ServiceCompletionUseCase.EditNotesResult.Error -> {
                    setStatus(result.message)
                }

                is ServiceCompletionUseCase.EditNotesResult.OpenEditor -> {
                    _events.emit(
                        MainEvent.EditClientNotes(
                            result.clientId,
                            result.clientName,
                            result.currentNotes
                        )
                    )
                }
            }
        }
    }

    /**
     * Saves updated persistent notes for a client (local DB + sheet write-back).
     */
    fun saveClientNotes(clientId: String, notes: String) {
        viewModelScope.launch {
            when (
                val result = serviceCompletionUseCase.saveClientNotes(
                    ServiceCompletionUseCase.SaveNotesRequest(
                        clients = _uiState.value.clients,
                        currentSelectedClientId = _uiState.value.selectedClient?.id,
                        clientId = clientId,
                        notes = notes
                    )
                )
            ) {
                is ServiceCompletionUseCase.SaveNotesResult.Error -> {
                    setStatus(result.message)
                }

                is ServiceCompletionUseCase.SaveNotesResult.Success -> {
                    _uiState.update {
                        it.copy(
                            clients = result.updatedClients,
                            selectedClient = result.updatedSelectedClient ?: it.selectedClient,
                            selectedClientDetails = if (result.updatedSelectedClient != null)
                                routingEngine.buildClientDetails(result.updatedSelectedClient) else it.selectedClientDetails
                        )
                    }

                    setStatus(result.savedStatusMessage)
                    result.syncedStatusMessage?.let { message ->
                        setStatus(message)
                    }
                    if (result.retryDrainSucceeded > 0) {
                        setStatus("Retried ${result.retryDrainSucceeded} queued sheet write(s)", emitSnackbar = false)
                    }
                }
            }
        }
    }

    private suspend fun fetchDrivingTimesForCurrentPage() {
        val location = suggestionUseCase.lastSuggestionLocation() ?: return
        val state = _uiState.value
        val page = suggestionUseCase.currentPageSuggestions(
            suggestions = state.suggestions,
            suggestionOffset = state.suggestionOffset
        )
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
        val settings = syncSettingsUseCase.loadSyncSettings()
        _uiState.update {
            it.copy(
                sheetsReadUrl = settings.readUrl,
                sheetsWriteUrl = settings.writeUrl
            )
        }
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
            when (val result = routeHistoryUseCase.loadDailySummary()) {
                is RouteHistoryUseCase.DailySummaryResult.Empty -> {
                    setStatus("No completed services or non-client stops today")
                }

                is RouteHistoryUseCase.DailySummaryResult.Error -> {
                    setStatus(result.message)
                }

                is RouteHistoryUseCase.DailySummaryResult.Success -> {
                    val summary = buildDailySummaryText(result.rows, result.nonClientStops)
                    _events.emit(MainEvent.ShowDailySummary(summary))
                }
            }
        }
    }

    private fun buildDailySummaryText(rows: List<ClientStopRow>, nonClientStops: List<NonClientStop> = emptyList()): String {
        val sb = StringBuilder()
        val totalStops = rows.size
        val totalMinutes = rows.sumOf { it.durationMinutes }
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        val durationLabel = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

        sb.appendLine("Today's Route Summary")
        sb.appendLine("─────────────────────")

        // First/last stop clock window
        val firstArrival = rows.mapNotNull { it.arrivedAtMillis }.minOrNull()
        val lastEnd = rows.maxOfOrNull { it.endedAtMillis }
        if (firstArrival != null && lastEnd != null) {
            sb.appendLine("${DateUtils.formatTime(firstArrival)} – ${DateUtils.formatTime(lastEnd)}")
        }
        sb.appendLine("$totalStops stops  •  $durationLabel total")
        sb.appendLine()

        rows.forEachIndexed { index, row ->
            val timeStr = row.arrivedAtMillis?.let { DateUtils.formatTime(it) } ?: "—"
            val endStr = DateUtils.formatTime(row.endedAtMillis)
            sb.appendLine("${index + 1}. ${row.clientName}")
            sb.appendLine("   ${formatClientStopDetail(row)}")
            sb.appendLine("   $timeStr → $endStr  (${row.durationMinutes}m)")
            if (row.notes.isNotBlank()) {
                sb.appendLine("   \uD83D\uDCDD ${row.notes}")
            }
            if (index < rows.size - 1) sb.appendLine()
        }

        appendNonClientStopsSummary(sb, nonClientStops)
        return sb.toString()
    }

    // ─── Route History ─────────────────────────────────────────

    /** Show route history starting at today (or the most recent recorded day). */
    fun showRouteHistory() {
        viewModelScope.launch {
            handleRouteHistoryResult(routeHistoryUseCase.loadRouteHistoryStart())
        }
    }

    /** Navigate to a specific day in route history by its epoch millis. */
    fun showRouteHistoryForDate(dateMillis: Long) {
        viewModelScope.launch {
            handleRouteHistoryResult(routeHistoryUseCase.loadRouteHistoryForDate(dateMillis))
        }
    }

    /** Navigate forward or backward in history. delta = -1 (newer), +1 (older). */
    fun navigateHistory(currentDateMillis: Long, delta: Int) {
        viewModelScope.launch {
            val result = routeHistoryUseCase.navigateHistory(currentDateMillis, delta)
            if (result is RouteHistoryUseCase.HistoryResult.NavigationUnavailable) return@launch
            handleRouteHistoryResult(result)
        }
    }

    private suspend fun handleRouteHistoryResult(result: RouteHistoryUseCase.HistoryResult) {
        when (result) {
            is RouteHistoryUseCase.HistoryResult.Success -> {
                emitRouteHistoryDay(result.dayData)
            }

            is RouteHistoryUseCase.HistoryResult.Error -> {
                setStatus(result.message)
            }

            is RouteHistoryUseCase.HistoryResult.NoRecordsForDate -> {
                setStatus("No records for ${DateUtils.formatDate(result.dateMillis)}")
            }

            RouteHistoryUseCase.HistoryResult.NoHistory -> {
                setStatus("No route history yet")
            }

            RouteHistoryUseCase.HistoryResult.NoRecordsForRequestedDate -> {
                setStatus("No records for that date")
            }

            RouteHistoryUseCase.HistoryResult.NavigationUnavailable -> Unit
        }
    }

    private suspend fun emitRouteHistoryDay(dayData: RouteHistoryUseCase.DayData) {
        val summary = buildHistorySummaryText(dayData.dateMillis, dayData.rows, dayData.nonClientStops)
        val dateLabel = DateUtils.formatDateFull(dayData.dateMillis)
        _events.emit(
            MainEvent.ShowRouteHistory(
                summary = summary,
                dateLabel = dateLabel,
                dateMillis = dayData.dateMillis,
                hasPrevDay = dayData.hasPrevDay,
                hasNextDay = dayData.hasNextDay,
                gapDaysToOlder = dayData.gapDaysToOlder,
                gapDaysToNewer = dayData.gapDaysToNewer
            )
        )
    }

    private fun buildHistorySummaryText(@Suppress("UNUSED_PARAMETER") dateMillis: Long, rows: List<ClientStopRow>, nonClientStops: List<NonClientStop> = emptyList()): String {
        val sb = StringBuilder()
        val totalStops = rows.size
        val totalMinutes = rows.sumOf { it.durationMinutes }
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        val durationLabel = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

        sb.appendLine("$totalStops stops  •  $durationLabel total")

        // Step breakdown
        val stepCounts = rows
            .flatMap { row -> row.serviceTypes.split(",").map { it.trim() }.filter { it.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
        if (stepCounts.isNotEmpty()) {
            val parts = stepCounts.map { (type, count) ->
                val label = runCatching { ServiceType.valueOf(type).label }.getOrElse { type }
                "$label: $count"
            }
            sb.appendLine(parts.joinToString("  •  "))
        }
        sb.appendLine()

        rows.forEachIndexed { index, row ->
            val timeStr = row.arrivedAtMillis?.let { DateUtils.formatTime(it) } ?: "—"
            val endStr = DateUtils.formatTime(row.endedAtMillis)
            sb.appendLine("${index + 1}. ${row.clientName}")
            sb.appendLine("   ${formatClientStopDetail(row)}")
            sb.appendLine("   $timeStr → $endStr  (${row.durationMinutes}m)")
            if (row.notes.isNotBlank()) {
                sb.appendLine("   \uD83D\uDCDD ${row.notes}")
            }
            if (index < rows.size - 1) sb.appendLine()
        }

        appendNonClientStopsSummary(sb, nonClientStops)
        return sb.toString()
    }

    private fun formatClientStopDetail(row: ClientStopRow): String {
        val status = runCatching { ClientStopStatus.valueOf(row.status) }.getOrDefault(ClientStopStatus.DONE)
        return when (status) {
            ClientStopStatus.DONE -> {
                val stepsLabel = formatServiceTypes(row.serviceTypes)
                if (stepsLabel.isBlank()) "✅ Done" else "✅ Done — $stepsLabel"
            }

            ClientStopStatus.CANCELLED -> {
                val reason = row.cancelReason?.replace('_', ' ')?.trim().orEmpty()
                if (reason.isBlank()) "❌ Cancelled" else "❌ Cancelled — $reason"
            }
        }
    }

    private fun formatServiceTypes(serviceTypes: String): String {
        if (serviceTypes.isBlank()) return ""
        val labels = serviceTypes
            .split(",")
            .mapNotNull { token ->
                val normalized = token.trim()
                if (normalized.isBlank()) return@mapNotNull null
                runCatching { ServiceType.valueOf(normalized).label }.getOrElse { normalized }
            }
        return labels.joinToString("+")
    }

    // ─── Week Summary ──────────────────────────────────────────

    /** Show a week summary anchored to [dateMillis]. */
    fun showWeekSummary(dateMillis: Long) {
        viewModelScope.launch {
            when (val result = routeHistoryUseCase.loadWeekSummary(dateMillis)) {
                is RouteHistoryUseCase.WeekResult.Success -> {
                    val text = buildWeekSummaryText(result.weekData)
                    _events.emit(MainEvent.ShowWeekSummary(text))
                }
                is RouteHistoryUseCase.WeekResult.NoHistory -> {
                    setStatus("No activity this week")
                }
                is RouteHistoryUseCase.WeekResult.Error -> {
                    setStatus(result.message)
                }
            }
        }
    }

    private fun buildWeekSummaryText(week: RouteHistoryUseCase.WeekData): String {
        val sb = StringBuilder()
        val startLabel = DateUtils.formatDate(week.startMillis)
        val endLabel = DateUtils.formatDate(week.endMillis - 1) // last day, not day after
        sb.appendLine("Week: $startLabel – $endLabel")
        sb.appendLine("═════════════════════════")

        val activeDays = week.days.filter { it.rows.isNotEmpty() || it.nonClientStops.isNotEmpty() }
        val totalStops = week.days.sumOf { it.rows.size }
        val totalMinutes = week.days.sumOf { day -> day.rows.sumOf { it.durationMinutes } }
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        val durationLabel = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

        sb.appendLine("${activeDays.size} day(s) worked  •  $totalStops stops  •  $durationLabel total")

        // Step totals across the week
        val weekStepCounts = week.days.flatMap { day ->
            day.rows.flatMap { row ->
                row.serviceTypes.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }
        }.groupingBy { it }.eachCount()

        if (weekStepCounts.isNotEmpty()) {
            val parts = weekStepCounts.map { (type, count) ->
                val label = runCatching { ServiceType.valueOf(type).label }.getOrElse { type }
                "$label: $count"
            }
            sb.appendLine(parts.joinToString("  •  "))
        }

        // Days-per-step: how many distinct days included each step
        val daysPerStep = mutableMapOf<String, Int>()
        for (day in week.days) {
            val stepsThisDay = day.rows.flatMap { row ->
                row.serviceTypes.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }.toSet()
            for (step in stepsThisDay) {
                daysPerStep[step] = (daysPerStep[step] ?: 0) + 1
            }
        }
        if (daysPerStep.isNotEmpty()) {
            val dayParts = daysPerStep.map { (type, days) ->
                val label = runCatching { ServiceType.valueOf(type).label }.getOrElse { type }
                "$label: ${days}d"
            }
            sb.appendLine("Days per step: ${dayParts.joinToString("  •  ")}")
        }

        sb.appendLine()

        // Per-day breakdown
        for (day in week.days) {
            val dayLabel = DateUtils.formatDate(day.dateMillis)
            val dayStops = day.rows.size
            val dayNonClient = day.nonClientStops.size
            if (dayStops == 0 && dayNonClient == 0) {
                sb.appendLine("$dayLabel  —  no activity")
            } else {
                val dayMin = day.rows.sumOf { it.durationMinutes }
                val dH = dayMin / 60
                val dM = dayMin % 60
                val dayDur = if (dH > 0) "${dH}h ${dM}m" else "${dM}m"
                sb.appendLine("$dayLabel  —  $dayStops stops  •  $dayDur")
            }
        }

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

    /** Called on init and resume to flush any backed-up writes. */
    fun retryPendingWrites() {
        viewModelScope.launch {
            val result = syncSettingsUseCase.retryPendingWrites()
            if (result.succeeded > 0) {
                setStatus("Retried ${result.succeeded} queued sheet write(s)", emitSnackbar = false)
            }
        }
    }
}
