package com.routeme.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import android.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.routeme.app.databinding.ActivityMainBinding
import com.routeme.app.databinding.SectionStatusNotesBinding
import com.routeme.app.databinding.SectionStepControlsBinding
import com.routeme.app.databinding.SectionSuggestionsBinding
import com.routeme.app.databinding.SectionTrackingActionsBinding
import com.routeme.app.network.DistanceMatrixHelper
import com.routeme.app.network.GeocodingHelper
import com.routeme.app.network.SheetsWriteBack
import com.routeme.app.ui.DestinationInputController
import com.routeme.app.ui.DialogFactory
import com.routeme.app.ui.EventObserver
import com.routeme.app.ui.MainEvent
import com.routeme.app.ui.MainViewModel
import com.routeme.app.ui.SuggestionUiController
import com.routeme.app.ui.TrackingUiController
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : AppCompatActivity() {
    private var clients = mutableListOf<Client>()
    private var selectedClient: Client? = null
    private var lastLocation: Location? = null
    private lateinit var binding: ActivityMainBinding
    private lateinit var stepControlsBinding: SectionStepControlsBinding
    private lateinit var trackingActionsBinding: SectionTrackingActionsBinding
    private lateinit var suggestionsBinding: SectionSuggestionsBinding
    private lateinit var statusNotesBinding: SectionStatusNotesBinding
    private lateinit var suggestionUiController: SuggestionUiController
    private lateinit var trackingUiController: TrackingUiController
    private lateinit var destinationInputController: DestinationInputController
    private lateinit var eventObserver: EventObserver

    private val viewModel: MainViewModel by viewModel()
    private val trackingEventBus: TrackingEventBus by inject()
    private var sheetsUrl: String = ""
    private val SUGGEST_LOCATION_MAX_AGE_MS = 120_000L

    /** Tracks which client IDs we already showed an arrival dialog for this session
     *  so we don't nag repeatedly. Resets when tracking is stopped. */
    private val arrivedClientIds = mutableSetOf<String>()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        viewModel.importClients(uri)
    }

    // ─── Destination input launchers ───────────────────────────

    private val voiceInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (::destinationInputController.isInitialized) {
            destinationInputController.onVoiceInputResult(result.resultCode, result.data)
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (::destinationInputController.isInitialized) {
            destinationInputController.onCameraCaptureResult(success)
        }
    }

    private val destinationsScreenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val queueResult = DestinationsActivity.extractQueueResult(result.data) ?: return@registerForActivityResult
        viewModel.replaceDestinationQueue(
            queue = queueResult.destinationQueue,
            activeDestinationIndex = queueResult.activeDestinationIndex
        )
        rerunSuggestionsIfVisible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        stepControlsBinding = SectionStepControlsBinding.bind(binding.root)
        trackingActionsBinding = SectionTrackingActionsBinding.bind(binding.root)
        suggestionsBinding = SectionSuggestionsBinding.bind(binding.root)
        statusNotesBinding = SectionStatusNotesBinding.bind(binding.root)
        suggestionUiController = SuggestionUiController(suggestionsBinding, viewModel)
        trackingUiController = TrackingUiController(
            activity = this,
            viewModel = viewModel,
            trackingEventBus = trackingEventBus,
            onTrackingSessionReset = { arrivedClientIds.clear() }
        )
        destinationInputController = DestinationInputController(
            activity = this,
            binding = binding,
            viewModel = viewModel,
            lifecycleScope = lifecycleScope,
            launchVoiceRecognizer = { intent -> voiceInputLauncher.launch(intent) },
            launchCameraCapture = { uri -> cameraLauncher.launch(uri) },
            getLastLocation = { lastLocation },
            rerunSuggestionsIfVisible = ::rerunSuggestionsIfVisible,
            launchDestinationsScreen = ::launchDestinationsScreen
        )
        eventObserver = EventObserver(
            lifecycleOwner = this,
            lifecycleScope = lifecycleScope,
            viewModel = viewModel,
            trackingEventBus = trackingEventBus,
            onMainEvent = ::handleMainEvent,
            onTrackingEvent = ::handleTrackingEvent
        )

        setSupportActionBar(binding.topToolbar)

        setupStepToggle()
        setupActions()
        observeViewModel()
        eventObserver.start()
        handleArrivalIntent(intent)

        // Set up Distance Matrix API key
        DistanceMatrixHelper.apiKey = BuildConfig.MAPS_API_KEY
        GeocodingHelper.apiKey = BuildConfig.MAPS_API_KEY

    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        clients.clear()
                        clients.addAll(state.clients)
                        selectedClient = state.selectedClient
                        sheetsUrl = state.sheetsReadUrl
                        if (state.sheetsWriteUrl != SheetsWriteBack.webAppUrl) {
                            SheetsWriteBack.webAppUrl = state.sheetsWriteUrl
                        }
                        if (state.summaryText.isNotBlank()) {
                            binding.summaryText.text = state.summaryText
                        }
                        if (state.statusText.isNotBlank()) {
                            statusNotesBinding.statusText.text = state.statusText
                        }
                        if (state.selectedClientDetails.isNotBlank()) {
                            statusNotesBinding.clientDetailsText.text = state.selectedClientDetails
                        }
                        trackingActionsBinding.trackingButton.text = if (state.isTracking) {
                            getString(R.string.stop_tracking)
                        } else {
                            getString(R.string.start_tracking)
                        }
                        statusNotesBinding.arrivedButton.text = if (state.arrivalStartedAtMillis != null) {
                            getString(R.string.btn_cancel)
                        } else {
                            getString(R.string.btn_arrived)
                        }
                        statusNotesBinding.visitNotesLayout.visibility = if (state.arrivalStartedAtMillis != null) View.VISIBLE else View.GONE
                        if (state.arrivalStartedAtMillis == null) {
                            statusNotesBinding.visitNotesInput.text?.clear()
                        }
                        updateStepToggleEnabled(state.completedSteps)
                        val activeDest = state.activeDestination
                        if (activeDest != null) {
                            binding.destinationBanner.text = "\uD83D\uDCCD Heading toward: ${activeDest.name}"
                            binding.destinationBanner.visibility = View.VISIBLE
                        } else {
                            binding.destinationBanner.visibility = View.GONE
                        }
                        showCurrentPage()
                    }
                }
            }
        }
    }

    private fun handleMainEvent(event: MainEvent) {
        when (event) {
            is MainEvent.ShowSnackbar -> {
                Snackbar.make(binding.root, event.message, Snackbar.LENGTH_SHORT).show()
            }

            is MainEvent.OpenMapsRoute -> {
                openMapsUri(Uri.parse(event.uri))
            }

            is MainEvent.ShowDailySummary -> {
                showDailySummaryDialog(event.summary)
            }

            is MainEvent.StaleArrivalPrompt -> {
                showStaleArrivalDialog(event.clientName, event.minutesElapsed)
            }

            is MainEvent.ClusterCompletePrompt -> {
                showClusterCompletionDialog(event.members)
            }

            is MainEvent.UndoConfirmation -> {
                Snackbar.make(binding.root, "Confirmed ${event.clientName}", Snackbar.LENGTH_LONG)
                    .setDuration(8000)
                    .setAction("UNDO") {
                        viewModel.undoLastConfirmation(event.clientId, event.recordCompletedAtMillis)
                    }
                    .show()
            }

            is MainEvent.UndoClusterConfirmation -> {
                val label = "Confirmed ${event.clientNames.size} stops"
                Snackbar.make(binding.root, label, Snackbar.LENGTH_LONG)
                    .setDuration(8000)
                    .setAction("UNDO") {
                        viewModel.undoClusterConfirmation(event.clientIds, event.recordCompletedAtMillis)
                    }
                    .show()
            }

            is MainEvent.EditClientNotes -> {
                showEditNotesDialog(event.clientId, event.clientName, event.currentNotes)
            }

            is MainEvent.ShowRouteHistory -> {
                showRouteHistoryDialog(event)
            }

            is MainEvent.ShowWeekSummary -> {
                showWeekSummaryDialog(event.summary)
            }

            MainEvent.ServiceConfirmed -> Unit
        }
    }

    private fun handleTrackingEvent(event: TrackingEvent) {
        when (event) {
            is TrackingEvent.ClientArrival -> {
                showArrivalDialog(event.client, event.arrivedAtMillis, event.location)
            }

            is TrackingEvent.JobComplete -> {
                showCompletionDialog(event.client, event.timeOnSiteMillis, event.arrivedAtMillis, event.location)
            }

            is TrackingEvent.ClusterComplete -> {
                showClusterCompletionDialog(event.members)
            }

            is TrackingEvent.DestinationReached -> {
                viewModel.onDestinationReached(event.destinationName)
            }

            is TrackingEvent.LocationUpdated -> Unit
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleArrivalIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Sync tracking button state from actual service state
        trackingUiController.syncTrackingState()
        // Handle any pending notification tap (e.g. app was killed and relaunched)
        handleArrivalIntent(intent)
        // Flush any backed-up sheet writes
        viewModel.retryPendingWrites()
    }

    override fun onPause() {
        super.onPause()
    }

    /** Map each toggle button ID to its ServiceType. */
    private val toggleButtonToServiceType: Map<Int, ServiceType> by lazy {
        mapOf(
            R.id.btnStep1 to ServiceType.ROUND_1,
            R.id.btnStep2 to ServiceType.ROUND_2,
            R.id.btnStep3 to ServiceType.ROUND_3,
            R.id.btnStep4 to ServiceType.ROUND_4,
            R.id.btnStep5 to ServiceType.ROUND_5,
            R.id.btnStep6 to ServiceType.ROUND_6,
            R.id.btnStepGrub to ServiceType.GRUB,
            R.id.btnStepInc to ServiceType.INCIDENTAL
        )
    }

    /** Reverse map: ServiceType → button ID. */
    private val serviceTypeToButtonId: Map<ServiceType, Int> by lazy {
        toggleButtonToServiceType.entries.associate { (id, type) -> type to id }
    }

    /** Read which service types are currently toggled on. */
    private fun getSelectedServiceTypes(): Set<ServiceType> {
        val checked = stepControlsBinding.stepToggleGroup.checkedButtonIds
        val types = checked.mapNotNull { toggleButtonToServiceType[it] }.toSet()
        return types.ifEmpty { setOf(ServiceType.ROUND_1) } // fallback
    }

    /** Build a short label like "S1+2" from the currently selected toggles. */
    private fun getSelectedStepsLabel(): String {
        val types = getSelectedServiceTypes()
        if (types.size == 1) return types.first().label
        return types.sortedBy { it.stepNumber }.joinToString("+") { it.label }
    }

    private fun setupStepToggle() {
        // Restore selection from ViewModel (which resolves from savedState / prefs / date windows)
        val savedTypes = viewModel.uiState.value.selectedServiceTypes
        // Clear all first, then check the right ones
        stepControlsBinding.stepToggleGroup.clearChecked()
        for (type in savedTypes) {
            serviceTypeToButtonId[type]?.let { stepControlsBinding.stepToggleGroup.check(it) }
        }
    }

    /** Disable and grey out toggle buttons for steps that are fully completed. */
    private fun updateStepToggleEnabled(completedSteps: Set<ServiceType>) {
        for ((type, buttonId) in serviceTypeToButtonId) {
            val button = findViewById<View>(buttonId) ?: continue
            val isCompleted = type in completedSteps
            button.isEnabled = !isCompleted
            button.alpha = if (isCompleted) 0.35f else 1.0f
        }
    }

    private fun setupActions() {
        suggestionUiController.bindPaginationActions()

        trackingActionsBinding.trackingButton.setOnClickListener {
            trackingUiController.toggleTracking()
        }

        trackingActionsBinding.suggestButton.setOnClickListener {
            viewModel.setServiceTypes(getSelectedServiceTypes())
            suggestNextClients()
        }

        statusNotesBinding.mapsButton.setOnClickListener {
            openSelectedInMaps()
        }

        statusNotesBinding.arrivedButton.setOnClickListener {
            if (viewModel.uiState.value.arrivalStartedAtMillis != null) {
                viewModel.cancelArrival()
                return@setOnClickListener
            }

            lastLocation = getCurrentLocation()
            viewModel.startArrivalForSelected(lastLocation)
        }

        statusNotesBinding.confirmButton.setOnClickListener {
            confirmSelectedClientService()
        }

        statusNotesBinding.skipButton.setOnClickListener {
            viewModel.skipSelectedClientToday()
        }
    }

    private fun suggestNextClients() {
        // Prefer a fresh location fix so ranking reflects current position.
        lastLocation = getBestSuggestionLocation() ?: lastLocation
        viewModel.suggestNextClients(lastLocation)
    }

    private fun showCurrentPage() {
        suggestionUiController.showCurrentPage()
    }

    private fun openSelectedInMaps() {
        val client = selectedClient
        if (client == null) {
            viewModel.postStatus(getString(R.string.status_pick_client))
            return
        }

        val uri = if (client.latitude != null && client.longitude != null) {
            Uri.parse("google.navigation:q=${client.latitude},${client.longitude}")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(client.address)}")
        }

        openMapsUri(uri)
    }

    private fun openMapsUri(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.apps.maps")

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun confirmSelectedClientService() {
        val current = getCurrentLocation()
        lastLocation = current
        viewModel.setServiceTypes(getSelectedServiceTypes())
        val notes = statusNotesBinding.visitNotesInput.text?.toString().orEmpty()
        viewModel.confirmSelectedClientService(current, notes)
    }

    private fun showDailySummaryDialog(summary: String) {
        DialogFactory.showDailySummaryDialog(this, summary)
    }

    private fun showRouteHistoryDialog(event: MainEvent.ShowRouteHistory) {
        DialogFactory.showRouteHistoryDialog(
            context = this,
            event = event,
            onNavigate = { dateMillis, delta ->
                viewModel.navigateHistory(dateMillis, delta)
            },
            onPickDate = { dateMillis ->
                viewModel.showRouteHistoryForDate(dateMillis)
            },
            onWeekSummary = { dateMillis ->
                viewModel.showWeekSummary(dateMillis)
            }
        )
    }

    private fun showWeekSummaryDialog(summary: String) {
        DialogFactory.showWeekSummaryDialog(this, summary)
    }

    private fun showEditNotesDialog(clientId: String, clientName: String, currentNotes: String) {
        DialogFactory.showEditNotesDialog(
            context = this,
            clientId = clientId,
            clientName = clientName,
            currentNotes = currentNotes,
            onSave = { id, notes -> viewModel.saveClientNotes(id, notes) }
        )
    }

    private fun showStaleArrivalDialog(clientName: String, minutesElapsed: Long) {
        DialogFactory.showStaleArrivalDialog(
            context = this,
            clientName = clientName,
            minutesElapsed = minutesElapsed,
            onMarkComplete = {
                val location = getCurrentLocation()
                lastLocation = location
                val notes = statusNotesBinding.visitNotesInput.text?.toString().orEmpty()
                viewModel.resolveStaleArrival(markComplete = true, currentLocation = location, visitNotes = notes)
            },
            onDiscard = {
                viewModel.resolveStaleArrival(markComplete = false)
            },
            onGoBack = {
                viewModel.dropPendingStaleAction()
            }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val state = viewModel.uiState.value
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_cu_override)?.isChecked = state.cuOverrideEnabled
        menu.findItem(R.id.action_toggle_direction)?.title = getDirectionMenuTitle(state.routeDirection)
        val skipCount = viewModel.skippedCount()
        menu.findItem(R.id.action_clear_skips)?.title = if (skipCount > 0)
            "Clear Skipped ($skipCount)" else getString(R.string.menu_clear_skips)
        menu.findItem(R.id.action_toggle_break_logging)?.isChecked = viewModel.isNonClientLoggingEnabled()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_import_file -> {
                importLauncher.launch(arrayOf("*/*"))
                true
            }
            R.id.action_sync_sheets -> {
                showSheetsUrlDialog()
                true
            }
            R.id.action_cu_override -> {
                item.isChecked = !item.isChecked
                viewModel.toggleCuOverride()
                rerunSuggestionsIfVisible()
                true
            }
            R.id.action_toggle_direction -> {
                viewModel.toggleRouteDirection()
                item.title = getDirectionMenuTitle(viewModel.uiState.value.routeDirection)
                rerunSuggestionsIfVisible()
                true
            }
            R.id.action_open_route_maps -> {
                viewModel.exportTopRouteToMaps()
                true
            }
            R.id.action_daily_summary -> {
                viewModel.showDailySummary()
                true
            }
            R.id.action_route_history -> {
                viewModel.showRouteHistory()
                true
            }
            R.id.action_clear_skips -> {
                viewModel.clearSkippedClients()
                rerunSuggestionsIfVisible()
                true
            }
            R.id.action_edit_notes -> {
                viewModel.editSelectedClientNotes()
                true
            }
            R.id.action_toggle_break_logging -> {
                viewModel.toggleNonClientLogging()
                item.isChecked = viewModel.isNonClientLoggingEnabled()
                true
            }
            R.id.action_break_threshold -> {
                showBreakThresholdDialog()
                true
            }
            R.id.action_min_days -> {
                showMinDaysDialog()
                true
            }
            R.id.action_destinations -> {
                destinationInputController.showDestinationDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun getDirectionMenuTitle(direction: RouteDirection): String {
        return when (direction) {
            RouteDirection.OUTWARD -> getString(R.string.menu_direction_outward)
            RouteDirection.HOMEWARD -> getString(R.string.menu_direction_homeward)
        }
    }

    private fun showBreakThresholdDialog() {
        val current = viewModel.getNonClientStopThreshold()
        DialogFactory.showBreakThresholdDialog(this, current) { mins ->
            viewModel.setNonClientStopThreshold(mins)
        }
    }

    private fun showMinDaysDialog() {
        val current = viewModel.uiState.value.minDays
        DialogFactory.showMinDaysDialog(this, current) { days ->
            viewModel.setMinDays(days)
            rerunSuggestionsIfVisible()
        }
    }

    private fun rerunSuggestionsIfVisible() {
        if (clients.isNotEmpty() && viewModel.uiState.value.suggestions.isNotEmpty()) {
            suggestNextClients()
        }
    }

    private fun launchDestinationsScreen() {
        val state = viewModel.uiState.value
        val intent = DestinationsActivity.createIntent(
            context = this,
            destinationQueue = state.destinationQueue,
            activeDestinationIndex = state.activeDestinationIndex,
            currentLocation = lastLocation
        )
        destinationsScreenLauncher.launch(intent)
    }

    private fun getCurrentLocation(): Location? {
        val hasCoarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasCoarse && !hasFine) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                101
            )
            return null
        }

        val manager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Try to get current location from all providers
        val gps = runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val network = runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val fused = runCatching { manager.getLastKnownLocation("fused") }.getOrNull()

        val best = listOfNotNull(gps, network, fused).maxByOrNull { it.time }

        return best
    }

    private fun showSheetsUrlDialog() {
        DialogFactory.showSheetsUrlDialog(
            context = this,
            currentReadUrl = sheetsUrl,
            currentWriteUrl = SheetsWriteBack.webAppUrl,
            onSyncNow = { enteredReadUrl, enteredWriteUrl ->
                if (enteredReadUrl.isNotBlank()) {
                    sheetsUrl = enteredReadUrl
                }
                if (enteredWriteUrl.isNotBlank()) {
                    SheetsWriteBack.webAppUrl = enteredWriteUrl
                }
                saveSyncSettings()

                if (sheetsUrl.isNotBlank()) {
                    syncFromSheets(sheetsUrl)
                } else {
                    viewModel.postStatus(getString(R.string.status_saved_write_url))
                }
            }
        )
    }

    private fun saveSyncSettings() {
        viewModel.updateSyncSettings(sheetsUrl, SheetsWriteBack.webAppUrl)
    }

    private fun syncFromSheets(url: String) {
        viewModel.syncFromSheets(url)
    }

    // ── Geocoding ──────────────────────────────────────────────

    private fun geocodeClientsInBackground() {
        viewModel.geocodeMissingClientCoordinates()
    }

    // ── Location tracking & arrival detection ─────────────────

    private fun getBestSuggestionLocation(): Location? {
        val now = System.currentTimeMillis()
        val serviceLocation = trackingEventBus.latestLocation.value
        val serviceLocationIsFresh = serviceLocation != null && (now - serviceLocation.time) <= SUGGEST_LOCATION_MAX_AGE_MS

        if (serviceLocationIsFresh) {
            return serviceLocation
        }

        val currentFix = getCurrentLocation()
        if (currentFix != null) {
            return currentFix
        }

        return serviceLocation ?: lastLocation
    }

    private fun locationFromIntent(
        intent: Intent,
        latKey: String,
        lngKey: String,
        timeKey: String
    ): Location? {
        if (!intent.hasExtra(latKey) || !intent.hasExtra(lngKey)) return null
        val lat = intent.getDoubleExtra(latKey, Double.NaN)
        val lng = intent.getDoubleExtra(lngKey, Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return null
        return Location("notification").apply {
            latitude = lat
            longitude = lng
            time = intent.getLongExtra(timeKey, System.currentTimeMillis())
        }
    }

    private fun showArrivalDialog(client: Client, arrivedAtMillis: Long, location: Location, fromNotification: Boolean = false) {
        // Don't nag if we already showed this client this session
        // BUT always show if user explicitly tapped a notification
        if (!fromNotification && arrivedClientIds.contains(client.id)) return
        arrivedClientIds.add(client.id)

        val stepsLabel = getSelectedStepsLabel()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_arrived_title, client.name))
            .setMessage(getString(R.string.dialog_arrived_message, client.address, stepsLabel))
            .setPositiveButton(R.string.dialog_yes_arrived) { _, _ ->
                lastLocation = location
                viewModel.markArrivalForClient(client, location, arrivedAtMillis)
                // Dismiss the arrival notification for this client
                trackingUiController.dismissNotification(2000 + client.id.hashCode())
            }
            .setNegativeButton(R.string.dialog_not_here) { _, _ ->
                viewModel.recordCancelledClientStop(
                    client = client,
                    arrivedAtMillis = arrivedAtMillis,
                    reason = "arrival_prompt_not_here",
                    location = location
                )
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Shows a dialog to confirm job completion after the user departed a client site.
     * This fires when the user was on site 5+ min and then drove away.
     */
    private fun showCompletionDialog(client: Client, timeOnSiteMillis: Long, arrivedAtMillis: Long, location: Location) {
        val minutesOnSite = (timeOnSiteMillis / 60_000).toInt().coerceAtLeast(1)
        val stepsLabel = getSelectedStepsLabel()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_complete_title, client.name))
            .setMessage(getString(R.string.dialog_complete_message, client.address, minutesOnSite, stepsLabel))
            .setPositiveButton(R.string.dialog_yes_complete) { _, _ ->
                // Use the real arrival time captured by the tracking service
                lastLocation = location
                viewModel.markArrivalForClient(client, location, arrivedAtMillis)
                confirmSelectedClientService()
                // Dismiss the completion notification for this client
                trackingUiController.dismissNotification(3000 + client.id.hashCode())
            }
            .setNegativeButton(R.string.dialog_not_yet) { _, _ ->
                viewModel.recordCancelledClientStop(
                    client = client,
                    arrivedAtMillis = arrivedAtMillis,
                    reason = "completion_prompt_not_yet",
                    location = location
                )
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Shows a multi-select dialog for a cluster of 2+ nearby clients that were
     * all departed in the same GPS tick. All are pre-checked; user unchecks any
     * they didn't actually service.
     */
    private fun showClusterCompletionDialog(members: List<ClusterMember>) {
        DialogFactory.showClusterCompletionDialog(
            context = this,
            members = members,
            onConfirmSelection = { selected ->
                if (selected.isNotEmpty()) {
                    viewModel.confirmClusterService(selected)
                }
                for (member in members) {
                    trackingUiController.dismissNotification(3000 + member.client.id.hashCode())
                }
                trackingUiController.dismissNotification(4000 + members.hashCode())
            }
        )
    }

    private fun handleArrivalIntent(intent: Intent?) {
        if (intent == null) return

        // Handle "Arrived at X?" notification tap
        val arrivalClientId = intent.getStringExtra(LocationTrackingService.EXTRA_ARRIVAL_CLIENT_ID)
        if (arrivalClientId != null) {
            val client = clients.find { it.id == arrivalClientId } ?: return
            val arrivedAt = intent.getLongExtra(LocationTrackingService.EXTRA_ARRIVAL_ARRIVED_AT, System.currentTimeMillis())
            val location = locationFromIntent(
                intent,
                LocationTrackingService.EXTRA_ARRIVAL_LAT,
                LocationTrackingService.EXTRA_ARRIVAL_LNG,
                LocationTrackingService.EXTRA_ARRIVAL_TIME
            ) ?: trackingEventBus.latestLocation.value ?: getCurrentLocation() ?: return
            // Force show dialog even if arrivedClientIds already has this client
            // (notification tap = explicit user action)
            showArrivalDialog(client, arrivedAt, location, fromNotification = true)
            // Clear extra only after successfully handling
            intent.removeExtra(LocationTrackingService.EXTRA_ARRIVAL_CLIENT_ID)
            intent.removeExtra(LocationTrackingService.EXTRA_ARRIVAL_LAT)
            intent.removeExtra(LocationTrackingService.EXTRA_ARRIVAL_LNG)
            intent.removeExtra(LocationTrackingService.EXTRA_ARRIVAL_TIME)
            intent.removeExtra(LocationTrackingService.EXTRA_ARRIVAL_ARRIVED_AT)
            return
        }

        // Handle "Mark job complete for X?" notification tap
        val completeClientId = intent.getStringExtra(LocationTrackingService.EXTRA_COMPLETE_CLIENT_ID)
        if (completeClientId != null) {
            val minutes = intent.getIntExtra(LocationTrackingService.EXTRA_COMPLETE_MINUTES, 5)
            val arrivedAt = intent.getLongExtra(LocationTrackingService.EXTRA_COMPLETE_ARRIVED_AT, System.currentTimeMillis() - minutes * 60_000L)
            val client = clients.find { it.id == completeClientId } ?: return
            val location = locationFromIntent(
                intent,
                LocationTrackingService.EXTRA_COMPLETE_LAT,
                LocationTrackingService.EXTRA_COMPLETE_LNG,
                LocationTrackingService.EXTRA_COMPLETE_TIME
            ) ?: trackingEventBus.latestLocation.value ?: getCurrentLocation() ?: return
            showCompletionDialog(client, minutes * 60_000L, arrivedAt, location)
            // Clear extras only after successfully handling
            intent.removeExtra(LocationTrackingService.EXTRA_COMPLETE_CLIENT_ID)
            intent.removeExtra(LocationTrackingService.EXTRA_COMPLETE_MINUTES)
            intent.removeExtra(LocationTrackingService.EXTRA_COMPLETE_LAT)
            intent.removeExtra(LocationTrackingService.EXTRA_COMPLETE_LNG)
            intent.removeExtra(LocationTrackingService.EXTRA_COMPLETE_TIME)
            intent.removeExtra(LocationTrackingService.EXTRA_COMPLETE_ARRIVED_AT)
            return
        }

        // Handle "Mark all N complete?" cluster notification tap
        val clusterClientIds = intent.getStringArrayExtra(LocationTrackingService.EXTRA_CLUSTER_CLIENT_IDS)
        if (clusterClientIds != null && clusterClientIds.size >= 2) {
            val minutesArray = intent.getIntArrayExtra(LocationTrackingService.EXTRA_CLUSTER_MINUTES) ?: IntArray(clusterClientIds.size) { 5 }
            val arrivedAtArray = intent.getLongArrayExtra(LocationTrackingService.EXTRA_CLUSTER_ARRIVED_AT)
            val location = trackingEventBus.latestLocation.value ?: getCurrentLocation()
            val now = System.currentTimeMillis()
            val members = clusterClientIds.mapIndexedNotNull { i, id ->
                val client = clients.find { it.id == id } ?: return@mapIndexedNotNull null
                val mins = minutesArray.getOrElse(i) { 5 }
                val arrivedAt = arrivedAtArray?.getOrElse(i) { now - mins * 60_000L } ?: (now - mins * 60_000L)
                ClusterMember(
                    client = client,
                    timeOnSiteMillis = mins * 60_000L,
                    arrivedAtMillis = arrivedAt,
                    location = location ?: Location("notification")
                )
            }
            if (members.size >= 2) {
                showClusterCompletionDialog(members)
            }
            intent.removeExtra(LocationTrackingService.EXTRA_CLUSTER_CLIENT_IDS)
            intent.removeExtra(LocationTrackingService.EXTRA_CLUSTER_MINUTES)
            intent.removeExtra(LocationTrackingService.EXTRA_CLUSTER_ARRIVED_AT)
            return
        }
    }

}
