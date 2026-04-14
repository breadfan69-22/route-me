package com.routeme.app.ui

import android.location.Location
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routeme.app.Client
import com.routeme.app.ClientSuggestion
import com.routeme.app.ClientStopRow
import com.routeme.app.ClientStopStatus
import com.routeme.app.RouteDirection
import com.routeme.app.SavedDestination
import com.routeme.app.ClientProperty
import com.routeme.app.PropertyInput
import com.routeme.app.ProductType
import com.routeme.app.ServiceType
import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.NonClientStop
import com.routeme.app.ActiveArrivalState
import com.routeme.app.TruckInventory
import com.routeme.app.suggestedStepsForDate
import com.routeme.app.TrackingEvent
import com.routeme.app.TrackingEventBus
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.domain.ArrivalUseCase
import com.routeme.app.data.WeatherRepository
import com.routeme.app.data.db.WeekPlanDao
import com.routeme.app.data.db.SavedWeekPlanEntity
import com.routeme.app.domain.DestinationQueueUseCase
import com.routeme.app.domain.MapsExportUseCase
import com.routeme.app.domain.RouteHistoryUseCase
import com.routeme.app.domain.RoutingEngine
import com.routeme.app.domain.ServiceCompletionUseCase
import com.routeme.app.domain.SuggestionUseCase
import com.routeme.app.domain.SyncSettingsUseCase
import com.routeme.app.domain.TruckInventoryUseCase
import com.routeme.app.domain.WeeklyPlannerUseCase
import com.routeme.app.model.RecentWeatherSignal
import com.routeme.app.model.WeekPlan
import com.routeme.app.util.DateUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainViewModel(
    private val clientRepository: ClientRepository,
    private val preferencesRepository: PreferencesRepository,
    private val routingEngine: RoutingEngine,
    private val savedStateHandle: SavedStateHandle,
    private val retryQueue: com.routeme.app.data.WriteBackRetryQueue,
    private val trackingEventBus: TrackingEventBus,
    private val suggestionUseCase: SuggestionUseCase = SuggestionUseCase(routingEngine),
    private val arrivalUseCase: ArrivalUseCase = ArrivalUseCase(routingEngine),
    private val serviceCompletionUseCase: ServiceCompletionUseCase = ServiceCompletionUseCase(clientRepository, retryQueue, preferencesRepository),
    private val truckInventoryUseCase: TruckInventoryUseCase? = null,
    private val destinationQueueUseCase: DestinationQueueUseCase = DestinationQueueUseCase(preferencesRepository, routingEngine),
    private val routeHistoryUseCase: RouteHistoryUseCase = RouteHistoryUseCase(clientRepository),
    private val mapsExportUseCase: MapsExportUseCase = MapsExportUseCase(),
    private val syncSettingsUseCase: SyncSettingsUseCase = SyncSettingsUseCase(clientRepository, preferencesRepository, retryQueue),
    private val weatherRepository: WeatherRepository? = null,
    private val weeklyPlannerUseCase: WeeklyPlannerUseCase? = null,
    private val weekPlanDao: WeekPlanDao? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    companion object {
        private const val KEY_SERVICE_TYPE = "service_type"
        private const val KEY_MIN_DAYS = "min_days"
        private const val KEY_CU_OVERRIDE = "cu_override"
        private const val KEY_ERRANDS_MODE = "errands_mode"
        private const val KEY_ROUTE_DIRECTION = "route_direction"
        private const val KEY_SUGGESTION_OFFSET = "suggestion_offset"
        private const val KEY_SELECTED_CLIENT_ID = "selected_client_id"
        private const val KEY_ARRIVAL_STARTED_AT = "arrival_started_at"
        private const val KEY_ARRIVAL_LAT = "arrival_lat"
        private const val KEY_ARRIVAL_LNG = "arrival_lng"
        private const val KEY_ARRIVAL_WEATHER_TEMP_F = "arrival_weather_temp_f"
        private const val KEY_ARRIVAL_WEATHER_WIND_MPH = "arrival_weather_wind_mph"
        private const val KEY_ARRIVAL_WEATHER_DESC = "arrival_weather_desc"
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
        val fromSavedState = parseServiceTypes(savedStateHandle.get<String>(KEY_SERVICE_TYPE))
        if (fromSavedState != null) return fromSavedState

        // 2. SharedPreferences survives full app close — use if saved today
        val fromPrefs = resolveTodaySavedStepsFromPrefs()
        if (fromPrefs != null) return fromPrefs

        // 3. New day or first launch — auto-select from seasonal date windows
        return suggestedStepsForDate() ?: setOf(ServiceType.ROUND_1)
    }

    private fun resolveTodaySavedStepsFromPrefs(): Set<ServiceType>? {
        val todayEpochDay = System.currentTimeMillis() / 86_400_000L
        val savedDate = preferencesRepository.selectedStepsDate
        if (savedDate != todayEpochDay) return null
        return parseServiceTypes(preferencesRepository.selectedSteps)
    }

    private fun parseServiceTypes(rawValue: String?): Set<ServiceType>? {
        return rawValue
            ?.split(",")
            ?.mapNotNull { runCatching { ServiceType.valueOf(it.trim()) }.getOrNull() }
            ?.toSet()
            ?.ifEmpty { null }
    }

    private fun decodeDestinationQueueFromSavedState(): List<SavedDestination>? {
        return savedStateHandle.get<String>(KEY_DEST_QUEUE)
            ?.takeIf { it.isNotBlank() }
            ?.split("|")
            ?.mapNotNull { entry ->
                val parts = entry.split(",", limit = 5)
                if (parts.size == 5) {
                    SavedDestination(
                        id = parts[0],
                        name = parts[1],
                        address = parts[2],
                        lat = parts[3].toDoubleOrNull() ?: return@mapNotNull null,
                        lng = parts[4].toDoubleOrNull() ?: return@mapNotNull null
                    )
                } else {
                    null
                }
            }
    }

    private fun clampDestinationIndex(queue: List<SavedDestination>, index: Int): Int {
        return when {
            queue.isEmpty() -> 0
            index < 0 -> 0
            index >= queue.size -> queue.lastIndex
            else -> index
        }
    }

    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedServiceTypes = resolveInitialSteps(),
            minDays = savedStateHandle.get<Int>(KEY_MIN_DAYS)
                ?: preferencesRepository.minDays,
            cuOverrideEnabled = savedStateHandle.get<Boolean>(KEY_CU_OVERRIDE)
                ?: preferencesRepository.cuOverrideEnabled,
            errandsModeEnabled = savedStateHandle.get<Boolean>(KEY_ERRANDS_MODE)
                ?: preferencesRepository.errandsModeEnabled,
            routeDirection = savedStateHandle.get<String>(KEY_ROUTE_DIRECTION)
                ?.let { runCatching { RouteDirection.valueOf(it) }.getOrNull() }
                ?: preferencesRepository.routeDirection,
            plannedRouteClientIds = preferencesRepository.plannedRouteClientIds,
            suggestionOffset = savedStateHandle.get<Int>(KEY_SUGGESTION_OFFSET) ?: 0,
            arrivalStartedAtMillis = savedStateHandle.get<Long?>(KEY_ARRIVAL_STARTED_AT),
            arrivalLat = savedStateHandle.get<Double?>(KEY_ARRIVAL_LAT),
            arrivalLng = savedStateHandle.get<Double?>(KEY_ARRIVAL_LNG),
            arrivalWeatherTempF = savedStateHandle.get<Int?>(KEY_ARRIVAL_WEATHER_TEMP_F),
            arrivalWeatherWindMph = savedStateHandle.get<Int?>(KEY_ARRIVAL_WEATHER_WIND_MPH),
            arrivalWeatherDesc = savedStateHandle.get<String?>(KEY_ARRIVAL_WEATHER_DESC),
            isTracking = savedStateHandle.get<Boolean>(KEY_IS_TRACKING) ?: false,
            destinationQueue = decodeDestinationQueueFromSavedState()
                ?: preferencesRepository.destinationQueue,
            activeDestinationIndex = clampDestinationIndex(
                queue = decodeDestinationQueueFromSavedState() ?: preferencesRepository.destinationQueue,
                index = savedStateHandle.get<Int>(KEY_DEST_INDEX)
                    ?: preferencesRepository.destinationQueueActiveIndex
            )
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>()
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    init {
        syncTrackingActiveArrival(_uiState.value)
        loadSyncSettings()
        loadClients()
        loadSavedDestinations()
        refreshGranularInventory()
        // Weather will be fetched once location is available via fetchWeatherAtLocation()
    }

    fun addBagsToTruck(bagsAdded: Int) {
        val useCase = truckInventoryUseCase ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) {
                useCase.addBags(ProductType.GRANULAR, bagsAdded)
            }
            refreshGranularInventory()
            setStatus("Added $bagsAdded bag(s) to truck")
        }
    }

    fun setBagsOnTruck(exactBags: Int) {
        val useCase = truckInventoryUseCase ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) {
                useCase.setStock(ProductType.GRANULAR, exactBags)
            }
            refreshGranularInventory()
            setStatus("Truck inventory set to $exactBags bag(s)")
        }
    }

    fun refreshGranularInventory() {
        val useCase = truckInventoryUseCase ?: return
        viewModelScope.launch {
            val granular = withContext(ioDispatcher) {
                useCase.loadInventory()[ProductType.GRANULAR]
            }
            _uiState.update { state ->
                state.copy(granularInventory = granular?.toInventoryStatus())
            }
        }
    }

    /**
     * Fetch current observation + hourly forecast at the given location.
     * Called by MainActivity once GPS location is available.
     */
    fun fetchWeatherAtLocation(lat: Double, lng: Double) {
        val repo = weatherRepository ?: return
        viewModelScope.launch {
            val isDaytime = com.routeme.app.util.SunCalc.isDaytime(lat, lng)
            
            // Fetch current observation
            val snapshot = withContext(ioDispatcher) {
                runCatching { repo.fetchCurrentSnapshot(lat, lng) }.getOrNull()
            }
            
            // Fetch hourly forecast
            val hourly = withContext(ioDispatcher) {
                runCatching { com.routeme.app.network.NwsWeatherService.fetchNextHourForecast(lat, lng) }.getOrNull()
            }
            
            _uiState.update {
                it.copy(
                    currentWeatherTempF = snapshot?.tempF ?: it.currentWeatherTempF,
                    currentWeatherIconDesc = snapshot?.description ?: it.currentWeatherIconDesc,
                    currentWeatherWindMph = snapshot?.windMph ?: it.currentWeatherWindMph,
                    currentWeatherWindGust = snapshot?.windGustMph ?: it.currentWeatherWindGust,
                    currentWeatherWindDirection = snapshot?.windDirection ?: it.currentWeatherWindDirection,
                    forecastTempF = hourly?.tempF,
                    forecastIconDesc = hourly?.description,
                    forecastWindMph = hourly?.windSpeedMph,
                    forecastWindDirection = hourly?.windDirection,
                    forecastTimeLabel = hourly?.timeLabel,
                    isDaytime = isDaytime
                )
            }
            
            // Background: pre-fetch soil/rain signals to populate cache (fire-and-forget)
            prefetchRecentWeatherSignalsInBackground()
        }
    }

    /** Pre-fetch recent weather signals in the background to populate cache for later use. */
    private fun prefetchRecentWeatherSignalsInBackground() {
        val repo = weatherRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            // Only fetch shop location signal - it will be used as a proxy for nearby clients
            runCatching { repo.getRecentWeatherSignal(SHOP_LAT, SHOP_LNG) }
        }
    }

    /** Toggle between showing current weather and hourly forecast. */
    fun toggleWeatherDisplay() {
        _uiState.update { it.copy(showCurrentWeather = !it.showCurrentWeather) }
    }

    fun loadClients() {
        viewModelScope.launch {
            setLoading(true)
            setStatus("Loading clients…", emitSnackbar = false)
            var shouldEmitLoadComplete = false
            try {
                val result = syncSettingsUseCase.loadClients()
                shouldEmitLoadComplete = handleLoadClientsResult(result)
            } finally {
                setLoading(false)
            }
            // Emit after loading clears so suggestions can run
            if (shouldEmitLoadComplete) {
                _events.emit(MainEvent.SyncComplete)
            }
        }
    }

    private suspend fun handleLoadClientsResult(result: SyncSettingsUseCase.LoadClientsResult): Boolean {
        when (result) {
            is SyncSettingsUseCase.LoadClientsResult.Error -> {
                setStatus(result.message)
                return false
            }

            is SyncSettingsUseCase.LoadClientsResult.Success -> {
                handleLoadClientsSuccess(result)
                return result.clients.isNotEmpty()
            }
        }
    }

    private suspend fun handleLoadClientsSuccess(result: SyncSettingsUseCase.LoadClientsResult.Success) {
        val loadedClients = result.clients
        val restoredSelectedClient = resolveRestoredSelectedClient(loadedClients)

        _uiState.update { current ->
            current.withUpdatedClients(loadedClients).copy(
                selectedClient = restoredSelectedClient,
                selectedClientDetails = restoredSelectedClient?.let(routingEngine::buildClientDetails)
                    ?: current.selectedClientDetails
            )
        }
        val restoredPlannedRoute = restorePlannedRouteAfterClientsLoaded(loadedClients)
        if (!restoredPlannedRoute) {
            persistCriticalState(_uiState.value)
        }
        setStatus(result.statusMessage)
    }

    private fun restorePlannedRouteAfterClientsLoaded(loadedClients: List<Client>): Boolean {
        val plannedIds = preferencesRepository.plannedRouteClientIds
        if (plannedIds.isEmpty()) return false

        val plannedClients = matchClientsByOrderedIds(plannedIds, loadedClients)
        if (plannedClients.isEmpty()) {
            preferencesRepository.plannedRouteClientIds = emptyList()
            _uiState.update { it.copy(plannedRouteClientIds = emptyList()) }
            persistCriticalState(_uiState.value)
            return false
        }

        applyPlannedRouteState(plannedClients, emitStatus = false)
        return true
    }

    private fun resolveRestoredSelectedClient(clients: List<Client>): Client? {
        val restoredSelectedId = savedStateHandle.get<String>(KEY_SELECTED_CLIENT_ID)
        return clients.firstOrNull { it.id == restoredSelectedId }
    }

    fun importClients(uri: Uri) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val result = syncSettingsUseCase.importClients(
                    existingClients = _uiState.value.clients,
                    uri = uri
                )
                handleImportClientsResult(result)
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun handleImportClientsResult(result: SyncSettingsUseCase.ImportClientsResult) {
        when (result) {
            is SyncSettingsUseCase.ImportClientsResult.Error -> {
                setStatus(result.message)
            }

            is SyncSettingsUseCase.ImportClientsResult.Success -> {
                handleImportClientsSuccess(result)
            }
        }
    }

    private suspend fun handleImportClientsSuccess(result: SyncSettingsUseCase.ImportClientsResult.Success) {
        setStatus(result.statusMessage)
        if (result.didImportClients) {
            applyImportedClientsState(result.clients)
        }
    }

    private fun applyImportedClientsState(clients: List<Client>) {
        _uiState.update { it.withUpdatedClients(clients) }
        persistCriticalState(_uiState.value)
    }

    fun syncFromSheets(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSuggestionsLoading = true) }
            setLoading(true)
            setStatus("Syncing from Google Sheets…", emitSnackbar = false)
            var postActions = SyncPostActions()
            try {
                when (val result = syncSettingsUseCase.syncFromSheets(url)) {
                    is SyncSettingsUseCase.SyncFromSheetsResult.Error -> {
                        handleSyncFromSheetsError(result)
                    }

                    is SyncSettingsUseCase.SyncFromSheetsResult.Success -> {
                        postActions = handleSyncFromSheetsSuccess(result)
                    }
                }
            } finally {
                setLoading(false)
                // If sync failed, no SyncComplete will fire so suggestion loading will
                // never be cleared by suggestNextClientsInternal — clear it here instead.
                if (!postActions.shouldEmitSyncComplete) {
                    _uiState.update { it.copy(isSuggestionsLoading = false) }
                }
            }

            emitPostSyncActions(postActions)
        }
    }

    private data class SyncPostActions(
        val shouldEmitSyncComplete: Boolean = false,
        val shouldAutoGeocode: Boolean = false
    )

    private suspend fun handleSyncFromSheetsError(result: SyncSettingsUseCase.SyncFromSheetsResult.Error) {
        setStatus(result.message)
    }

    private suspend fun handleSyncFromSheetsSuccess(
        result: SyncSettingsUseCase.SyncFromSheetsResult.Success
    ): SyncPostActions {
        setStatus(result.statusMessage)
        val syncedClients = result.syncedClients ?: return SyncPostActions()

        applySyncedClientsToState(syncedClients)
        refreshTrackingClientsIfNeeded()

        if (result.newlyAddedClients.isNotEmpty() &&
            com.routeme.app.network.SheetsWriteBack.propertyWebAppUrl.isNotBlank()) {
            for (client in result.newlyAddedClients) {
                runCatching {
                    clientRepository.writeBackAddPropertyClientRow(
                        client.name,
                        client.address
                    )
                }
            }
        }

        if (com.routeme.app.network.SheetsWriteBack.propertyWebAppUrl.isNotBlank()) {
            val propResult = runCatching { syncSettingsUseCase.syncPropertyDataFromSheet() }.getOrNull()
            if (propResult != null && propResult.updated > 0) {
                android.util.Log.d("MainViewModel", propResult.message)
            }
        }

        return SyncPostActions(
            shouldEmitSyncComplete = true,
            shouldAutoGeocode = result.shouldAutoGeocode
        )
    }

    private fun applySyncedClientsToState(syncedClients: List<Client>) {
        _uiState.update { current ->
            current.withUpdatedClients(syncedClients)
                .withClearedArrival()
                .copy(
                    suggestions = emptyList(),
                    suggestionOffset = 0,
                    selectedClient = null,
                    selectedClientDetails = "",
                    isSuggestionsLoading = true
                )
        }
        persistCriticalState(_uiState.value)
    }

    private suspend fun refreshTrackingClientsIfNeeded() {
        if (_uiState.value.isTracking) {
            _events.emit(MainEvent.RefreshTrackingClients)
        }
    }

    private suspend fun emitPostSyncActions(postActions: SyncPostActions) {
        if (postActions.shouldAutoGeocode) {
            geocodeMissingClientCoordinatesInternal(showNoopStatus = false)
        }
        if (postActions.shouldEmitSyncComplete) {
            _events.emit(MainEvent.SyncComplete)
        }
    }

    fun geocodeMissingClientCoordinates() {
        viewModelScope.launch {
            geocodeMissingClientCoordinatesInternal(showNoopStatus = true)
        }
    }

    private suspend fun geocodeMissingClientCoordinatesInternal(showNoopStatus: Boolean) {
            val clients = _uiState.value.clients
            val withoutCoordsCount = syncSettingsUseCase.missingCoordinatesCount(clients)
            if (withoutCoordsCount == 0) {
                if (showNoopStatus) {
                    setStatus("All clients already have coordinates.")
                }
                return
            }

            setLoading(true)
            setStatus("Geocoding ${withoutCoordsCount} client(s)…", emitSnackbar = false)
            try {
                val result = syncSettingsUseCase.geocodeMissingClientCoordinates(clients)
                handleGeocodeResult(result)
            } finally {
                setLoading(false)
            }
    }

    /**
     * Debug: run the aerial estimation pipeline on a single lat/lng and show results
     * via snackbar + logcat. Use this to validate parcel, imagery, and classification
     * before wiring up batch processing or UI.
     */
    fun debugEstimateProperty(lat: Double, lng: Double, clientName: String = "DEBUG") {
        viewModelScope.launch {
            setLoading(true)
            setStatus("Estimating property for $clientName…", emitSnackbar = false)
            try {
                val result = withContext(ioDispatcher) {
                    com.routeme.app.domain.PropertyEstimator.estimate(lat, lng, clientName)
                }
                val msg = buildString {
                    append("${result.confidence}: ${result.lawnSizeSqFt} sqft, ${result.sunShade}")
                    if (result.parcelId != null) append(" — parcel ${result.parcelId}")
                    if (result.assessedAcres != null) append(" (${result.assessedAcres}ac)")
                }
                android.util.Log.i("AerialDebug", "=== Estimation for $clientName ===")
                android.util.Log.i("AerialDebug", msg)
                android.util.Log.i("AerialDebug", "Pixels: turf=${result.turfPixels} tree=${result.treePixels} " +
                    "building=${result.buildingPixels} hardscape=${result.hardscapePixels} " +
                    "other=${result.otherPixels} total=${result.totalParcelPixels}")
                android.util.Log.i("AerialDebug", result.notes)
                setStatus(msg)
            } catch (e: Exception) {
                android.util.Log.e("AerialDebug", "Estimation failed", e)
                setStatus("Aerial estimation failed: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun handleGeocodeResult(result: SyncSettingsUseCase.GeocodeResult) {
        when (result) {
            is SyncSettingsUseCase.GeocodeResult.Error -> {
                setStatus(result.message)
            }

            is SyncSettingsUseCase.GeocodeResult.NoMissingCoordinates -> {
                setStatus(result.statusMessage)
            }

            is SyncSettingsUseCase.GeocodeResult.Success -> {
                handleGeocodeSuccess(result)
            }
        }
    }

    private suspend fun handleGeocodeSuccess(result: SyncSettingsUseCase.GeocodeResult.Success) {
        setStatus(result.statusMessage)
        _uiState.update { it.withUpdatedClients(result.clients) }
        persistCriticalState(_uiState.value)
        refreshTrackingClientsIfNeeded()
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
        preferencesRepository.minDays = minDays
    }

    fun toggleCuOverride() {
        _uiState.update { it.copy(cuOverrideEnabled = !it.cuOverrideEnabled) }
        savedStateHandle[KEY_CU_OVERRIDE] = _uiState.value.cuOverrideEnabled
        preferencesRepository.cuOverrideEnabled = _uiState.value.cuOverrideEnabled
    }

    fun toggleErrandsMode() {
        val enabled = !_uiState.value.errandsModeEnabled
        preferencesRepository.errandsModeEnabled = enabled
        _uiState.update {
            if (enabled) {
                it.copy(
                    errandsModeEnabled = true,
                    suggestions = emptyList(),
                    suggestionOffset = 0,
                    selectedClient = null,
                    selectedClientDetails = "",
                    isSuggestionsLoading = false
                )
            } else {
                it.copy(errandsModeEnabled = false)
            }
        }
        persistCriticalState(_uiState.value)
        viewModelScope.launch {
            setStatus(if (enabled) "Errands Mode enabled" else "Errands Mode disabled")
        }
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
        preferencesRepository.routeDirection = _uiState.value.routeDirection
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

    fun replaceDestinationQueue(queue: List<SavedDestination>, activeDestinationIndex: Int) {
        val result = destinationQueueUseCase.replaceDestinationQueue(
            destinationQueue = queue,
            activeDestinationIndex = activeDestinationIndex
        )
        _uiState.update {
            it.copy(
                destinationQueue = result.destinationQueue,
                activeDestinationIndex = result.activeDestinationIndex
            )
        }
    }

    fun loadPlannedRoute(clients: List<Client>) {
        if (clients.isEmpty()) {
            preferencesRepository.plannedRouteClientIds = emptyList()
            _uiState.update { it.copy(plannedRouteClientIds = emptyList()) }
            viewModelScope.launch { setStatus("No planned stops to load") }
            return
        }
        applyPlannedRouteState(clients, emitStatus = true)
    }

    private fun applyPlannedRouteState(clients: List<Client>, emitStatus: Boolean) {
        val plannedIds = clients.map { it.id }
        val plannedSuggestions = clients.toPlannedRouteSuggestions()
        val firstClient = clients.firstOrNull()

        preferencesRepository.plannedRouteClientIds = plannedIds
        _uiState.update {
            it.copy(
                suggestions = plannedSuggestions,
                plannedRouteClientIds = plannedIds,
                suggestionOffset = 0,
                selectedClient = firstClient,
                selectedClientDetails = firstClient?.let(routingEngine::buildClientDetails) ?: "",
                eligibleClientCount = plannedSuggestions.size
            )
        }
        persistCriticalState(_uiState.value)

        if (emitStatus) {
            viewModelScope.launch {
                setStatus("Planned route: ${plannedSuggestions.size} stops")
            }
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
            val queue = preferencesRepository.destinationQueue
            val activeIndex = clampDestinationIndex(queue, preferencesRepository.destinationQueueActiveIndex)
            val snackbarMessage = if (queue.isEmpty()) {
                "Arrived at $destinationName — all destinations reached"
            } else {
                val next = queue.getOrNull(activeIndex)
                val remainingAfterNext = (queue.size - activeIndex - 1).coerceAtLeast(0)
                "Arrived at $destinationName — next: ${next?.name ?: "(unknown)"} ($remainingAfterNext remaining)"
            }
            _events.emit(MainEvent.ShowSnackbar(snackbarMessage))
            _uiState.update {
                it.copy(
                    destinationQueue = queue,
                    activeDestinationIndex = activeIndex
                )
            }
            persistDestinationState(_uiState.value)
        }
    }

    fun suggestNextClients(currentLocation: Location?) {
        if (checkAndPromptStaleArrival { suggestNextClients(currentLocation) }) return
        viewModelScope.launch {
            suggestNextClientsInternal(currentLocation)
        }
    }

    private suspend fun suggestNextClientsInternal(currentLocation: Location?) {
        val state = _uiState.value
        if (!canSuggestForState(state)) return

        val plannedRouteCleared = clearPlannedRouteForScoringIfNeeded(state)

        _uiState.update { it.copy(isSuggestionsLoading = true) }

        try {
            val result = computeSuggestionResult(currentLocation)
            applySuggestionResult(result)
            if (plannedRouteCleared) {
                setStatus("Planned route cleared. ${result.statusMessage}", emitSnackbar = false)
            }
        } finally {
            clearSuggestionLoadingFlag()
        }
    }

    private fun clearPlannedRouteForScoringIfNeeded(state: MainUiState): Boolean {
        if (state.plannedRouteClientIds.isEmpty()) return false
        preferencesRepository.plannedRouteClientIds = emptyList()
        _uiState.update { it.copy(plannedRouteClientIds = emptyList()) }
        return true
    }

    private suspend fun canSuggestForState(state: MainUiState): Boolean {
        if (state.isLoading) {
            setStatus("Still loading clients, please wait…")
            return false
        }

        if (state.errandsModeEnabled) {
            setStatus("Errands Mode is active")
            return false
        }

        return true
    }

    private suspend fun computeSuggestionResult(
        currentLocation: Location?
    ): SuggestionUseCase.SuggestNextResult {
        val latestState = _uiState.value
        val weatherInputs = loadSuggestionWeatherInputs(latestState.clients)
        val propertyMap = latestState.clients
            .mapNotNull { client -> client.property?.let { client.id to it } }
            .toMap()

        withContext(ioDispatcher) {
            routingEngine.precomputeClusterDrivingDistances(latestState.clients)
        }

        return suggestionUseCase.suggestNextClients(
            clients = latestState.clients,
            selectedServiceTypes = latestState.selectedServiceTypes,
            minDays = latestState.minDays,
            cuOverrideEnabled = latestState.cuOverrideEnabled,
            routeDirection = latestState.routeDirection,
            activeDestination = latestState.activeDestination,
            currentLocation = currentLocation,
            weather = weatherInputs.today,
            recentPrecipInches = weatherInputs.recentPrecipInches,
            propertyMap = propertyMap,
            recentWeatherByClientId = weatherInputs.recentWeatherByClientId
        )
    }

    private data class SuggestionWeatherInputs(
        val today: com.routeme.app.model.DailyWeather?,
        val recentPrecipInches: Double?,
        val recentWeatherByClientId: Map<String, RecentWeatherSignal>
    )

    private suspend fun loadSuggestionWeatherInputs(clients: List<Client>): SuggestionWeatherInputs {
        val repo = weatherRepository ?: return SuggestionWeatherInputs(
            today = null,
            recentPrecipInches = null,
            recentWeatherByClientId = emptyMap()
        )

        // Use cache-only for recent signals (soil/rain) - instant, no network blocking
        // These values don't change fast, so cached data is fine for suggestions
        val perClientSignals = repo.getRecentWeatherSignalsCacheOnly(clients)
        val shopSignal = repo.getRecentWeatherSignalCacheOnly(SHOP_LAT, SHOP_LNG)
        val recentPrecip = shopSignal?.rainLast48hInches

        // Today's forecast can do a quick DB check (no network if missing)
        val today = withContext(ioDispatcher) {
            runCatching { repo.getWeatherForDay(startOfTodayMillis()) }.getOrNull()
        }

        return SuggestionWeatherInputs(
            today = today,
            recentPrecipInches = recentPrecip,
            recentWeatherByClientId = perClientSignals
        )
    }

    private fun startOfTodayMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private suspend fun applySuggestionResult(result: SuggestionUseCase.SuggestNextResult) {
        handleSuggestionDateRollover(result)

        if (result.suggestions.isEmpty()) {
            applyEmptySuggestionResult(result)
            return
        }

        applyNonEmptySuggestionResult(result)
    }

    private fun handleSuggestionDateRollover(result: SuggestionUseCase.SuggestNextResult) {
        if (result.dateRolloverDetected) {
            clearDestinationQueue()
        }
    }

    private suspend fun applyEmptySuggestionResult(result: SuggestionUseCase.SuggestNextResult) {
        _uiState.update {
            it.copy(
                suggestions = emptyList(),
                suggestionOffset = result.suggestionOffset,
                selectedClient = null,
                selectedClientDetails = "",
                eligibleClientCount = 0
            )
        }
        setStatus(result.statusMessage)
    }

    private suspend fun applyNonEmptySuggestionResult(result: SuggestionUseCase.SuggestNextResult) {
        _uiState.update {
            it.copy(
                suggestions = result.suggestions,
                suggestionOffset = result.suggestionOffset,
                selectedClient = result.selectedClient,
                selectedClientDetails = result.selectedClientDetails,
                eligibleClientCount = result.totalEligibleCount
            )
        }
        persistCriticalState(_uiState.value)
        setStatus(result.statusMessage)
        fetchDrivingTimesForCurrentPage()
    }

    private fun clearSuggestionLoadingFlag() {
        _uiState.update { current ->
            if (current.isSuggestionsLoading) {
                current.copy(isSuggestionsLoading = false)
            } else {
                current
            }
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
        _uiState.update { current ->
            current.withClearedArrival().copy(
                selectedClient = suggestion.client,
                selectedClientDetails = routingEngine.buildClientDetails(suggestion.client),
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
        val result = resolveSkipSelectedResult(state)
        if (result == null) {
            emitSkipSelectedMissingClientStatus()
            return
        }

        applySkipSelectedResult(result)
        persistCriticalState(_uiState.value)
        emitSkipSelectedStatus(result.statusMessage)
    }

    private fun resolveSkipSelectedResult(state: MainUiState): SuggestionUseCase.SkipSelectedResult? {
        return suggestionUseCase.skipSelectedClientToday(
            selectedClient = state.selectedClient,
            suggestions = state.suggestions
        )
    }

    private fun emitSkipSelectedMissingClientStatus() {
        viewModelScope.launch { setStatus("Pick a client first") }
    }

    private fun applySkipSelectedResult(result: SuggestionUseCase.SkipSelectedResult) {
        _uiState.update { current ->
            current.withClearedArrival().copy(
                suggestions = result.suggestions,
                suggestionOffset = result.suggestionOffset,
                selectedClient = result.selectedClient,
                selectedClientDetails = result.selectedClientDetails,
            )
        }
    }

    private fun emitSkipSelectedStatus(message: String) {
        viewModelScope.launch {
            setStatus(message)
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
            val originLocation = suggestionOriginLocation()

            if (state.errandsModeEnabled) {
                handleErrandsRouteExport(state, originLocation)
                return@launch
            }

            handleSuggestionRouteExport(state, originLocation)
        }
    }

    private fun suggestionOriginLocation(): MapsExportUseCase.GeoPoint? {
        return suggestionUseCase.lastSuggestionLocation()?.let {
            MapsExportUseCase.GeoPoint(it.latitude, it.longitude)
        }
    }

    private suspend fun handleErrandsRouteExport(
        state: MainUiState,
        originLocation: MapsExportUseCase.GeoPoint?
    ) {
        when (
            val result = mapsExportUseCase.exportErrandsRoute(
                destinationQueue = state.destinationQueue,
                activeDestinationIndex = state.activeDestinationIndex,
                originLocation = originLocation
            )
        ) {
            MapsExportUseCase.ExportResult.NoDestinations,
            MapsExportUseCase.ExportResult.NoSuggestions,
            MapsExportUseCase.ExportResult.NoMappableClients -> {
                setStatus("Add destinations first")
            }

            is MapsExportUseCase.ExportResult.Success -> {
                emitRouteExportSuccess(result.routeExport, routeKind = "errands")
            }
        }
    }

    private suspend fun handleSuggestionRouteExport(
        state: MainUiState,
        originLocation: MapsExportUseCase.GeoPoint?
    ) {
        val result = buildSuggestionRouteExportResult(state, originLocation)
        handleSuggestionRouteExportResult(result)
    }

    private fun buildSuggestionRouteExportResult(
        state: MainUiState,
        originLocation: MapsExportUseCase.GeoPoint?
    ): MapsExportUseCase.ExportResult {
        return mapsExportUseCase.exportTopRoute(
            suggestions = state.suggestions,
            routeDirection = state.routeDirection,
            activeDestination = state.activeDestination,
            originLocation = originLocation
        )
    }

    private suspend fun handleSuggestionRouteExportResult(result: MapsExportUseCase.ExportResult) {
        when (result) {
            MapsExportUseCase.ExportResult.NoMappableClients -> {
                setStatus("No mappable clients in current suggestions")
            }

            MapsExportUseCase.ExportResult.NoSuggestions -> {
                setStatus("Run suggestions first")
            }

            MapsExportUseCase.ExportResult.NoDestinations -> {
                setStatus("Add destinations first")
            }

            is MapsExportUseCase.ExportResult.Success -> {
                emitRouteExportSuccess(result.routeExport, routeKind = "Maps")
            }
        }
    }

    private suspend fun emitRouteExportSuccess(
        routeExport: MapsExportUseCase.RouteExport,
        routeKind: String
    ) {
        _events.emit(MainEvent.OpenMapsRoute(routeExport.uri))

        val clipped = routeExport.requestedStops - routeExport.includedStops
        if (clipped > 0) {
            setStatus("Opened $routeKind route with ${routeExport.includedStops} of ${routeExport.requestedStops} stops")
        } else {
            setStatus("Opened $routeKind route with ${routeExport.includedStops} stops")
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
        val statusMessage = arrivalUseCase.cancelArrival(
            arrivalStartedAtMillis = state.arrivalStartedAtMillis,
            selectedClientName = state.selectedClient?.name
        ) ?: return

        recordCancelledArrivalFromStateIfPresent(state, reason = "manual_cancel")

        clearArrivalState(clearCurrentStopClientName = true)
        arrivalUseCase.resetStaleArrivalSuppression()
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
                handleStaleArrivalConfirmAndContinue(result, currentLocation, visitNotes)
            }
            is ArrivalUseCase.ResolveStaleResult.DiscardAndContinue -> {
                handleStaleArrivalDiscardAndContinue(result)
            }
        }
    }

    private fun handleStaleArrivalConfirmAndContinue(
        result: ArrivalUseCase.ResolveStaleResult.ConfirmAndContinue,
        currentLocation: Location?,
        visitNotes: String
    ) {
        confirmSelectedClientService(currentLocation, visitNotes) {
            result.deferredAction?.invoke()
        }
    }

    private fun handleStaleArrivalDiscardAndContinue(
        result: ArrivalUseCase.ResolveStaleResult.DiscardAndContinue
    ) {
        val state = _uiState.value
        recordCancelledArrivalFromStateIfPresent(state, reason = "stale_discard")
        clearArrivalState(clearCurrentStopClientName = false)
        viewModelScope.launch { setStatus(result.statusMessage) }
        result.deferredAction?.invoke()
    }

    private fun recordCancelledArrivalFromStateIfPresent(state: MainUiState, reason: String) {
        val selectedClient = state.selectedClient ?: return
        val arrivalStartedAtMillis = state.arrivalStartedAtMillis ?: return

        recordCancelledClientStop(
            client = selectedClient,
            arrivedAtMillis = arrivalStartedAtMillis,
            reason = reason,
            location = arrivalLocationFromState(state),
            weatherTempF = state.arrivalWeatherTempF,
            weatherWindMph = state.arrivalWeatherWindMph,
            weatherDesc = state.arrivalWeatherDesc
        )
    }

    private fun arrivalLocationFromState(state: MainUiState): Location? {
        val lat = state.arrivalLat ?: return null
        val lng = state.arrivalLng ?: return null
        return Location("arrival").apply {
            latitude = lat
            longitude = lng
        }
    }

    private fun clearArrivalState(clearCurrentStopClientName: Boolean) {
        _uiState.update { current ->
            if (clearCurrentStopClientName) {
                current.withClearedArrival(currentStopClientName = null)
            } else {
                current.withClearedArrival()
            }
        }
        syncTrackingActiveArrival(_uiState.value)
        clearArrivalSavedState()
    }

    /** Dismiss the stale-arrival dialog and suppress future prompts for this stop. Arrival timer keeps ticking. */
    fun hideStaleArrival() {
        arrivalUseCase.hideStaleArrival()?.invoke()
    }

    fun recordCancelledClientStop(
        client: Client,
        arrivedAtMillis: Long,
        reason: String,
        location: Location? = null,
        weatherTempF: Int? = null,
        weatherWindMph: Int? = null,
        weatherDesc: String? = null
    ) {
        viewModelScope.launch {
            val timing = computeCancelledStopTiming(arrivedAtMillis)
            persistCancelledStopEvent(
                client = client,
                arrivedAtMillis = arrivedAtMillis,
                reason = reason,
                timing = timing,
                location = location,
                weatherTempF = weatherTempF,
                weatherWindMph = weatherWindMph,
                weatherDesc = weatherDesc
            )
        }
    }

    private data class CancelledStopTiming(
        val endedAtMillis: Long,
        val durationMinutes: Long
    )

    private fun computeCancelledStopTiming(arrivedAtMillis: Long): CancelledStopTiming {
        val endedAtMillis = System.currentTimeMillis()
        val elapsedMillis = (endedAtMillis - arrivedAtMillis).coerceAtLeast(0L)
        val durationMinutes = (elapsedMillis / 60_000L).coerceAtLeast(1L)
        return CancelledStopTiming(
            endedAtMillis = endedAtMillis,
            durationMinutes = durationMinutes
        )
    }

    private suspend fun persistCancelledStopEvent(
        client: Client,
        arrivedAtMillis: Long,
        reason: String,
        timing: CancelledStopTiming,
        location: Location?,
        weatherTempF: Int?,
        weatherWindMph: Int?,
        weatherDesc: String?
    ) {
        runCatching {
            clientRepository.saveClientStopEvent(
                clientId = client.id,
                clientName = client.name,
                arrivedAtMillis = arrivedAtMillis,
                endedAtMillis = timing.endedAtMillis,
                durationMinutes = timing.durationMinutes,
                status = ClientStopStatus.CANCELLED,
                cancelReason = reason,
                lat = location?.latitude,
                lng = location?.longitude,
                weatherTempF = weatherTempF,
                weatherWindMph = weatherWindMph,
                weatherDesc = weatherDesc
            )
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
                arrivalLng = arrival.arrivalLng,
                arrivalWeatherTempF = null,
                arrivalWeatherWindMph = null,
                arrivalWeatherDesc = null,
                currentStopClientName = arrival.selectedClient.name
            )
        }
        persistCriticalState(_uiState.value)
        captureArrivalWeatherSnapshot(arrival)
        viewModelScope.launch {
            setStatus(
                "Arrival started for ${arrival.selectedClient.name} at ${DateUtils.formatTimestamp(arrival.arrivalStartedAtMillis)}"
            )
        }
    }

    private fun captureArrivalWeatherSnapshot(arrival: ArrivalUseCase.MarkArrivalResult) {
        val weatherRepo = weatherRepository ?: return
        val clientId = arrival.selectedClient.id
        val startedAt = arrival.arrivalStartedAtMillis

        viewModelScope.launch {
            val snapshot = fetchArrivalWeatherSnapshot(weatherRepo, arrival.arrivalLat, arrival.arrivalLng)
                ?: return@launch
            val updated = applyArrivalWeatherSnapshotIfCurrent(
                clientId = clientId,
                startedAt = startedAt,
                lat = arrival.arrivalLat,
                lng = arrival.arrivalLng,
                tempF = snapshot.tempF,
                windMph = snapshot.windMph,
                description = snapshot.description
            )
            if (updated) {
                persistCriticalState(_uiState.value)
            }
        }
    }

    private suspend fun fetchArrivalWeatherSnapshot(
        weatherRepo: WeatherRepository,
        lat: Double,
        lng: Double
    ) = withContext(ioDispatcher) {
        runCatching {
            weatherRepo.fetchCurrentSnapshot(lat, lng)
        }.getOrNull()
    }

    private fun applyArrivalWeatherSnapshotIfCurrent(
        clientId: String,
        startedAt: Long,
        lat: Double,
        lng: Double,
        tempF: Int?,
        windMph: Int?,
        description: String?
    ): Boolean {
        var updated = false
        val isDaytime = com.routeme.app.util.SunCalc.isDaytime(lat, lng)
        _uiState.update { state ->
            if (!isCurrentArrivalSnapshotTarget(state, clientId, startedAt)) {
                state
            } else {
                updated = true
                stateWithArrivalWeatherSnapshot(
                    state = state,
                    tempF = tempF,
                    windMph = windMph,
                    description = description,
                    isDaytime = isDaytime
                )
            }
        }
        return updated
    }

    private fun isCurrentArrivalSnapshotTarget(
        state: MainUiState,
        clientId: String,
        startedAt: Long
    ): Boolean {
        return state.selectedClient?.id == clientId && state.arrivalStartedAtMillis == startedAt
    }

    private fun stateWithArrivalWeatherSnapshot(
        state: MainUiState,
        tempF: Int?,
        windMph: Int?,
        description: String?,
        isDaytime: Boolean
    ): MainUiState {
        return state.copy(
            arrivalWeatherTempF = tempF,
            arrivalWeatherWindMph = windMph,
            arrivalWeatherDesc = description,
            currentWeatherTempF = tempF,
            currentWeatherIconDesc = description,
            isDaytime = isDaytime
        )
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

    fun getGranularRate(serviceType: ServiceType): Double {
        return preferencesRepository.getGranularRate(serviceType)
    }

    fun buildClientDetailsFor(client: com.routeme.app.Client): String {
        return routingEngine.buildClientDetails(client)
    }

    fun confirmSelectedClientService(
        currentLocation: Location?,
        visitNotes: String = "",
        amountUsed: Double? = null,
        amountUsed2: Double? = null,
        property: PropertyInput = PropertyInput(),
        completedAtMillisOverride: Long? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            val state = _uiState.value
            when (
                val result = serviceCompletionUseCase.confirmSelectedClientService(
                    buildConfirmSelectedRequest(
                        state = state,
                        currentLocation = currentLocation,
                        visitNotes = visitNotes,
                        amountUsed = amountUsed,
                        amountUsed2 = amountUsed2,
                        property = property,
                        completedAtMillisOverride = completedAtMillisOverride
                    )
                )
            ) {
                is ServiceCompletionUseCase.ConfirmSelectedResult.Error -> {
                    setStatus(result.message)
                }

                is ServiceCompletionUseCase.ConfirmSelectedResult.Success -> {
                    handleConfirmSelectedSuccess(result, state, onSuccess)
                }
            }
        }
    }

    /**
     * Write property stats to the Google Sheet for the given client.
     * Independent of the service-completion flow — used from the
     * notification "Property Stats" action.
     */
    fun writePropertyStats(clientName: String, property: PropertyInput) {
        if (!property.hasAnyData) return
        viewModelScope.launch {
            val client = _uiState.value.clients.find { it.name == clientName }
            if (client != null) {
                runCatching {
                    clientRepository.saveClientPropertyInput(client.id, property)
                }
                refreshClientFromRepository(client.id)
            }

            if (com.routeme.app.network.SheetsWriteBack.propertyWebAppUrl.isBlank()) return@launch
            
            // Ensure row exists first - look up address from clients list
            val address = client?.address ?: ""
            if (address.isNotEmpty()) {
                val rowResult = runCatching { clientRepository.writeBackAddPropertyClientRow(clientName, address) }
                if (rowResult.isFailure) {
                    android.util.Log.w("MainViewModel", "Failed to ensure property row: ${rowResult.exceptionOrNull()?.message}")
                }
            }
            
            var anyFailed = false
            if (property.sunShade.isNotEmpty()) {
                val r = runCatching { clientRepository.writeBackPropertyRaw(clientName, "Sun/Shade", property.sunShade) }
                if (r.isFailure) { anyFailed = true; android.util.Log.w("MainViewModel", "Sun/Shade write failed: ${r.exceptionOrNull()?.message}") }
            }
            if (property.windExposure.isNotEmpty()) {
                val r = runCatching { clientRepository.writeBackPropertyRaw(clientName, "Wind Exposure", property.windExposure) }
                if (r.isFailure) { anyFailed = true; android.util.Log.w("MainViewModel", "Wind Exposure write failed: ${r.exceptionOrNull()?.message}") }
            }
            if (property.steepSlopes.isNotEmpty()) {
                val r = runCatching { clientRepository.writeBackPropertyRaw(clientName, "Steep Slopes", property.steepSlopes) }
                if (r.isFailure) { anyFailed = true; android.util.Log.w("MainViewModel", "Steep Slopes write failed: ${r.exceptionOrNull()?.message}") }
            }
            if (property.irrigation.isNotEmpty()) {
                val r = runCatching { clientRepository.writeBackPropertyRaw(clientName, "Irrigation", property.irrigation) }
                if (r.isFailure) { anyFailed = true; android.util.Log.w("MainViewModel", "Irrigation write failed: ${r.exceptionOrNull()?.message}") }
            }
            val cal = java.util.Calendar.getInstance()
            val dateStr = "%d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            runCatching { clientRepository.writeBackPropertyRaw(clientName, "Last Updated", dateStr) }
            
            if (anyFailed) {
                setStatus("Property stats partially saved for $clientName (some writes failed)")
            } else {
                setStatus("Property stats saved for $clientName")
            }
        }
    }

    private suspend fun refreshClientFromRepository(clientId: String) {
        val refreshedClient = runCatching { clientRepository.loadClientById(clientId) }.getOrNull() ?: return

        _uiState.update { state ->
            val updatedClients = state.clients.map { client ->
                if (client.id == clientId) refreshedClient else client
            }
            val updatedSelectedClient = if (state.selectedClient?.id == clientId) refreshedClient else state.selectedClient
            state.withUpdatedClients(updatedClients).copy(
                selectedClient = updatedSelectedClient,
                selectedClientDetails = if (updatedSelectedClient?.id == clientId) {
                    routingEngine.buildClientDetails(updatedSelectedClient)
                } else {
                    state.selectedClientDetails
                }
            )
        }
    }

    private fun buildConfirmSelectedRequest(
        state: MainUiState,
        currentLocation: Location?,
        visitNotes: String,
        amountUsed: Double? = null,
        amountUsed2: Double? = null,
        property: PropertyInput = PropertyInput(),
        completedAtMillisOverride: Long? = null
    ): ServiceCompletionUseCase.ConfirmSelectedRequest {
        return ServiceCompletionUseCase.ConfirmSelectedRequest(
            clients = state.clients,
            selectedClient = state.selectedClient,
            arrivalStartedAtMillis = state.arrivalStartedAtMillis,
            arrivalLat = state.arrivalLat,
            arrivalLng = state.arrivalLng,
            weatherTempF = state.arrivalWeatherTempF,
            weatherWindMph = state.arrivalWeatherWindMph,
            weatherDesc = state.arrivalWeatherDesc,
            completedAtMillisOverride = completedAtMillisOverride,
            selectedSuggestionEligibleSteps = selectedSuggestionEligibleStepsForSelectedClient(state),
            selectedServiceTypes = state.selectedServiceTypes,
            currentLocation = serviceCompletionGeoPoint(currentLocation),
            visitNotes = visitNotes,
            amountUsed = amountUsed,
            amountUsed2 = amountUsed2,
            property = property
        )
    }

    private fun selectedSuggestionEligibleStepsForSelectedClient(state: MainUiState): Set<ServiceType> {
        return state.selectedClient
            ?.let { client -> state.suggestions.find { it.client.id == client.id }?.eligibleSteps }
            ?: emptySet()
    }

    private fun serviceCompletionGeoPoint(location: Location?): ServiceCompletionUseCase.GeoPoint? {
        return location?.let {
            ServiceCompletionUseCase.GeoPoint(it.latitude, it.longitude)
        }
    }

    private suspend fun handleConfirmSelectedSuccess(
        result: ServiceCompletionUseCase.ConfirmSelectedResult.Success,
        stateBeforeConfirm: MainUiState,
        onSuccess: (() -> Unit)?
    ) {
        applyConfirmSelectedState(result, stateBeforeConfirm)
        refreshClientFromRepository(result.selectedClient.id)
        emitConfirmSelectedPrimaryStatus(result)
        emitConfirmSelectedEvents(result)
        emitConfirmSelectedSheetFeedback(result)
        emitPropertyNudgeIfNeeded(result.selectedClient)
        removeClientFromPlannedRoute(result.selectedClient.id)
        removeClientFromSavedPlan(result.selectedClient.id)
        refreshGranularInventory()

        onSuccess?.invoke()
    }

    private fun TruckInventory.toInventoryStatus(): InventoryStatus =
        InventoryStatus(
            current = currentStock,
            capacity = capacity,
            pctRemaining = pctRemaining,
            isLow = isLow
        )

    private fun removeClientFromPlannedRoute(clientId: String) {
        val currentState = _uiState.value
        if (currentState.plannedRouteClientIds.isEmpty()) return

        val remainingIds = currentState.plannedRouteClientIds.filterNot { it == clientId }
        preferencesRepository.plannedRouteClientIds = remainingIds

        _uiState.update { state ->
            val remainingClients = matchClientsByOrderedIds(remainingIds, state.clients)
            val remainingSuggestions = remainingClients.toPlannedRouteSuggestions()
            val selectedClient = remainingClients.firstOrNull()

            state.copy(
                plannedRouteClientIds = remainingIds,
                suggestions = remainingSuggestions,
                suggestionOffset = 0,
                selectedClient = selectedClient,
                selectedClientDetails = selectedClient?.let(routingEngine::buildClientDetails) ?: "",
                eligibleClientCount = remainingSuggestions.size
            )
        }
        persistCriticalState(_uiState.value)
    }

    private fun applyConfirmSelectedState(
        result: ServiceCompletionUseCase.ConfirmSelectedResult.Success,
        stateBeforeConfirm: MainUiState
    ) {
        _uiState.update { current ->
            current.withUpdatedClients(result.updatedClients)
                .withClearedArrival(currentStopClientName = null)
                .copy(
                    selectedClient = result.selectedClient,
                    selectedClientDetails = routingEngine.buildClientDetails(result.selectedClient),
                    currentWeatherTempF = stateBeforeConfirm.arrivalWeatherTempF ?: current.currentWeatherTempF,
                    currentWeatherIconDesc = stateBeforeConfirm.arrivalWeatherDesc ?: current.currentWeatherIconDesc
                )
        }
        persistCriticalState(_uiState.value)
    }

    private suspend fun emitConfirmSelectedPrimaryStatus(
        result: ServiceCompletionUseCase.ConfirmSelectedResult.Success
    ) {
        setStatus(result.statusMessage)
        if (result.retryDrainSucceeded > 0) {
            setStatus("Retried ${result.retryDrainSucceeded} queued sheet write(s)", emitSnackbar = false)
        }
    }

    private suspend fun emitConfirmSelectedEvents(
        result: ServiceCompletionUseCase.ConfirmSelectedResult.Success
    ) {
        _events.emit(MainEvent.ServiceConfirmed)
        _events.emit(
            MainEvent.UndoConfirmation(
                result.selectedClient.name,
                result.selectedClient.id,
                result.finishedAt
            )
        )
    }

    private suspend fun emitConfirmSelectedSheetFeedback(
        result: ServiceCompletionUseCase.ConfirmSelectedResult.Success
    ) {
        result.sheetSnackbarMessage?.let { message ->
            _events.emit(MainEvent.ShowSnackbar(message))
        }
        result.sheetStatusMessage?.let { message ->
            setStatus(message)
        }
    }

    private suspend fun emitPropertyNudgeIfNeeded(client: Client) {
        val prop = client.property ?: run {
            _events.emit(MainEvent.PropertyNudge(client.id, client.name))
            return
        }
        val filledCount = listOf(
            prop.sunShade != com.routeme.app.SunShade.UNKNOWN,
            prop.windExposure != com.routeme.app.WindExposure.UNKNOWN,
            prop.hasSteepSlopes,
            prop.hasIrrigation
        ).count { it }
        if (filledCount <= 1) {
            _events.emit(MainEvent.PropertyNudge(client.id, client.name))
        }
    }

    private fun removeClientFromSavedPlan(clientId: String) {
        val dao = weekPlanDao ?: return
        viewModelScope.launch(ioDispatcher) {
            val entity = dao.loadPlan() ?: return@launch
            val plan = runCatching { WeekPlan.fromJson(org.json.JSONObject(entity.planJson)) }.getOrNull() ?: return@launch
            val updated = plan.copy(
                days = plan.days.map { day ->
                    day.copy(clients = day.clients.filter { it.client.id != clientId })
                }
            )
            dao.savePlan(
                SavedWeekPlanEntity(
                    planJson = updated.toJson().toString(),
                    generatedAtMillis = updated.generatedAtMillis
                )
            )
        }
    }

    /**
     * Batch-confirm a cluster of 2+ clients that were all nearby.
     * [selectedMembers] is the subset the user checked in the dialog.
     */
    fun confirmClusterService(selectedMembers: List<com.routeme.app.ClusterMember>) {
        viewModelScope.launch {
            val state = _uiState.value
            when (
                val result = serviceCompletionUseCase.confirmClusterService(
                    buildConfirmClusterRequest(state, selectedMembers)
                )
            ) {
                is ServiceCompletionUseCase.ConfirmClusterResult.Error -> {
                    setStatus(result.message)
                }

                is ServiceCompletionUseCase.ConfirmClusterResult.Success -> {
                    handleConfirmClusterSuccess(result)
                }
            }
        }
    }

    private fun buildConfirmClusterRequest(
        state: MainUiState,
        selectedMembers: List<com.routeme.app.ClusterMember>
    ): ServiceCompletionUseCase.ConfirmClusterRequest {
        return ServiceCompletionUseCase.ConfirmClusterRequest(
            clients = state.clients,
            selectedServiceTypes = state.selectedServiceTypes,
            suggestionEligibleStepsByClientId = suggestionEligibleStepsByClientId(state),
            selectedMembers = selectedMembers.map(::toClusterMemberInput)
        )
    }

    private fun suggestionEligibleStepsByClientId(state: MainUiState): Map<String, Set<ServiceType>> {
        return state.suggestions.associate { suggestion ->
            suggestion.client.id to suggestion.eligibleSteps
        }
    }

    private fun toClusterMemberInput(member: com.routeme.app.ClusterMember): ServiceCompletionUseCase.ClusterMemberInput {
        return ServiceCompletionUseCase.ClusterMemberInput(
            clientId = member.client.id,
            clientName = member.client.name,
            arrivedAtMillis = member.arrivedAtMillis,
            completedAtMillis = member.completedAtMillis,
            location = ServiceCompletionUseCase.GeoPoint(
                member.location.latitude,
                member.location.longitude
            ),
            weatherTempF = member.weatherTempF,
            weatherWindMph = member.weatherWindMph,
            weatherDesc = member.weatherDesc
        )
    }

    private suspend fun handleConfirmClusterSuccess(
        result: ServiceCompletionUseCase.ConfirmClusterResult.Success
    ) {
        emitClusterTransientFailureStatuses(result)
        applyConfirmClusterState(result)
        emitConfirmClusterCompletion(result)
        result.confirmedIds.forEach { removeClientFromSavedPlan(it) }
    }

    private suspend fun emitClusterTransientFailureStatuses(
        result: ServiceCompletionUseCase.ConfirmClusterResult.Success
    ) {
        result.transientFailureMessages.forEach { message ->
            setStatus(message)
        }
    }

    private fun applyConfirmClusterState(
        result: ServiceCompletionUseCase.ConfirmClusterResult.Success
    ) {
        _uiState.update { current ->
            current.withUpdatedClients(result.updatedClients)
                .withClearedArrival(currentStopClientName = null)
        }
        persistCriticalState(_uiState.value)
    }

    private suspend fun emitConfirmClusterCompletion(
        result: ServiceCompletionUseCase.ConfirmClusterResult.Success
    ) {
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

    /**
     * Undo the most recently confirmed service record.
     * Deletes it from the DB and removes it from the in-memory client records list.
     */
    fun undoLastConfirmation(clientId: String, completedAtMillis: Long) {
        viewModelScope.launch {
            val result = serviceCompletionUseCase.undoLastConfirmation(
                clients = _uiState.value.clients,
                clientId = clientId,
                completedAtMillis = completedAtMillis
            )
            handleUndoLastResult(result, clientId)
        }
    }

    private suspend fun handleUndoLastResult(
        result: ServiceCompletionUseCase.UndoLastResult,
        clientId: String
    ) {
        when (result) {
            is ServiceCompletionUseCase.UndoLastResult.Error -> {
                setStatus(result.message)
            }

            is ServiceCompletionUseCase.UndoLastResult.Success -> {
                applyUndoLastSuccessState(result, clientId)
                setStatus("Undone — record removed")
            }
        }
    }

    private fun applyUndoLastSuccessState(
        result: ServiceCompletionUseCase.UndoLastResult.Success,
        clientId: String
    ) {
        val state = _uiState.value
        _uiState.update {
            it.withUpdatedClients(result.updatedClients).copy(
                selectedClientDetails = if (state.selectedClient?.id == clientId && result.updatedClient != null)
                    routingEngine.buildClientDetails(result.updatedClient) else it.selectedClientDetails
            )
        }
    }

    /**
     * Undo a cluster of service records that were batch-confirmed together.
     */
    fun undoClusterConfirmation(clientIds: List<String>, completedAtMillis: Long) {
        viewModelScope.launch {
            val result = serviceCompletionUseCase.undoClusterConfirmation(
                clients = _uiState.value.clients,
                clientIds = clientIds,
                completedAtMillis = completedAtMillis
            )
            handleUndoClusterResult(result, removedCount = clientIds.size)
        }
    }

    private suspend fun handleUndoClusterResult(
        result: ServiceCompletionUseCase.UndoClusterResult,
        removedCount: Int
    ) {
        when (result) {
            is ServiceCompletionUseCase.UndoClusterResult.Error -> {
                setStatus(result.message)
            }

            is ServiceCompletionUseCase.UndoClusterResult.Success -> {
                applyUndoClusterSuccessState(result)
                setStatus("Undone — ${removedCount} records removed")
            }
        }
    }

    private fun applyUndoClusterSuccessState(result: ServiceCompletionUseCase.UndoClusterResult.Success) {
        _uiState.update {
            it.withUpdatedClients(result.updatedClients)
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
            val request = buildSaveNotesRequest(clientId, notes)
            val result = serviceCompletionUseCase.saveClientNotes(request)
            handleSaveNotesResult(result)
        }
    }

    private fun buildSaveNotesRequest(clientId: String, notes: String): ServiceCompletionUseCase.SaveNotesRequest {
        val state = _uiState.value
        return ServiceCompletionUseCase.SaveNotesRequest(
            clients = state.clients,
            currentSelectedClientId = state.selectedClient?.id,
            clientId = clientId,
            notes = notes
        )
    }

    private suspend fun handleSaveNotesResult(result: ServiceCompletionUseCase.SaveNotesResult) {
        when (result) {
            is ServiceCompletionUseCase.SaveNotesResult.Error -> {
                setStatus(result.message)
            }

            is ServiceCompletionUseCase.SaveNotesResult.Success -> {
                handleSaveNotesSuccess(result)
            }
        }
    }

    private suspend fun handleSaveNotesSuccess(result: ServiceCompletionUseCase.SaveNotesResult.Success) {
        applySaveNotesState(result)
        emitSaveNotesStatusMessages(result)
    }

    private fun applySaveNotesState(result: ServiceCompletionUseCase.SaveNotesResult.Success) {
        _uiState.update {
            it.copy(
                clients = result.updatedClients,
                selectedClient = result.updatedSelectedClient ?: it.selectedClient,
                selectedClientDetails = if (result.updatedSelectedClient != null)
                    routingEngine.buildClientDetails(result.updatedSelectedClient) else it.selectedClientDetails
            )
        }
    }

    private suspend fun emitSaveNotesStatusMessages(result: ServiceCompletionUseCase.SaveNotesResult.Success) {
        setStatus(result.savedStatusMessage)
        result.syncedStatusMessage?.let { message ->
            setStatus(message)
        }
        if (result.retryDrainSucceeded > 0) {
            setStatus("Retried ${result.retryDrainSucceeded} queued sheet write(s)", emitSnackbar = false)
        }
    }

    private suspend fun fetchDrivingTimesForCurrentPage() {
        val location = suggestionUseCase.lastSuggestionLocation() ?: return
        val state = _uiState.value
        val page = resolveCurrentPageNeedingDrivingTimes(state)
        if (page.isEmpty()) return

        setStatus("Fetching driving times...", emitSnackbar = false)
        try {
            fetchAndApplyDrivingTimes(location, page)
            refreshSuggestionsAfterDrivingTimeUpdate()
            emitSelectedClientStatusIfAny()
        } catch (e: Exception) {
            setStatus("Fetch times failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun resolveCurrentPageNeedingDrivingTimes(state: MainUiState): List<com.routeme.app.ClientSuggestion> {
        val page = suggestionUseCase.currentPageSuggestions(
            suggestions = state.suggestions,
            suggestionOffset = state.suggestionOffset
        )
        if (page.isEmpty()) return emptyList()
        if (page.none { it.drivingTime == null }) return emptyList()
        return page
    }

    private suspend fun fetchAndApplyDrivingTimes(location: Location, page: List<com.routeme.app.ClientSuggestion>) {
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
    }

    private fun refreshSuggestionsAfterDrivingTimeUpdate() {
        _uiState.update { it.copy(suggestions = it.suggestions.toList()) }
    }

    private suspend fun emitSelectedClientStatusIfAny() {
        val selected = _uiState.value.selectedClient
        if (selected != null) {
            setStatus("Selected ${selected.name}", emitSnackbar = false)
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

    private fun MainUiState.withUpdatedClients(updatedClients: List<Client>): MainUiState {
        return copy(
            clients = updatedClients,
            summaryText = buildSummaryText(updatedClients),
            completedSteps = computeCompletedSteps(updatedClients)
        )
    }

    private fun matchClientsByOrderedIds(ids: List<String>, clients: List<Client>): List<Client> {
        if (ids.isEmpty()) return emptyList()
        val clientsById = clients.associateBy { it.id }
        return ids.mapNotNull { clientsById[it] }
    }

    private fun List<Client>.toPlannedRouteSuggestions(): List<ClientSuggestion> {
        return map { client ->
            ClientSuggestion(
                client = client,
                daysSinceLast = null,
                distanceMiles = null,
                distanceToShopMiles = null,
                mowWindowPreferred = true
            )
        }
    }

    private fun MainUiState.withClearedArrival(
        currentStopClientName: String? = this.currentStopClientName
    ): MainUiState {
        return copy(
            arrivalStartedAtMillis = null,
            arrivalLat = null,
            arrivalLng = null,
            arrivalWeatherTempF = null,
            arrivalWeatherWindMph = null,
            arrivalWeatherDesc = null,
            currentStopClientName = currentStopClientName
        )
    }

    private fun clearArrivalSavedState() {
        savedStateHandle[KEY_ARRIVAL_STARTED_AT] = null
        savedStateHandle[KEY_ARRIVAL_LAT] = null
        savedStateHandle[KEY_ARRIVAL_LNG] = null
        savedStateHandle[KEY_ARRIVAL_WEATHER_TEMP_F] = null
        savedStateHandle[KEY_ARRIVAL_WEATHER_WIND_MPH] = null
        savedStateHandle[KEY_ARRIVAL_WEATHER_DESC] = null
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
        appendDailySummaryHeader(sb, rows)
        sb.appendLine()
        appendDailyRows(sb, rows)

        appendNonClientStopsSummary(sb, nonClientStops)
        return sb.toString()
    }

    private fun appendDailySummaryHeader(sb: StringBuilder, rows: List<ClientStopRow>) {
        sb.appendLine("Today's Route Summary")
        sb.appendLine("─────────────────────")

        appendDailyTimeWindow(sb, rows)

        val totalStops = rows.size
        val totalMinutes = rows.sumOf { it.durationMinutes }
        sb.appendLine("$totalStops stops  •  ${formatDurationLabel(totalMinutes)} total")
    }

    private fun appendDailyTimeWindow(sb: StringBuilder, rows: List<ClientStopRow>) {
        val firstArrival = rows.mapNotNull { it.arrivedAtMillis }.minOrNull()
        val lastEnd = rows.maxOfOrNull { it.endedAtMillis }
        if (firstArrival != null && lastEnd != null) {
            sb.appendLine("${DateUtils.formatTime(firstArrival)} – ${DateUtils.formatTime(lastEnd)}")
        }
    }

    private fun appendDailyRows(sb: StringBuilder, rows: List<ClientStopRow>) {
        rows.forEachIndexed { index, row ->
            appendDailyRow(sb, index, row, rows.lastIndex)
        }
    }

    private fun appendDailyRow(sb: StringBuilder, index: Int, row: ClientStopRow, lastIndex: Int) {
        val timeStr = row.arrivedAtMillis?.let { DateUtils.formatTime(it) } ?: "—"
        val endStr = DateUtils.formatTime(row.endedAtMillis)
        sb.appendLine("${index + 1}. ${row.clientName}")
        sb.appendLine("   ${formatClientStopDetail(row)}")
        sb.appendLine("   $timeStr → $endStr  (${row.durationMinutes}m)")
        if (row.notes.isNotBlank()) {
            sb.appendLine("   \uD83D\uDCDD ${row.notes}")
        }
        if (index < lastIndex) sb.appendLine()
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

            else -> {
                handleRouteHistoryNonSuccess(result)
            }
        }
    }

    private suspend fun handleRouteHistoryNonSuccess(result: RouteHistoryUseCase.HistoryResult) {
        val message = routeHistoryStatusMessage(result)
        if (message != null) {
            setStatus(message)
        }
    }

    private fun routeHistoryStatusMessage(result: RouteHistoryUseCase.HistoryResult): String? {
        return when (result) {
            is RouteHistoryUseCase.HistoryResult.Error -> {
                result.message
            }

            is RouteHistoryUseCase.HistoryResult.NoRecordsForDate -> {
                "No records for ${DateUtils.formatDate(result.dateMillis)}"
            }

            RouteHistoryUseCase.HistoryResult.NoHistory -> {
                "No route history yet"
            }

            RouteHistoryUseCase.HistoryResult.NoRecordsForRequestedDate -> {
                "No records for that date"
            }

            RouteHistoryUseCase.HistoryResult.NavigationUnavailable,
            is RouteHistoryUseCase.HistoryResult.Success -> null
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
        appendHistorySummaryHeader(sb, rows)
        appendHistoryStepBreakdown(sb, rows)
        sb.appendLine()
        appendHistoryRows(sb, rows)

        appendNonClientStopsSummary(sb, nonClientStops)
        return sb.toString()
    }

    private fun appendHistorySummaryHeader(sb: StringBuilder, rows: List<ClientStopRow>) {
        val totalStops = rows.size
        val totalMinutes = rows.sumOf { it.durationMinutes }
        sb.appendLine("$totalStops stops  •  ${formatDurationLabel(totalMinutes)} total")
    }

    private fun appendHistoryStepBreakdown(sb: StringBuilder, rows: List<ClientStopRow>) {
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
    }

    private fun appendHistoryRows(sb: StringBuilder, rows: List<ClientStopRow>) {
        rows.forEachIndexed { index, row ->
            appendHistoryRow(sb, index, row, rows.lastIndex)
        }
    }

    private fun appendHistoryRow(sb: StringBuilder, index: Int, row: ClientStopRow, lastIndex: Int) {
        val timeStr = row.arrivedAtMillis?.let { DateUtils.formatTime(it) } ?: "—"
        val endStr = DateUtils.formatTime(row.endedAtMillis)
        sb.appendLine("${index + 1}. ${row.clientName}")
        sb.appendLine("   ${formatClientStopDetail(row)}")
        formatHistoryWeatherDetail(row)?.let { weatherLine ->
            sb.appendLine("   $weatherLine")
        }
        sb.appendLine("   $timeStr → $endStr  (${row.durationMinutes}m)")
        if (row.notes.isNotBlank()) {
            sb.appendLine("   \uD83D\uDCDD ${row.notes}")
        }
        if (index < lastIndex) sb.appendLine()
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

    private fun formatHistoryWeatherDetail(row: ClientStopRow): String? {
        val parts = mutableListOf<String>()
        row.weatherTempF?.let { parts += "${it}°F" }
        row.weatherWindMph?.let { parts += "Wind ${it} mph" }
        row.weatherDesc?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (parts.isEmpty()) return null
        return "🌦️ ${parts.joinToString("  •  ")}"
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

    /** Generate and show a weather-aware plan for the next 7 days. */
    fun showWeeklyPlanner(dayAnchors: Map<Int, com.routeme.app.domain.DayAnchor> = emptyMap()) {
        val planner = weeklyPlannerUseCase ?: run {
            viewModelScope.launch { setStatus("Weekly planner is not available") }
            return
        }

        viewModelScope.launch {
            setStatus("Generating weekly weather plan…", emitSnackbar = false)
            val state = _uiState.value
            val selectedTypes = state.selectedServiceTypes.ifEmpty { ServiceType.entries.toSet() }
            val result = runCatching {
                planner.generateWeekPlan(
                    serviceTypes = selectedTypes,
                    minDays = state.minDays,
                    dayAnchors = dayAnchors
                )
            }

            result
                .onSuccess { weekPlan ->
                    _events.emit(MainEvent.ShowWeeklyPlanner(weekPlan))
                }
                .onFailure { error ->
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
                    setStatus("Failed to generate weekly planner: $message")
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
        val durationLabel = formatDurationLabel(totalMinutes)

        sb.appendLine("${activeDays.size} day(s) worked  •  $totalStops stops  •  $durationLabel total")

        appendWeekStepTotals(sb, week)
        appendWeekDaysPerStep(sb, week)

        sb.appendLine()
        appendWeekDailyBreakdown(sb, week)

        return sb.toString()
    }

    private fun buildWeeklyPlannerText(plan: WeekPlan): String {
        val sb = StringBuilder()
        sb.appendLine("Weekly Planner (Weather-Aware)")
        sb.appendLine("════════════════════════════")
        sb.appendLine("Generated: ${DateUtils.formatTimestamp(plan.generatedAtMillis)}")
        sb.appendLine("Clients: ${plan.totalClients}  •  Unassigned: ${plan.unassignedCount}")
        sb.appendLine()

        for (day in plan.days) {
            appendPlannedDaySummary(sb, day)
            sb.appendLine()
        }

        if (plan.noteOnlyClients.isNotEmpty()) {
            sb.appendLine("─────────────────────────────")
            sb.appendLine("Pending Notes")
            for (client in plan.noteOnlyClients.sortedBy { it.name }) {
                sb.appendLine("  • ${client.name}: ${client.notes.trim()}")
            }
        }

        return sb.toString().trimEnd()
    }

    private fun appendPlannedDaySummary(sb: StringBuilder, day: com.routeme.app.model.PlannedDay) {
        val icon = if (day.isWorkDay) "✅" else "⛔"
        val dateLabel = DateUtils.formatDate(day.dateMillis)
        sb.appendLine("$icon ${day.dayName} ($dateLabel)  •  ${day.dayScoreLabel} (${day.dayScore})")
        sb.appendLine("   ${formatPlannedDayWeather(day)}")

        if (!day.isWorkDay) {
            sb.appendLine("   Not scheduled as work day")
            return
        }

        if (day.clients.isEmpty()) {
            sb.appendLine("   No assignments")
            return
        }

        day.clients.forEachIndexed { index, planned ->
            val overdue = planned.daysOverdue?.let { " • ${it}d overdue" } ?: ""
            sb.appendLine(
                "   ${index + 1}. ${planned.client.name} — ${planned.fitnessLabel} (${planned.fitnessScore})$overdue"
            )
            sb.appendLine("      ${planned.primaryReason}")
        }
    }

    private fun formatPlannedDayWeather(day: com.routeme.app.model.PlannedDay): String {
        val forecast = day.forecast ?: return "Forecast unavailable"
        val wind = forecast.windGustMph ?: forecast.windSpeedMph
        return "H/L ${forecast.highTempF}°/${forecast.lowTempF}° • Rain ${forecast.precipProbabilityPct}% • Wind ${wind} mph"
    }

    private fun appendWeekStepTotals(sb: StringBuilder, week: RouteHistoryUseCase.WeekData) {
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
    }

    private fun appendWeekDaysPerStep(sb: StringBuilder, week: RouteHistoryUseCase.WeekData) {
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
    }

    private fun appendWeekDailyBreakdown(sb: StringBuilder, week: RouteHistoryUseCase.WeekData) {
        for (day in week.days) {
            val dayLabel = DateUtils.formatDate(day.dateMillis)
            val dayStops = day.rows.size
            val dayNonClient = day.nonClientStops.size
            if (dayStops == 0 && dayNonClient == 0) {
                sb.appendLine("$dayLabel  —  no activity")
            } else {
                val dayMin = day.rows.sumOf { it.durationMinutes }
                sb.appendLine("$dayLabel  —  $dayStops stops  •  ${formatDurationLabel(dayMin)}")
            }
        }
    }

    private fun formatDurationLabel(totalMinutes: Long): String {
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    /** Format non-client stops split into destination stops and breaks. */
    private fun appendNonClientStopsSummary(sb: StringBuilder, nonClientStops: List<NonClientStop>) {
        val destinationStops = nonClientStops.filter { it.label != null }
        val breakStops = nonClientStops.filter { it.label == null }

        if (destinationStops.isNotEmpty()) {
            appendDestinationStopsSection(sb, destinationStops)
        }

        if (breakStops.isNotEmpty()) {
            appendBreakStopsSection(sb, breakStops)
        }
    }

    private fun appendDestinationStopsSection(sb: StringBuilder, destinationStops: List<NonClientStop>) {
        appendNonClientSectionHeader(sb, "Destination Stops \uD83D\uDCE6")
        val totalMinutes = destinationStops.sumOf { it.durationMinutes }
        sb.appendLine("${destinationStops.size} destination(s)  •  ${formatDurationLabel(totalMinutes)} total")
        sb.appendLine()

        destinationStops.forEachIndexed { index, stop ->
            val name = stop.label ?: "Destination"
            appendNonClientStopRow(sb, "\uD83D\uDCCD $name", stop)
            if (index < destinationStops.size - 1) sb.appendLine()
        }
    }

    private fun appendBreakStopsSection(sb: StringBuilder, breakStops: List<NonClientStop>) {
        appendNonClientSectionHeader(sb, "Breaks / Non-Client Stops")
        val totalMinutes = breakStops.sumOf { it.durationMinutes }
        sb.appendLine("${breakStops.size} break(s)  •  ${formatDurationLabel(totalMinutes)} total")
        sb.appendLine()

        breakStops.forEachIndexed { index, stop ->
            val address = stop.address ?: "Unknown location"
            appendNonClientStopRow(sb, "☕ $address", stop)
            if (index < breakStops.size - 1) sb.appendLine()
        }
    }

    private fun appendNonClientSectionHeader(sb: StringBuilder, title: String) {
        sb.appendLine()
        sb.appendLine("┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄")
        sb.appendLine(title)
        sb.appendLine()
    }

    private fun appendNonClientStopRow(sb: StringBuilder, title: String, stop: NonClientStop) {
        val arriveStr = DateUtils.formatTime(stop.arrivedAtMillis)
        val departStr = stop.departedAtMillis?.let { DateUtils.formatTime(it) } ?: "ongoing"
        sb.appendLine(title)
        sb.appendLine("   $arriveStr → $departStr  (${stop.durationMinutes}m)")
    }

    private fun persistCriticalState(state: MainUiState) {
        persistSelectionState(state)
        persistArrivalState(state)
        persistTrackingState(state)
        persistDestinationState(state)
        syncTrackingActiveArrival(state)
    }

    private fun syncTrackingActiveArrival(state: MainUiState) {
        val selectedClient = state.selectedClient
        val startedAtMillis = state.arrivalStartedAtMillis
        val lat = state.arrivalLat
        val lng = state.arrivalLng

        val activeArrival = if (
            selectedClient != null &&
            startedAtMillis != null &&
            lat != null &&
            lng != null
        ) {
            ActiveArrivalState(
                clientId = selectedClient.id,
                clientName = selectedClient.name,
                arrivedAtMillis = startedAtMillis,
                lat = lat,
                lng = lng
            )
        } else {
            null
        }

        trackingEventBus.setActiveArrival(activeArrival)
    }

    private fun persistSelectionState(state: MainUiState) {
        savedStateHandle[KEY_SERVICE_TYPE] = state.selectedServiceTypes.joinToString(",") { it.name }
        savedStateHandle[KEY_MIN_DAYS] = state.minDays
        savedStateHandle[KEY_CU_OVERRIDE] = state.cuOverrideEnabled
        savedStateHandle[KEY_ERRANDS_MODE] = state.errandsModeEnabled
        savedStateHandle[KEY_ROUTE_DIRECTION] = state.routeDirection.name
        savedStateHandle[KEY_SUGGESTION_OFFSET] = state.suggestionOffset
        savedStateHandle[KEY_SELECTED_CLIENT_ID] = state.selectedClient?.id
    }

    private fun persistArrivalState(state: MainUiState) {
        savedStateHandle[KEY_ARRIVAL_STARTED_AT] = state.arrivalStartedAtMillis
        savedStateHandle[KEY_ARRIVAL_LAT] = state.arrivalLat
        savedStateHandle[KEY_ARRIVAL_LNG] = state.arrivalLng
        savedStateHandle[KEY_ARRIVAL_WEATHER_TEMP_F] = state.arrivalWeatherTempF
        savedStateHandle[KEY_ARRIVAL_WEATHER_WIND_MPH] = state.arrivalWeatherWindMph
        savedStateHandle[KEY_ARRIVAL_WEATHER_DESC] = state.arrivalWeatherDesc
    }

    private fun persistTrackingState(state: MainUiState) {
        savedStateHandle[KEY_IS_TRACKING] = state.isTracking
    }

    private fun persistDestinationState(state: MainUiState) {
        savedStateHandle[KEY_DEST_QUEUE] = state.destinationQueue.joinToString("|") {
            "${it.id},${it.name},${it.address},${it.lat},${it.lng}"
        }
        savedStateHandle[KEY_DEST_INDEX] = state.activeDestinationIndex
        preferencesRepository.destinationQueue = state.destinationQueue
        preferencesRepository.destinationQueueActiveIndex = state.activeDestinationIndex
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
