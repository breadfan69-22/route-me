package com.routeme.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import android.app.AlertDialog
import android.widget.EditText as AndroidEditText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.routeme.app.databinding.ActivityMainBinding
import com.routeme.app.ui.MainEvent
import com.routeme.app.ui.MainViewModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var clients = mutableListOf<Client>()
    private var selectedClient: Client? = null
    private var lastLocation: Location? = null
    private lateinit var binding: ActivityMainBinding

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topToolbar)

        setupStepToggle()
        setupActions()
        observeViewModel()
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
                            binding.statusText.text = state.statusText
                        }
                        if (state.selectedClientDetails.isNotBlank()) {
                            binding.clientDetailsText.text = state.selectedClientDetails
                        }
                        binding.trackingButton.text = if (state.isTracking) {
                            getString(R.string.stop_tracking)
                        } else {
                            getString(R.string.start_tracking)
                        }
                        binding.arrivedButton.text = if (state.arrivalStartedAtMillis != null) {
                            getString(R.string.btn_cancel)
                        } else {
                            getString(R.string.btn_arrived)
                        }
                        // Show/hide visit notes field based on arrival state
                        binding.visitNotesLayout.visibility = if (state.arrivalStartedAtMillis != null) View.VISIBLE else View.GONE
                        if (state.arrivalStartedAtMillis == null) {
                            binding.visitNotesInput.text?.clear()
                        }
                        // Grey out toggle buttons for fully completed steps
                        updateStepToggleEnabled(state.completedSteps)
                        showCurrentPage()
                    }
                }
                launch {
                    viewModel.events.collect { event ->
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
                            MainEvent.ServiceConfirmed -> Unit
                        }
                    }
                }
                launch {
                    trackingEventBus.events.collect { event ->
                        when (event) {
                            is TrackingEvent.ClientArrival -> showArrivalDialog(event.client, event.arrivedAtMillis, event.location)
                            is TrackingEvent.JobComplete -> showCompletionDialog(event.client, event.timeOnSiteMillis, event.arrivedAtMillis, event.location)
                            is TrackingEvent.ClusterComplete -> showClusterCompletionDialog(event.members)
                            is TrackingEvent.LocationUpdated -> Unit
                        }
                    }
                }
            }
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
        syncTrackingState()
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
        val checked = binding.stepToggleGroup.checkedButtonIds
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
        binding.stepToggleGroup.clearChecked()
        for (type in savedTypes) {
            serviceTypeToButtonId[type]?.let { binding.stepToggleGroup.check(it) }
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
        binding.trackingButton.setOnClickListener {
            toggleTracking()
        }

        binding.suggestButton.setOnClickListener {
            viewModel.setServiceTypes(getSelectedServiceTypes())
            suggestNextClients()
        }

        binding.moreSuggestionsButton.setOnClickListener {
            viewModel.nextSuggestionPage()
            showCurrentPage()
        }

        binding.prevSuggestionsButton.setOnClickListener {
            viewModel.previousSuggestionPage()
            showCurrentPage()
        }

        binding.mapsButton.setOnClickListener {
            openSelectedInMaps()
        }

        binding.arrivedButton.setOnClickListener {
            if (viewModel.uiState.value.arrivalStartedAtMillis != null) {
                viewModel.cancelArrival()
                return@setOnClickListener
            }

            lastLocation = getCurrentLocation()
            viewModel.startArrivalForSelected(lastLocation)
        }

        binding.confirmButton.setOnClickListener {
            confirmSelectedClientService()
        }

        binding.skipButton.setOnClickListener {
            viewModel.skipSelectedClientToday()
        }
    }

    private fun suggestNextClients() {
        // Prefer a fresh location fix so ranking reflects current position.
        lastLocation = getBestSuggestionLocation() ?: lastLocation
        viewModel.suggestNextClients(lastLocation)
    }

    private fun showCurrentPage() {
        binding.suggestionsContainer.removeAllViews()

        val state = viewModel.uiState.value
        val page = viewModel.currentPageSuggestions()
        if (page.isEmpty()) {
            binding.paginationRow.visibility = View.GONE
            return
        }

        // Header
        val stepsLabel = state.selectedServiceTypes.joinToString("+") { it.label }
        val header = TextView(this).apply {
            text = getString(
                R.string.suggestion_header,
                stepsLabel,
                state.minDays,
                state.suggestionOffset + 1,
                state.suggestionOffset + page.size,
                state.suggestions.size
            )
            setTypeface(null, Typeface.BOLD)
            textSize = 13f
        }
        binding.suggestionsContainer.addView(header)

        // Add a tappable row for each suggestion
        page.forEachIndexed { i, suggestion ->
            val globalIndex = state.suggestionOffset + i
            val row = buildSuggestionRow(suggestion, globalIndex)
            binding.suggestionsContainer.addView(row)
        }

        // Show/hide pagination row
        val hasMore = viewModel.canShowMoreSuggestions()
        val hasPrev = viewModel.canShowPreviousSuggestions()
        binding.paginationRow.visibility = if (hasMore || hasPrev) View.VISIBLE else View.GONE
        binding.prevSuggestionsButton.isEnabled = hasPrev
        binding.moreSuggestionsButton.isEnabled = hasMore
    }

    private fun buildSuggestionRow(suggestion: ClientSuggestion, index: Int): MaterialButton {
        val daysText = suggestion.daysSinceLast?.toString() ?: "Never"
        val driveText = suggestion.drivingTime
        val distText = when {
            driveText != null -> "${suggestion.drivingDistance} (${driveText})"
            suggestion.distanceMiles != null -> String.format(Locale.US, "%.1f mi", suggestion.distanceMiles)
            else -> ""
        }
        val mowText = if (suggestion.mowWindowPreferred) " \u2713mow" else ""
        val cuText = if (suggestion.requiresCuOverride) " \u26a0CU" else ""
        // Show step tag when in combo mode (e.g. [S1] or [S1+2])
        val stepTag = if (suggestion.eligibleSteps.size == 1 &&
            viewModel.uiState.value.selectedServiceTypes.size == 1
        ) {
            "" // Single-step mode: no tag needed
        } else {
            val nums = suggestion.eligibleSteps
                .filter { it.stepNumber > 0 }
                .map { it.stepNumber }
                .sorted()
            if (nums.isNotEmpty()) "[S${nums.joinToString("+")}] " else ""
        }
        val label = "${index + 1}. $stepTag${suggestion.client.name}  \u2022  ${daysText}d  \u2022  $distText$mowText$cuText".trim()

        val isSelected = selectedClient?.id == suggestion.client.id

        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (4 * resources.displayMetrics.density).toInt()
            layoutParams = lp

            if (isSelected) {
                // Filled-tonal look for the active selection
                setBackgroundColor(ContextCompat.getColor(context, R.color.suggestion_selected_bg))
                setTextColor(ContextCompat.getColor(context, R.color.md_theme_dark_onPrimaryContainer))
                strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.suggestion_selected_stroke))
                strokeWidth = (2 * resources.displayMetrics.density).toInt()
                setTypeface(null, Typeface.BOLD)
            } else {
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
            }

            setOnClickListener {
                selectSuggestion(suggestion)
                showCurrentPage()
            }
        }
    }

    private fun selectSuggestion(suggestion: ClientSuggestion) {
        viewModel.selectSuggestion(suggestion.client.id)
        selectedClient = suggestion.client
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
        val notes = binding.visitNotesInput.text?.toString().orEmpty()
        viewModel.confirmSelectedClientService(current, notes)
    }

    private fun showDailySummaryDialog(summary: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_daily_summary_title))
            .setMessage(summary)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showRouteHistoryDialog(event: MainEvent.ShowRouteHistory) {
        // Build a custom layout with prev/next navigation
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        // Nav row: < Prev | date label | Next >
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val prevBtn = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "\u25C0 Older"
            textSize = 12f
            isAllCaps = false
            isEnabled = event.hasPrevDay
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dateLabel = TextView(this).apply {
            text = event.dateLabel
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }

        val nextBtn = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Newer \u25B6"
            textSize = 12f
            isAllCaps = false
            isEnabled = event.hasNextDay
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        navRow.addView(prevBtn)
        navRow.addView(dateLabel)
        navRow.addView(nextBtn)
        container.addView(navRow)

        // Separator
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 12; bottomMargin = 12 }
            setBackgroundColor(0x33FFFFFF)
        })

        // Summary text
        val summaryView = TextView(this).apply {
            text = event.summary
            textSize = 13f
            setLineSpacing(0f, 1.2f)
        }
        container.addView(summaryView)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_route_history_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        // Wire up navigation — dismiss and show next/prev
        prevBtn.setOnClickListener {
            dialog.dismiss()
            viewModel.navigateHistory(event.dateMillis, 1)  // +1 = older
        }
        nextBtn.setOnClickListener {
            dialog.dismiss()
            viewModel.navigateHistory(event.dateMillis, -1) // -1 = newer
        }
    }

    private fun showEditNotesDialog(clientId: String, clientName: String, currentNotes: String) {
        val editText = AndroidEditText(this).apply {
            setText(currentNotes)
            hint = "Notes for $clientName"
            setPadding(48, 24, 48, 24)
            minLines = 2
            maxLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Notes – $clientName")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                viewModel.saveClientNotes(clientId, editText.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showStaleArrivalDialog(clientName: String, minutesElapsed: Long) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_stale_arrival_title))
            .setMessage(getString(R.string.dialog_stale_arrival_message, clientName, minutesElapsed))
            .setPositiveButton(getString(R.string.dialog_stale_mark_complete)) { _, _ ->
                val location = getCurrentLocation()
                lastLocation = location
                val notes = binding.visitNotesInput.text?.toString().orEmpty()
                viewModel.resolveStaleArrival(markComplete = true, currentLocation = location, visitNotes = notes)
            }
            .setNegativeButton(getString(R.string.dialog_stale_discard)) { _, _ ->
                viewModel.resolveStaleArrival(markComplete = false)
            }
            .setNeutralButton(getString(R.string.dialog_stale_go_back)) { _, _ ->
                viewModel.dropPendingStaleAction()
            }
            .setCancelable(false)
            .show()
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
        val input = AndroidEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            hint = "Minutes (1–30)"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_break_threshold_title))
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val mins = input.text.toString().toIntOrNull() ?: current
                viewModel.setNonClientStopThreshold(mins)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMinDaysDialog() {
        val current = viewModel.uiState.value.minDays
        val input = AndroidEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            hint = "Days (1–90)"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_min_days_title))
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val days = input.text.toString().toIntOrNull() ?: current
                viewModel.setMinDays(days)
                rerunSuggestionsIfVisible()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rerunSuggestionsIfVisible() {
        if (clients.isNotEmpty() && viewModel.uiState.value.suggestions.isNotEmpty()) {
            suggestNextClients()
        }
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val syncInput = AndroidEditText(this).apply {
            hint = getString(R.string.dialog_sheets_read_hint)
            if (sheetsUrl.isNotBlank()) setText(sheetsUrl)
            textSize = 13f
        }
        layout.addView(syncInput)

        val writeInput = AndroidEditText(this).apply {
            hint = getString(R.string.dialog_sheets_write_hint)
            if (SheetsWriteBack.webAppUrl.isNotBlank()) setText(SheetsWriteBack.webAppUrl)
            textSize = 13f
        }
        layout.addView(writeInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_sheets_title)
            .setMessage(R.string.dialog_sheets_message)
            .setView(layout)
            .setPositiveButton(R.string.dialog_sync_now) { _, _ ->
                val enteredReadUrl = syncInput.text.toString().trim()
                val enteredWriteUrl = writeInput.text.toString().trim()

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
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
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

    private fun toggleTracking() {
        if (viewModel.uiState.value.isTracking) {
            stopTracking()
        } else {
            startTracking()
        }
    }

    private fun startTracking() {
        // Check / request fine location
        val hasFine = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                102
            )
            return
        }

        // On Android 13+ request notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    103
                )
                // Continue anyway — notification just won't show
            }
        }

        val intent = Intent(this, LocationTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        viewModel.setTrackingActive(true)
        arrivedClientIds.clear()
        viewModel.postStatus(getString(R.string.status_tracking_started))
    }

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

    private fun stopTracking() {
        stopService(Intent(this, LocationTrackingService::class.java))
        viewModel.setTrackingActive(false)
        arrivedClientIds.clear()
        viewModel.postStatus(getString(R.string.status_tracking_stopped))
    }

    /** Sync tracking state from service (e.g. after app restart) */
    private fun syncTrackingState() {
        viewModel.setTrackingActive(trackingEventBus.isTracking.value)
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
                dismissNotification(2000 + client.id.hashCode())
            }
            .setNegativeButton(R.string.dialog_not_here, null)
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
                dismissNotification(3000 + client.id.hashCode())
            }
            .setNegativeButton(R.string.dialog_not_yet, null)
            .setCancelable(false)
            .show()
    }

    /**
     * Shows a multi-select dialog for a cluster of 2+ nearby clients that were
     * all departed in the same GPS tick. All are pre-checked; user unchecks any
     * they didn't actually service.
     */
    private fun showClusterCompletionDialog(members: List<ClusterMember>) {
        val names = members.map { m ->
            val mins = (m.timeOnSiteMillis / 60_000).toInt().coerceAtLeast(1)
            "${m.client.name} (${mins}m)"
        }.toTypedArray()
        val checked = BooleanArray(members.size) { true }  // all pre-checked

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_cluster_title))
            .setMessage(getString(R.string.dialog_cluster_message, members.size))
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.dialog_cluster_confirm) { _, _ ->
                val selected = members.filterIndexed { i, _ -> checked[i] }
                if (selected.isNotEmpty()) {
                    viewModel.confirmClusterService(selected)
                }
                // Dismiss all completion/cluster notifications for these clients
                for (member in members) {
                    dismissNotification(3000 + member.client.id.hashCode())
                }
                dismissNotification(4000 + members.hashCode())
            }
            .setNegativeButton(R.string.dialog_cluster_cancel, null)
            .setCancelable(false)
            .show()
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

    private fun dismissNotification(notifId: Int) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.cancel(notifId)
    }
}
