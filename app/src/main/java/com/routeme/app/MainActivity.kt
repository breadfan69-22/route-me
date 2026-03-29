package com.routeme.app

import android.content.res.Configuration
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.Gravity
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
import com.routeme.app.network.DistanceMatrixHelper
import com.routeme.app.network.GeocodingHelper
import com.routeme.app.network.SheetsWriteBack
import com.routeme.app.ui.DestinationInputController
import com.routeme.app.ui.DialogFactory
import com.routeme.app.ui.EventObserver
import com.routeme.app.ui.MainEvent
import com.routeme.app.ui.MainUiState
import com.routeme.app.ui.MainViewModel
import com.routeme.app.ui.SplitFlapDigitView
import com.routeme.app.ui.SuggestionUiController
import com.routeme.app.ui.StepPickerBottomSheet
import com.routeme.app.ui.TrackingUiController
import kotlinx.coroutines.launch
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.widget.PopupMenu
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : AppCompatActivity() {
    private var clients = mutableListOf<Client>()
    private var lastLocation: Location?  = null
    private lateinit var binding: ActivityMainBinding
    private lateinit var suggestionUiController: SuggestionUiController
    private lateinit var trackingUiController: TrackingUiController
    private lateinit var destinationInputController: DestinationInputController
    private lateinit var eventObserver: EventObserver

    private val viewModel: MainViewModel by viewModel()
    private val trackingEventBus: TrackingEventBus by inject()
    private val preferencesRepository: com.routeme.app.data.PreferencesRepository by inject()
    private var sheetsUrl: String = ""
    private val SUGGEST_LOCATION_MAX_AGE_MS = 120_000L
    private var lastObservedServiceTypes: Set<ServiceType>? = null
    private var lastObservedClientCount: Int? = null
    private var currentHeroRes: Int = 0
    private var currentHeroSecondaryRes: Int = 0
    private lateinit var splitFlapDigits: List<SplitFlapDigitView>

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

    private val syncSheetScreenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val syncResult = SyncSheetActivity.extractResult(result.data) ?: return@registerForActivityResult
        if (syncResult.readUrl.isNotBlank()) {
            sheetsUrl = syncResult.readUrl
        }
        if (syncResult.writeUrl.isNotBlank()) {
            SheetsWriteBack.webAppUrl = syncResult.writeUrl
        }
        saveSyncSettings()
        if (syncResult.syncRequested && sheetsUrl.isNotBlank()) {
            syncFromSheets(sheetsUrl)
        }
    }

    private val weeklyPlannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            com.routeme.app.ui.WeeklyPlannerActivity.RESULT_REGENERATE -> {
                val anchors = com.routeme.app.ui.WeeklyPlannerActivity.extractAnchors(result.data)
                viewModel.showWeeklyPlanner(dayAnchors = anchors)
            }
            com.routeme.app.ui.WeeklyPlannerActivity.RESULT_START_ROUTE -> {
                val destinations = com.routeme.app.ui.WeeklyPlannerActivity.extractRouteDestinations(result.data)
                if (!destinations.isNullOrEmpty()) {
                    viewModel.replaceDestinationQueue(queue = destinations, activeDestinationIndex = 0)
                    val clientsById = viewModel.uiState.value.clients.associateBy { it.id }
                    val plannedClients = destinations.mapNotNull { destination ->
                        clientsById[destination.id]
                    }
                    if (plannedClients.isNotEmpty()) {
                        viewModel.loadPlannedRoute(plannedClients)
                    }
                    showCurrentPage()
                    Snackbar.make(
                        binding.root,
                        "Route loaded: ${plannedClients.size} clients • ${destinations.size} destinations",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set status bar icons to dark in light mode so they're visible
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = !isNightMode

        // Apply status bar inset to the spacer so hero doesn't overlap system UI
        ViewCompat.setOnApplyWindowInsetsListener(binding.statusBarSpacer) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.layoutParams.height = statusBarHeight
            view.requestLayout()
            insets
        }

        suggestionUiController = SuggestionUiController(binding, viewModel,
            onSuggestionTapped = { suggestion -> showClientActionDialog(suggestion.client) },
            onNavigateToDestination = { dest -> openDestinationInMaps(dest) })
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

        binding.heroMenuButton.setOnClickListener { showMainMenu(it) }

        setupSplitFlapDigits()
        applyDarkOverlay()

        suggestionUiController.bindPaginationActions()
        setupTileActions()
        observeViewModel()
        eventObserver.start()
        handleArrivalIntent(intent)

        // Set up Distance Matrix API key
        DistanceMatrixHelper.apiKey = BuildConfig.MAPS_API_KEY
        GeocodingHelper.apiKey = BuildConfig.MAPS_API_KEY

        maybePromptStartTracking(savedInstanceState)

    }

    private fun maybePromptStartTracking(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        if (trackingEventBus.isTracking.value || viewModel.uiState.value.isTracking) return

        DialogFactory.showStartTrackingPrompt(this) {
            trackingUiController.startTracking()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
            }
        }
    }

    private fun renderState(state: MainUiState) {
        syncRenderedState(state)
        bindDashboardHero(state)
        handleSuggestionRefreshTriggers(state)
        bindPrimaryTiles(state)
        showCurrentPage()
    }

    private fun syncRenderedState(state: MainUiState) {
        clients.clear()
        clients.addAll(state.clients)
        sheetsUrl = state.sheetsReadUrl
        if (state.sheetsWriteUrl != SheetsWriteBack.webAppUrl) {
            SheetsWriteBack.webAppUrl = state.sheetsWriteUrl
        }
        if (state.summaryText.isNotBlank()) {
            binding.summaryText.text = state.summaryText
        }
        binding.statusText.text = state.statusText
    }

    private fun bindDashboardHero(state: MainUiState) {
        updateHeroIcon(state)

        if (state.currentStopClientName != null) {
            binding.heroClientName.text = "At: ${state.currentStopClientName}"
            binding.heroClientName.visibility = View.VISIBLE
        } else {
            binding.heroClientName.visibility = View.GONE
        }

        // Weather display - toggle between current observation and hourly forecast
        val showCurrent = state.showCurrentWeather
        val tempF = if (showCurrent) state.currentWeatherTempF else state.forecastTempF
        val iconDesc = if (showCurrent) state.currentWeatherIconDesc else state.forecastIconDesc
        val windMph = if (showCurrent) state.currentWeatherWindMph else state.forecastWindMph
        val windDir = if (showCurrent) state.currentWeatherWindDirection else state.forecastWindDirection
        val gust = if (showCurrent) state.currentWeatherWindGust else null // forecasts don't have gusts

        val hasWeather = tempF != null
        binding.heroWeatherChip.visibility = if (hasWeather) View.VISIBLE else View.GONE
        if (hasWeather) {
            val tempLabel = if (showCurrent) "${tempF}°F" else "${state.forecastTimeLabel ?: ""} ${tempF}°F"
            binding.heroWeatherTemp.text = tempLabel.trim()
            binding.heroWeatherIcon.setImageResource(weatherDescToIcon(iconDesc, state.isDaytime))

            // Wind direction + speed
            if (windMph != null && windMph > 0) {
                val windText = if (windDir != null) "$windDir ${windMph}mph" else "${windMph}mph"
                binding.heroWeatherWind.text = windText
                binding.heroWeatherWind.visibility = View.VISIBLE
            } else {
                binding.heroWeatherWind.visibility = View.GONE
            }

            // Wind gust (only for current weather)
            if (gust != null && gust > (windMph ?: 0)) {
                binding.heroWeatherGust.text = "Gust- ${gust}mph"
                binding.heroWeatherGust.visibility = View.VISIBLE
            } else {
                binding.heroWeatherGust.visibility = View.GONE
            }
        }

        if (state.selectedServiceTypes.isNotEmpty()) {
            binding.heroStepChip.visibility = View.VISIBLE
            binding.heroStepLabel.text = buildHeroStepLabel(state.selectedServiceTypes)
            binding.heroStepIcon.setImageResource(stepTypeToSmallIcon(state.selectedServiceTypes))
            bindHeroBagCount(state)
        } else {
            binding.heroStepChip.visibility = View.GONE
        }

        val countdownValue = if (state.errandsModeEnabled) {
            state.destinationQueue.size
        } else {
            state.eligibleClientCount
        }
        binding.heroCountdownPrefix.text = if (state.errandsModeEnabled) {
            getString(R.string.hero_destinations_remaining_prefix)
        } else {
            getString(R.string.hero_stops_remaining_prefix)
        }
        updateRemainingCount(countdownValue)

        val destination = state.activeDestination
        binding.heroDestChip.visibility = if (destination != null) View.VISIBLE else View.GONE
        if (destination != null) {
            binding.heroDestText.text = "→ ${destination.name}"
        }
    }

    private fun handleSuggestionRefreshTriggers(state: MainUiState) {
        val previousClientCount = lastObservedClientCount
        lastObservedClientCount = state.clients.size

        // Trigger suggestions when clients become available for the first time,
        // or when clients transition from 0 → N (after a sync).
        val clientsJustLoaded = state.clients.isNotEmpty() &&
            !state.isLoading &&
            !state.isSuggestionsLoading &&
            state.suggestions.isEmpty() &&
            (previousClientCount == null || previousClientCount == 0)

        if (clientsJustLoaded && state.selectedServiceTypes.isNotEmpty()) {
            suggestNextClients()
        }

        val previousServiceTypes = lastObservedServiceTypes
        if (previousServiceTypes != null && previousServiceTypes != state.selectedServiceTypes) {
            rerunSuggestionsIfVisible()
        }
        lastObservedServiceTypes = state.selectedServiceTypes
    }

    private fun bindPrimaryTiles(state: MainUiState) {
        bindStepTile(state)
        bindSyncTile(state)
        bindUpcomingTile()
        bindTrackingTile(state)
        bindErrandsSuggestionVisibility(state)
        bindDirectionTile(state)
    }

    private fun bindStepTile(state: MainUiState) {
        binding.stepLabel.text = formatStepLabel(state.selectedServiceTypes)
        binding.stepIcon.setImageResource(resolveStepTileIcon(state))
    }

    private fun bindSyncTile(state: MainUiState) {
        val sheetLoaded = state.sheetsReadUrl.isNotBlank()
        binding.syncIcon.setImageResource(
            if (sheetLoaded) R.drawable.ic_cloud_download else R.drawable.ic_cloud_off
        )
        binding.syncStatusText.text = if (sheetLoaded) {
            getString(R.string.tile_sync_status_loaded)
        } else {
            getString(R.string.tile_sync_status_none)
        }
    }

    private fun bindUpcomingTile() {
        binding.upcomingIcon.setImageResource(R.drawable.ic_event_available)
        binding.upcomingStatusText.text = getString(R.string.tile_upcoming_status_none)
    }

    private fun bindTrackingTile(state: MainUiState) {
        binding.tileDirection.isActive = state.routeDirection == RouteDirection.HOMEWARD
        binding.tileTracking.isActive = state.isTracking
        binding.trackingButton.setImageResource(
            if (state.isTracking) R.drawable.ic_wrong_location else R.drawable.ic_location_on
        )
        binding.trackingStatusText.text = if (state.isTracking) {
            getString(R.string.tile_tracking_status_active)
        } else {
            getString(R.string.tile_tracking_status_idle)
        }
    }

    private fun bindErrandsSuggestionVisibility(state: MainUiState) {
        if (state.errandsModeEnabled) {
            binding.errandsBanner.visibility = View.VISIBLE
            binding.errandsBanner.text = getString(
                R.string.errands_banner_active,
                state.destinationQueue.size
            )
            binding.tileSuggested.visibility = View.VISIBLE
        } else {
            binding.errandsBanner.visibility = View.GONE
            binding.tileSuggested.visibility =
                if (state.suggestions.isEmpty() && !state.isSuggestionsLoading) View.GONE else View.VISIBLE
        }
    }

    private fun bindDirectionTile(state: MainUiState) {
        val activeDestination = state.activeDestination
        binding.directionIcon.setImageResource(
            if (activeDestination != null) R.drawable.ic_assistant_navigation
            else when (state.routeDirection) {
                RouteDirection.OUTWARD -> R.drawable.ic_arrow_upward
                RouteDirection.HOMEWARD -> R.drawable.ic_home_work
            }
        )
        binding.directionText.text = activeDestination?.name ?: when (state.routeDirection) {
            RouteDirection.OUTWARD -> getString(R.string.menu_direction_outward)
            RouteDirection.HOMEWARD -> getString(R.string.menu_direction_homeward)
        }
        if (activeDestination != null) {
            binding.destinationBanner.text = "\uD83D\uDCCD Heading toward: ${activeDestination.name}"
            binding.destinationBanner.visibility = View.VISIBLE
        } else {
            binding.destinationBanner.visibility = View.GONE
        }
    }

    // ─── Dashboard Hero helpers ────────────────────────────────

    private fun setupSplitFlapDigits() {
        val container = binding.heroSplitFlapContainer
        val digitWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 22f, resources.displayMetrics
        ).toInt()
        val digitHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics
        ).toInt()
        val marginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics
        ).toInt()

        splitFlapDigits = (0 until 3).map { i ->
            SplitFlapDigitView(this).apply {
                val lp = android.widget.LinearLayout.LayoutParams(digitWidthPx, digitHeightPx)
                if (i > 0) lp.marginStart = marginPx
                layoutParams = lp
            }
        }
        splitFlapDigits.forEach { container.addView(it) }
    }

    private fun applyDarkOverlay() {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        binding.heroDarkOverlay.visibility =
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) View.VISIBLE else View.GONE
    }

    private fun updateHeroIcon(state: com.routeme.app.ui.MainUiState) {
        val icons = resolveHeroIcons(state)
        val primaryRes = icons.first()
        val secondaryRes = icons.getOrNull(1) ?: 0

        applyHeroIconLayout(primaryRes, secondaryRes)

        if (primaryRes == currentHeroRes && secondaryRes == currentHeroSecondaryRes) return
        currentHeroRes = primaryRes
        currentHeroSecondaryRes = secondaryRes

        binding.heroIcon.animate().alpha(0f).setDuration(150).withEndAction {
            binding.heroIcon.setImageResource(primaryRes)
            binding.heroIcon.animate().alpha(1f).setDuration(150).start()
        }.start()

        if (secondaryRes != 0) {
            binding.heroIconSecondary.visibility = View.VISIBLE
            binding.heroIconSecondary.animate().alpha(0f).setDuration(150).withEndAction {
                binding.heroIconSecondary.setImageResource(secondaryRes)
                binding.heroIconSecondary.animate().alpha(1f).setDuration(150).start()
            }.start()
        } else {
            binding.heroIconSecondary.visibility = View.GONE
        }
    }

    private fun resolveHeroIcons(state: com.routeme.app.ui.MainUiState): List<Int> {
        if (state.errandsModeEnabled) return listOf(R.drawable.ic_hero_notepad)

        val hasBug = state.selectedServiceTypes.any {
            it == ServiceType.GRUB || it == ServiceType.INCIDENTAL
        }
        val hasSprayer = state.selectedServiceTypes.any {
            it == ServiceType.ROUND_2 || it == ServiceType.ROUND_5
        }
        val hasSpreader = state.selectedServiceTypes.any {
            it == ServiceType.ROUND_1 || it == ServiceType.ROUND_3 ||
                it == ServiceType.ROUND_4 || it == ServiceType.ROUND_6
        }

        val icons = mutableListOf<Int>()
        if (hasSpreader) icons += R.drawable.permagreen_turf_spreader_sprayer
        if (hasSprayer) icons += R.drawable.siteone_lesco_495246_1
        if (hasBug) icons += R.drawable.grub

        if (icons.isEmpty()) return listOf(R.drawable.ic_hero_default)
        return icons.take(2)
    }

    private fun resolveStepTileIcon(state: com.routeme.app.ui.MainUiState): Int {
        if (state.errandsModeEnabled) return R.drawable.ic_sil_notepad
        val types = state.selectedServiceTypes
        val hasBug = types.any { it == ServiceType.GRUB || it == ServiceType.INCIDENTAL }
        val hasSprayer = types.any { it == ServiceType.ROUND_2 || it == ServiceType.ROUND_5 }
        val hasSpreader = types.any {
            it == ServiceType.ROUND_1 || it == ServiceType.ROUND_3 ||
                it == ServiceType.ROUND_4 || it == ServiceType.ROUND_6
        }
        return when {
            hasSpreader -> R.drawable.ic_sil_spreader
            hasSprayer -> R.drawable.ic_sil_sprayer
            hasBug -> R.drawable.ic_sil_bug
            else -> R.drawable.ic_sil_default
        }
    }

    private fun applyHeroIconLayout(primaryRes: Int, secondaryRes: Int) {
        val hasSecondary = secondaryRes != 0
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dualBaseDp   = if (isLandscape) 90f  else 112f
        val singleBaseDp = if (isLandscape) 120f else 160f

        if (hasSecondary) {
            val primarySizeDp = if (primaryRes == R.drawable.permagreen_turf_spreader_sprayer) {
                dualBaseDp * 1.7f
            } else {
                dualBaseDp
            }
            val secondarySizeDp = if (secondaryRes == R.drawable.permagreen_turf_spreader_sprayer) {
                dualBaseDp * 1.7f
            } else {
                dualBaseDp
            }
            applyHeroIconFrame(binding.heroIcon, primarySizeDp, -64f)
            applyHeroIconFrame(binding.heroIconSecondary, secondarySizeDp, 64f)
            binding.heroIconSecondary.visibility = View.VISIBLE
            return
        }

        val primarySizeDp = if (primaryRes == R.drawable.permagreen_turf_spreader_sprayer) {
            singleBaseDp * 1.7f
        } else {
            singleBaseDp
        }
        applyHeroIconFrame(binding.heroIcon, primarySizeDp, 0f)
        binding.heroIconSecondary.visibility = View.GONE
        binding.heroIconSecondary.translationX = 0f
    }

    private fun applyHeroIconFrame(icon: android.widget.ImageView, sizeDp: Float, offsetDp: Float) {
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            sizeDp,
            resources.displayMetrics
        ).toInt()
        val offsetPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            offsetDp,
            resources.displayMetrics
        )
        val params = icon.layoutParams as android.widget.FrameLayout.LayoutParams
        params.width = sizePx
        params.height = sizePx
        params.gravity = Gravity.CENTER
        icon.layoutParams = params
        icon.translationX = offsetPx
    }

    private fun buildHeroStepLabel(types: Set<ServiceType>): String {
        val steps = types.filter { it.stepNumber in 1..6 }
            .sortedBy { it.stepNumber }
            .map { "Step ${it.stepNumber}" }
        val extras = mutableListOf<String>()
        if (types.contains(ServiceType.GRUB)) extras += "Grub"
        if (types.contains(ServiceType.INCIDENTAL)) extras += "Incidental"
        return (steps + extras).joinToString(" + ")
    }

    private fun stepTypeToSmallIcon(types: Set<ServiceType>): Int = when {
        types.any { it == ServiceType.GRUB || it == ServiceType.INCIDENTAL } -> R.drawable.grub_small
        types.any { it == ServiceType.ROUND_2 || it == ServiceType.ROUND_5 } -> R.drawable.siteone_lesco_495246_1_small
        else -> R.drawable.permagreen_turf_spreader_sprayer_small
    }

    private fun updateRemainingCount(newCount: Int) {
        val padded = newCount.coerceIn(0, 999).toString().padStart(3, '0')
        padded.reversed().forEachIndexed { i, digit ->
            splitFlapDigits[2 - i].postDelayed({
                splitFlapDigits[2 - i].setDigit(digit)
            }, i * 50L)
        }
    }

    private fun weatherDescToIcon(desc: String?, isDaytime: Boolean): Int {
        val d = desc?.lowercase() ?: return R.drawable.ic_weather_unknown
        return when {
            "snow" in d || "flurr" in d || "ice" in d -> R.drawable.ic_weather_snowy
            "rain" in d || "drizzle" in d || "shower" in d -> R.drawable.ic_weather_rainy
            "wind" in d || "breezy" in d || "blustery" in d -> R.drawable.ic_weather_windy
            "thunder" in d || "storm" in d -> R.drawable.ic_weather_rainy
            "overcast" in d || "cloudy" in d -> R.drawable.ic_weather_cloudy
            "partly" in d || "mostly" in d || "haze" in d -> {
                if (isDaytime) R.drawable.ic_weather_partly else R.drawable.ic_weather_partly_night
            }
            "clear" in d || "sunny" in d || "fair" in d -> {
                if (isDaytime) R.drawable.ic_weather_sunny else R.drawable.ic_weather_clear_night
            }
            else -> R.drawable.ic_weather_unknown
        }
    }

    private fun handleMainEvent(event: MainEvent) {
        when (event) {
            is MainEvent.ShowSnackbar -> showShortSnackbar(event.message)
            is MainEvent.OpenMapsRoute -> openMapsUri(Uri.parse(event.uri))
            is MainEvent.ShowDailySummary -> showDailySummaryDialog(event.summary)
            is MainEvent.StaleArrivalPrompt -> showStaleArrivalDialog(event.clientName, event.minutesElapsed)
            is MainEvent.ClusterCompletePrompt -> showClusterCompletionDialog(event.members)
            is MainEvent.UndoConfirmation -> showUndoConfirmationSnackbar(event)
            is MainEvent.UndoClusterConfirmation -> showUndoClusterConfirmationSnackbar(event)
            is MainEvent.EditClientNotes -> showEditNotesDialog(event.clientId, event.clientName, event.currentNotes)
            is MainEvent.ShowRouteHistory -> showRouteHistoryDialog(event)
            is MainEvent.ShowWeekSummary -> showWeekSummaryDialog(event.summary)
            is MainEvent.ShowWeeklyPlanner -> launchWeeklyPlannerScreen(event.plan)
            MainEvent.RefreshTrackingClients -> trackingUiController.refreshTrackedClients()
            MainEvent.ServiceConfirmed -> rerunSuggestionsIfVisible()
            MainEvent.SyncComplete -> rerunSuggestionsIfVisible()
            is MainEvent.PropertyNudge -> showPropertyNudgeSnackbar(event.clientId, event.clientName)
        }
    }

    private fun showPropertyNudgeSnackbar(clientId: String, clientName: String) {
        Snackbar.make(binding.root, "Add property details for $clientName?", Snackbar.LENGTH_LONG)
            .setAction("Add") {
                val client = viewModel.uiState.value.clients.find { it.id == clientId } ?: return@setAction
                DialogFactory.showPropertyStatsDialog(
                    context = this,
                    clientName = clientName,
                    initialProperty = propertyInputFor(client),
                    onSave = { property ->
                        viewModel.writePropertyStats(clientName, property)
                    }
                )
            }
            .show()
    }

    private fun showShortSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showUndoConfirmationSnackbar(event: MainEvent.UndoConfirmation) {
        showUndoSnackbar(
            message = "Confirmed ${event.clientName}",
            onUndo = { viewModel.undoLastConfirmation(event.clientId, event.recordCompletedAtMillis) }
        )
    }

    private fun showUndoClusterConfirmationSnackbar(event: MainEvent.UndoClusterConfirmation) {
        val message = "Confirmed ${event.clientNames.size} stops"
        showUndoSnackbar(
            message = message,
            onUndo = { viewModel.undoClusterConfirmation(event.clientIds, event.recordCompletedAtMillis) }
        )
    }

    private fun showUndoSnackbar(message: String, onUndo: () -> Unit) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setDuration(8000)
            .setAction("UNDO") {
                onUndo()
            }
            .show()
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        trackingUiController.onRequestPermissionsResult(requestCode, grantResults)
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

    /** Build a short label like "S1+2" from explicitly supplied service types. */
    private fun formatStepLabel(types: Set<ServiceType>): String {
        if (types.isEmpty()) return ServiceType.ROUND_1.label
        if (types.size == 1) return types.first().label
        return types.sortedBy { it.stepNumber }.joinToString("+") { it.label }
    }

    private fun setupTileActions() {
        setupUpcomingTileActions()
        setupSyncTileActions()
        setupStepTileActions()
        setupTrackingTileActions()
        setupDirectionTileActions()
        setupSuggestionRefreshActions()
        setupWeatherTapAction()
        setupHeroInventoryAction()
        setupTileLongPressActions()
    }

    private fun setupHeroInventoryAction() {
        binding.heroStepChip.setOnClickListener {
            showTruckInventoryDialog()
        }
    }

    private fun bindHeroBagCount(state: MainUiState) {
        val bagCountView = binding.heroBagCount ?: return
        val inventory = state.granularInventory
        if (inventory == null) {
            bagCountView.text = "-- bags"
            bagCountView.setTextColor(Color.parseColor("#CCE0FF"))
            return
        }

        val current = inventory.current.coerceAtLeast(0.0)
        bagCountView.text = if (current == kotlin.math.floor(current)) "${current.toInt()} bags" else "${"%,.1f".format(current)} bags"
        val color = when {
            inventory.pctRemaining < 15 -> "#FF4444"
            inventory.pctRemaining < 30 -> "#FF8C00"
            else -> "#CCE0FF"
        }
        bagCountView.setTextColor(Color.parseColor(color))
    }

    private fun showTruckInventoryDialog() {
        val inventory = viewModel.uiState.value.granularInventory ?: return
        val currentBags = inventory.current.toInt().coerceAtLeast(0)
        val capacityBags = inventory.capacity.toInt().coerceAtLeast(1)

        DialogFactory.showTruckInventoryDialog(
            context = this,
            currentBags = currentBags,
            capacityBags = capacityBags,
            onAddBags = { bagsAdded -> viewModel.addBagsToTruck(bagsAdded) },
            onSetTotal = { exactBags -> viewModel.setBagsOnTruck(exactBags) }
        )
    }

    private fun setupWeatherTapAction() {
        binding.heroWeatherChip.setOnClickListener {
            viewModel.toggleWeatherDisplay()
        }
        // Fetch weather at current location on startup
        getCurrentLocation()?.let { loc ->
            viewModel.fetchWeatherAtLocation(loc.latitude, loc.longitude)
        }
    }

    private fun setupUpcomingTileActions() {
        // Tap the tile itself navigates to upcoming events (also covers landscape where badge is absent)
        binding.tileUpcoming.setOnClickListener {
            startActivity(Intent(this, UpcomingEventsActivity::class.java))
        }
        // Badge dot — only present in portrait; null in landscape layout
        binding.badgeUpcoming?.setOnClickListener {
            startActivity(Intent(this, UpcomingEventsActivity::class.java))
        }
    }

    private fun setupSyncTileActions() {
        binding.tileSync.setOnClickListener {
            if (sheetsUrl.isNotBlank()) {
                syncFromSheets(sheetsUrl)
            } else {
                Snackbar.make(binding.root, "No sheet URL configured — tap ⋯ to set one", Snackbar.LENGTH_SHORT).show()
            }
        }
        binding.badgeSync?.setOnClickListener {
            launchSyncSheetScreen()
        }
    }

    private fun setupStepTileActions() {
        binding.tileStep.setOnClickListener {
            StepPickerBottomSheet
                .newInstance(viewModel.uiState.value.selectedServiceTypes)
                .show(supportFragmentManager, "step_picker")
        }
    }

    private fun setupTrackingTileActions() {
        binding.trackingButton.setOnClickListener {
            trackingUiController.toggleTracking()
        }

        binding.tileTracking.setOnClickListener {
            trackingUiController.toggleTracking()
        }
        binding.badgeTracking?.setOnClickListener {
            viewModel.showDailySummary()
        }
    }

    private fun setupDirectionTileActions() {
        binding.directionIcon.setOnClickListener {
            val state = viewModel.uiState.value
            if (!state.errandsModeEnabled && state.activeDestination == null) {
                viewModel.toggleRouteDirection()
                rerunSuggestionsIfVisible()
            }
        }
        binding.tileDirection.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.errandsModeEnabled || state.activeDestination != null) {
                launchDestinationsScreen()
            }
        }
        binding.badgeDirection?.setOnClickListener {
            launchDestinationsScreen()
        }
    }

    private fun setupTileLongPressActions() {
        // Long-press on each tile triggers its overflow (badge) action.
        // Works in both orientations; in landscape the badge dots are absent so long-press is the only path.
        binding.tileTracking.setOnLongClickListener {
            viewModel.showDailySummary()
            true
        }
        binding.tileDirection.setOnLongClickListener {
            launchDestinationsScreen()
            true
        }
        binding.tileUpcoming.setOnLongClickListener {
            startActivity(Intent(this, UpcomingEventsActivity::class.java))
            true
        }
        binding.tileSync.setOnLongClickListener {
            launchSyncSheetScreen()
            true
        }
        binding.tileStep.setOnLongClickListener {
            StepPickerBottomSheet
                .newInstance(viewModel.uiState.value.selectedServiceTypes)
                .show(supportFragmentManager, "step_picker")
            true
        }
    }

    private fun setupSuggestionRefreshActions() {
        binding.badgeSuggestedRefresh.setOnClickListener {
            if (viewModel.uiState.value.errandsModeEnabled) {
                launchDestinationsScreen()
                return@setOnClickListener
            }
            suggestNextClients()
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

    private fun openDestinationInMaps(dest: com.routeme.app.SavedDestination) {
        val uri = Uri.parse("google.navigation:q=${dest.lat},${dest.lng}")
        openMapsUri(uri)
    }

    private fun openClientInMaps(client: Client) {
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

    private fun confirmServiceForSelectedClient(
        visitNotes: String = "",
        amountUsed: Double? = null,
        amountUsed2: Double? = null,
        property: PropertyInput = PropertyInput()
    ) {
        val current = getCurrentLocation()
        lastLocation = current
        val types = viewModel.uiState.value.selectedServiceTypes.ifEmpty { setOf(ServiceType.ROUND_1) }
        viewModel.setServiceTypes(types)
        viewModel.confirmSelectedClientService(current, visitNotes, amountUsed, amountUsed2, property)
    }

    private fun showClientActionDialog(client: Client) {
        val state = viewModel.uiState.value
        val primaryType = state.selectedServiceTypes.firstOrNull() ?: ServiceType.ROUND_1
        DialogFactory.showClientActionDialog(
            context = this,
            clientName = client.name,
            details = viewModel.buildClientDetailsFor(client),
            arrivalActive = state.arrivalStartedAtMillis != null,
            serviceTypes = state.selectedServiceTypes,
            granularRate = viewModel.getGranularRate(primaryType),
            initialProperty = propertyInputFor(client),
            onArrive = {
                lastLocation = getCurrentLocation()
                viewModel.startArrivalForSelected(lastLocation)
            },
            onCancelArrival = { viewModel.cancelArrival() },
            onMaps = { openClientInMaps(client) },
            onSkip = { viewModel.skipSelectedClientToday(); rerunSuggestionsIfVisible() },
            onConfirm = { notes, amt1, amt2, prop -> confirmServiceForSelectedClient(notes, amt1, amt2, prop) },
            onSavePropertyStats = { property -> viewModel.writePropertyStats(client.name, property) },
            onEditNotes = { viewModel.editSelectedClientNotes() }
        )
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
        DialogFactory.showWeekSummaryDialog(
            context = this,
            summary = summary,
            onWeeklyPlanner = { viewModel.showWeeklyPlanner() }
        )
    }

    private fun launchWeeklyPlannerScreen(plan: com.routeme.app.model.WeekPlan) {
        weeklyPlannerLauncher.launch(
            com.routeme.app.ui.WeeklyPlannerActivity.createIntent(this, plan)
        )
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
                viewModel.resolveStaleArrival(markComplete = true, currentLocation = location, visitNotes = "")
            },
            onDiscard = {
                viewModel.resolveStaleArrival(markComplete = false)
            },
            onHide = {
                viewModel.hideStaleArrival()
            }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val state = viewModel.uiState.value
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_cu_override)?.isChecked = state.cuOverrideEnabled
        menu.findItem(R.id.action_errands_mode)?.isChecked = state.errandsModeEnabled
        val skipCount = viewModel.skippedCount()
        menu.findItem(R.id.action_clear_skips)?.title = if (skipCount > 0)
            "Clear Skipped ($skipCount)" else getString(R.string.menu_clear_skips)
        menu.findItem(R.id.action_toggle_break_logging)?.isChecked = viewModel.isNonClientLoggingEnabled()
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_errands_mode)?.isChecked = viewModel.uiState.value.errandsModeEnabled
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_import_file -> {
                importLauncher.launch(arrayOf("*/*"))
                true
            }
            R.id.action_cu_override -> {
                item.isChecked = !item.isChecked
                viewModel.toggleCuOverride()
                rerunSuggestionsIfVisible()
                true
            }
            R.id.action_errands_mode -> {
                viewModel.toggleErrandsMode()
                item.isChecked = viewModel.uiState.value.errandsModeEnabled
                invalidateOptionsMenu()
                true
            }
            R.id.action_open_route_maps -> {
                viewModel.exportTopRouteToMaps()
                true
            }
            R.id.action_route_history -> {
                viewModel.showRouteHistory()
                true
            }
            R.id.action_weekly_planner -> {
                viewModel.showWeeklyPlanner()
                true
            }
            R.id.action_clear_skips -> {
                viewModel.clearSkippedClients()
                rerunSuggestionsIfVisible()
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
            R.id.action_app_rates -> {
                DialogFactory.showApplicationRatesDialog(this, preferencesRepository)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showMainMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        val state = viewModel.uiState.value
        popup.menu.findItem(R.id.action_cu_override)?.isChecked = state.cuOverrideEnabled
        popup.menu.findItem(R.id.action_errands_mode)?.isChecked = state.errandsModeEnabled
        val skipCount = viewModel.skippedCount()
        popup.menu.findItem(R.id.action_clear_skips)?.title =
            if (skipCount > 0) "Clear Skipped ($skipCount)" else getString(R.string.menu_clear_skips)
        popup.menu.findItem(R.id.action_toggle_break_logging)?.isChecked =
            viewModel.isNonClientLoggingEnabled()
        popup.setOnMenuItemClickListener { item -> onOptionsItemSelected(item) }
        popup.show()
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
        if (clients.isNotEmpty()) {
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

    private fun launchSyncSheetScreen() {
        syncSheetScreenLauncher.launch(SyncSheetActivity.createIntent(this))
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

        val stepsLabel = formatStepLabel(viewModel.uiState.value.selectedServiceTypes)

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
        val stepsLabel = formatStepLabel(viewModel.uiState.value.selectedServiceTypes)
        val primaryType = viewModel.uiState.value.selectedServiceTypes.firstOrNull() ?: ServiceType.ROUND_1
        val isSprayer = primaryType.isSpray
        val granularRate = viewModel.getGranularRate(primaryType)
        val dp = resources.displayMetrics.density

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (pad / 2), pad, 0)
        }
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.dialog_complete_message, client.address, minutesOnSite, stepsLabel)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        val amount1Input = android.widget.EditText(this).apply {
            hint = if (isSprayer) "Hose (gal)" else "Lbs used"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(amount1Input)

        val amount2Input = android.widget.EditText(this).apply {
            hint = "PG (gal)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            visibility = if (isSprayer) View.VISIBLE else View.GONE
        }
        container.addView(amount2Input)

        val estLabel = android.widget.TextView(this).apply {
            visibility = View.GONE
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        container.addView(estLabel)

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val a1 = amount1Input.text?.toString()?.toDoubleOrNull()
                val a2 = amount2Input.text?.toString()?.toDoubleOrNull()
                val est = if (isSprayer) estimateSpraySqFt(a1, a2) else estimateGranularSqFt(a1, granularRate)
                if (est != null) { estLabel.text = "≈ %,d sqft".format(est); estLabel.visibility = View.VISIBLE }
                else estLabel.visibility = View.GONE
            }
        }
        amount1Input.addTextChangedListener(watcher)
        amount2Input.addTextChangedListener(watcher)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_complete_title, client.name))
            .setView(container)
            .setPositiveButton(R.string.dialog_yes_complete) { _, _ ->
                val amt1 = amount1Input.text?.toString()?.toDoubleOrNull()
                val amt2 = amount2Input.text?.toString()?.toDoubleOrNull()
                lastLocation = location
                viewModel.markArrivalForClient(client, location, arrivedAtMillis)
                confirmServiceForSelectedClient(amountUsed = amt1, amountUsed2 = amt2)
                trackingUiController.dismissNotification(3000 + client.id.hashCode())
                DialogFactory.showPropertyStatsDialog(
                    context = this,
                    clientName = client.name,
                    initialProperty = propertyInputFor(client),
                    onSave = { property -> viewModel.writePropertyStats(client.name, property) },
                    onSkip = {}
                )
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
                    if (com.routeme.app.network.SheetsWriteBack.propertyWebAppUrl.isNotBlank()) {
                        showNextClusterPropertyDialog(selected, 0)
                    }
                }
                for (member in members) {
                    trackingUiController.dismissNotification(3000 + member.client.id.hashCode())
                }
                trackingUiController.dismissNotification(4000 + members.hashCode())
            }
        )
    }

    private fun showNextClusterPropertyDialog(members: List<ClusterMember>, index: Int) {
        if (index >= members.size) return
        val member = members[index]
        DialogFactory.showPropertyStatsDialog(
            context = this,
            clientName = member.client.name,
            initialProperty = propertyInputFor(member.client),
            onSave = { property ->
                viewModel.writePropertyStats(member.client.name, property)
                showNextClusterPropertyDialog(members, index + 1)
            },
            onSkip = { showNextClusterPropertyDialog(members, index + 1) }
        )
    }

    private fun handleArrivalIntent(intent: Intent?) {
        if (intent == null) return

        if (handleArrivalNotificationIntent(intent)) return
        if (handleCompletionNotificationIntent(intent)) return
        handleClusterCompletionNotificationIntent(intent)
    }

    private fun handleArrivalNotificationIntent(intent: Intent): Boolean {
        val arrivalClientId = intent.getStringExtra(LocationTrackingService.EXTRA_ARRIVAL_CLIENT_ID) ?: return false
        val client = clients.find { it.id == arrivalClientId } ?: return true
        val arrivedAt = intent.getLongExtra(LocationTrackingService.EXTRA_ARRIVAL_ARRIVED_AT, System.currentTimeMillis())
        val location = resolveNotificationLocation(
            intent,
            LocationTrackingService.EXTRA_ARRIVAL_LAT,
            LocationTrackingService.EXTRA_ARRIVAL_LNG,
            LocationTrackingService.EXTRA_ARRIVAL_TIME
        ) ?: return true

        showArrivalDialog(client, arrivedAt, location, fromNotification = true)
        clearIntentExtras(
            intent,
            LocationTrackingService.EXTRA_ARRIVAL_CLIENT_ID,
            LocationTrackingService.EXTRA_ARRIVAL_LAT,
            LocationTrackingService.EXTRA_ARRIVAL_LNG,
            LocationTrackingService.EXTRA_ARRIVAL_TIME,
            LocationTrackingService.EXTRA_ARRIVAL_ARRIVED_AT
        )
        return true
    }

    private fun handleCompletionNotificationIntent(intent: Intent): Boolean {
        val completeClientId = intent.getStringExtra(LocationTrackingService.EXTRA_COMPLETE_CLIENT_ID) ?: return false
        val minutes = intent.getIntExtra(LocationTrackingService.EXTRA_COMPLETE_MINUTES, 5)
        val arrivedAt = intent.getLongExtra(
            LocationTrackingService.EXTRA_COMPLETE_ARRIVED_AT,
            System.currentTimeMillis() - minutes * 60_000L
        )
        val client = clients.find { it.id == completeClientId } ?: return true
        val action = intent.getStringExtra(LocationTrackingService.EXTRA_COMPLETE_ACTION)

        // Property Stats action — show dialog but keep the notification alive
        if (action == LocationTrackingService.COMPLETE_ACTION_PROPERTY) {
            DialogFactory.showPropertyStatsDialog(
                context = this,
                clientName = client.name,
                initialProperty = propertyInputFor(client),
                onSave = { property -> viewModel.writePropertyStats(client.name, property) }
            )
            // Clear only the action extra so tapping the notification body still works
            intent.removeExtra(LocationTrackingService.EXTRA_COMPLETE_ACTION)
            return true
        }

        val location = resolveNotificationLocation(
            intent,
            LocationTrackingService.EXTRA_COMPLETE_LAT,
            LocationTrackingService.EXTRA_COMPLETE_LNG,
            LocationTrackingService.EXTRA_COMPLETE_TIME
        ) ?: return true

        showCompletionDialog(client, minutes * 60_000L, arrivedAt, location)
        clearIntentExtras(
            intent,
            LocationTrackingService.EXTRA_COMPLETE_CLIENT_ID,
            LocationTrackingService.EXTRA_COMPLETE_MINUTES,
            LocationTrackingService.EXTRA_COMPLETE_LAT,
            LocationTrackingService.EXTRA_COMPLETE_LNG,
            LocationTrackingService.EXTRA_COMPLETE_TIME,
            LocationTrackingService.EXTRA_COMPLETE_ARRIVED_AT
        )
        return true
    }

    private fun handleClusterCompletionNotificationIntent(intent: Intent) {
        val clusterClientIds = intent.getStringArrayExtra(LocationTrackingService.EXTRA_CLUSTER_CLIENT_IDS)
        if (clusterClientIds == null || clusterClientIds.size < 2) return

        val members = buildClusterMembers(intent, clusterClientIds)
        if (members.size >= 2) {
            showClusterCompletionDialog(members)
        }

        clearIntentExtras(
            intent,
            LocationTrackingService.EXTRA_CLUSTER_CLIENT_IDS,
            LocationTrackingService.EXTRA_CLUSTER_MINUTES,
            LocationTrackingService.EXTRA_CLUSTER_ARRIVED_AT,
            LocationTrackingService.EXTRA_CLUSTER_WEATHER_TEMP_F,
            LocationTrackingService.EXTRA_CLUSTER_WEATHER_WIND_MPH,
            LocationTrackingService.EXTRA_CLUSTER_WEATHER_DESC
        )
    }

    private fun buildClusterMembers(intent: Intent, clusterClientIds: Array<String>): List<ClusterMember> {
        val minutesArray = intent.getIntArrayExtra(LocationTrackingService.EXTRA_CLUSTER_MINUTES)
            ?: IntArray(clusterClientIds.size) { 5 }
        val arrivedAtArray = intent.getLongArrayExtra(LocationTrackingService.EXTRA_CLUSTER_ARRIVED_AT)
        val weatherTempArray = intent.getIntArrayExtra(LocationTrackingService.EXTRA_CLUSTER_WEATHER_TEMP_F)
        val weatherWindArray = intent.getIntArrayExtra(LocationTrackingService.EXTRA_CLUSTER_WEATHER_WIND_MPH)
        val weatherDescArray = intent.getStringArrayExtra(LocationTrackingService.EXTRA_CLUSTER_WEATHER_DESC)
        val location = trackingEventBus.latestLocation.value ?: getCurrentLocation()
        val now = System.currentTimeMillis()

        return clusterClientIds.mapIndexedNotNull { index, id ->
            val client = clients.find { it.id == id } ?: return@mapIndexedNotNull null
            val mins = minutesArray.getOrElse(index) { 5 }
            val arrivedAt = arrivedAtArray?.getOrElse(index) { now - mins * 60_000L } ?: (now - mins * 60_000L)
            val weatherTemp = weatherTempArray
                ?.getOrElse(index) { Int.MIN_VALUE }
                ?.takeUnless { it == Int.MIN_VALUE }
            val weatherWind = weatherWindArray
                ?.getOrElse(index) { Int.MIN_VALUE }
                ?.takeUnless { it == Int.MIN_VALUE }
            val weatherDesc = weatherDescArray
                ?.getOrElse(index) { "" }
                ?.takeIf { it.isNotBlank() }

            ClusterMember(
                client = client,
                timeOnSiteMillis = mins * 60_000L,
                arrivedAtMillis = arrivedAt,
                location = location ?: Location("notification"),
                weatherTempF = weatherTemp,
                weatherWindMph = weatherWind,
                weatherDesc = weatherDesc
            )
        }
    }

    private fun resolveNotificationLocation(
        intent: Intent,
        latKey: String,
        lngKey: String,
        timeKey: String
    ): Location? {
        return locationFromIntent(intent, latKey, lngKey, timeKey)
            ?: trackingEventBus.latestLocation.value
            ?: getCurrentLocation()
    }

    private fun clearIntentExtras(intent: Intent, vararg keys: String) {
        keys.forEach(intent::removeExtra)
    }

    private fun propertyInputFor(client: Client): PropertyInput {
        val property = client.property

        val sunShade = when {
            property == null -> client.sunShade
            property.sunShade == SunShade.FULL_SUN -> "Full Sun"
            property.sunShade == SunShade.PARTIAL_SHADE -> "Partial Shade"
            property.sunShade == SunShade.FULL_SHADE -> "Full Shade"
            else -> client.sunShade
        }

        val windExposure = when {
            property == null -> client.windExposure
            property.windExposure == WindExposure.EXPOSED -> "Exposed"
            property.windExposure == WindExposure.SHELTERED -> "Sheltered"
            property.windExposure == WindExposure.MIXED -> "Mixed"
            else -> client.windExposure
        }

        val steepSlopes = when {
            property != null && property.hasSteepSlopes -> "Yes"
            property != null && !property.hasSteepSlopes -> "No"
            client.terrain.isBlank() -> ""
            client.terrain.lowercase().contains("flat") ||
                client.terrain.lowercase().contains("level") ||
                client.terrain.lowercase().contains("no slope") -> "No"
            else -> "Yes"
        }

        val irrigation = when {
            property == null -> ""
            property.hasIrrigation -> "Yes"
            else -> "No"
        }

        return PropertyInput(
            sunShade = sunShade,
            windExposure = windExposure,
            steepSlopes = steepSlopes,
            irrigation = irrigation
        )
    }

}
