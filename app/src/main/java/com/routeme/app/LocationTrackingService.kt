package com.routeme.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Foreground service that continuously tracks the user's location
 * and detects when they've stopped near a client (dwell detection).
 *
 * Uses adaptive GPS tracking:
 * - DRIVING mode: Low power (20s interval, 25m movement gate, GPS only)
 * - ARRIVAL mode: High accuracy (5s interval, 0m gate, GPS + network)
 *   Activates within 200m of any client.
 *
 * Handles large yards (150m on-site radius) and adjacent client clusters
 * (won't fire departure while working 2-3 neighboring properties).
 */
class LocationTrackingService : Service(), KoinComponent {

    companion object {
        private const val TAG = "LocationTracking"
        private const val CHANNEL_ID = "routeme_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val ARRIVAL_NOTIF_BASE = 2000
        private const val COMPLETE_NOTIF_BASE = 3000
        private const val CLUSTER_NOTIF_BASE = 4000
        const val EXTRA_ARRIVAL_CLIENT_ID = "arrival_client_id"
        const val EXTRA_ARRIVAL_LAT = "arrival_lat"
        const val EXTRA_ARRIVAL_LNG = "arrival_lng"
        const val EXTRA_ARRIVAL_TIME = "arrival_time"
        const val EXTRA_ARRIVAL_ARRIVED_AT = "arrival_arrived_at"
        const val EXTRA_COMPLETE_CLIENT_ID = "complete_client_id"
        const val EXTRA_COMPLETE_MINUTES = "complete_minutes"
        const val EXTRA_COMPLETE_LAT = "complete_lat"
        const val EXTRA_COMPLETE_LNG = "complete_lng"
        const val EXTRA_COMPLETE_TIME = "complete_time"
        const val EXTRA_COMPLETE_ARRIVED_AT = "complete_arrived_at"
        const val EXTRA_CLUSTER_CLIENT_IDS = "cluster_client_ids"
        const val EXTRA_CLUSTER_MINUTES = "cluster_minutes"
        const val EXTRA_CLUSTER_ARRIVED_AT = "cluster_arrived_at"

        // --- Detection radii ---
        private const val ARRIVAL_RADIUS_METERS = 60f    // Initial dwell detection (must stop near property)
        private const val ONSITE_RADIUS_METERS = 150f    // On-site while working (large yards)
        private const val MODE_SWITCH_RADIUS = 200f       // Switch to arrival mode when this close
        private const val CLUSTER_RADIUS_METERS = 200f   // Adjacent clients treated as one work area

        // --- Timing ---
        private const val DWELL_THRESHOLD_MS = 60_000L   // 60s stopped → "Arrived?" prompt
        private const val JOB_MIN_DURATION_MS = 3 * 60 * 1000L  // 3 min on site → "Mark complete?"
        private const val MODE_SWITCH_COOLDOWN_MS = 10_000L  // Prevent rapid mode toggling

        // --- Driving mode (low power) ---
        private const val DRIVING_INTERVAL_MS = 30_000L  // GPS every 30s
        private const val DRIVING_MIN_DISTANCE = 25f     // Only update if moved 25m

        // --- Arrival mode (high accuracy) ---
        private const val ARRIVAL_INTERVAL_MS = 10_000L  // GPS every 10s
        private const val ARRIVAL_MIN_DISTANCE = 0f      // Every tick (needed for dwell detection)

        // --- Conditional network fallback in arrival mode ---
        private const val GPS_STALE_FOR_FALLBACK_MS = 25_000L
        private const val GPS_WEAK_ACCURACY_METERS = 45f
        private const val PROVIDER_REFRESH_COOLDOWN_MS = 30_000L

        // --- Non-client stop detection ---
        private const val NON_CLIENT_STOP_RADIUS_METERS = 60f   // Stationary threshold radius
        private const val NON_CLIENT_DEPART_RADIUS_METERS = 80f // Leave radius (slightly wider to avoid flicker)

        // --- Destination dwell detection ---
        private const val DESTINATION_RADIUS_METERS = 150f      // Near-destination radius
        private const val DESTINATION_DWELL_MS = 3 * 60 * 1000L // 3 min dwell → arrived
    }

    private var locationManager: LocationManager? = null

    // Core collaborators
    private val modeController = TrackingModeController(
        modeSwitchCooldownMs = MODE_SWITCH_COOLDOWN_MS,
        gpsStaleForFallbackMs = GPS_STALE_FOR_FALLBACK_MS,
        gpsWeakAccuracyMeters = GPS_WEAK_ACCURACY_METERS,
        providerRefreshCooldownMs = PROVIDER_REFRESH_COOLDOWN_MS
    )
    private val arrivalDepartureEngine = ArrivalDepartureEngine()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackedClients: List<Client> = emptyList()

    // Injected dependencies
    private val clientRepository: ClientRepository by inject()
    private val trackingEventBus: TrackingEventBus by inject()
    private val preferencesRepository: PreferencesRepository by inject()
    private val nonClientStopDao: NonClientStopDao by inject()

    // Extracted helpers
    private val nonClientStopTracker = NonClientStopTracker(
        nonClientStopRadiusMeters = NON_CLIENT_STOP_RADIUS_METERS,
        nonClientDepartRadiusMeters = NON_CLIENT_DEPART_RADIUS_METERS
    )

    // Destination dwell state
    private var destDwellStartMs: Long = 0L
    private var destDwellLat: Double = 0.0
    private var destDwellLng: Double = 0.0
    private var destDwellFired: Boolean = false
    private val trackingNotifier by lazy {
        LocationTrackingNotifier(
            context = this,
            channelId = CHANNEL_ID,
            arrivalNotifBase = ARRIVAL_NOTIF_BASE,
            completeNotifBase = COMPLETE_NOTIF_BASE,
            clusterNotifBase = CLUSTER_NOTIF_BASE
        )
    }

    private val locationListener = LocationListener { location ->
        if (location.provider == LocationManager.GPS_PROVIDER) {
            modeController.recordGpsFix(
                nowMillis = System.currentTimeMillis(),
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
            )
        }
        trackingEventBus.setLatestLocation(location)
        trackingEventBus.tryEmit(TrackingEvent.LocationUpdated(location))
        Log.d(TAG, "Location update [${modeController.currentMode}]: ${location.latitude}, ${location.longitude}")
        checkAndSwitchMode(location)
        checkForClientArrival(location)
        checkForDepartures(location)
        checkForNonClientStop(location)
        checkForDestinationArrival(location)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = trackingNotifier.buildTrackingNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            refreshTrackedClients()
        }
        startLocationUpdates()
        trackingEventBus.setTrackingActive(true)
        Log.d(TAG, "Location tracking started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager?.removeUpdates(locationListener)
        serviceScope.cancel()
        trackingEventBus.setTrackingActive(false)
        trackingEventBus.setLatestLocation(null)
        trackedClients = emptyList()
        clearNonClientState()
        arrivalDepartureEngine.reset()
        modeController.reset()
        Log.d(TAG, "Location tracking stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun refreshTrackedClients() {
        trackedClients = runCatching { clientRepository.loadAllClients() }
            .onFailure { Log.w(TAG, "Failed to load tracked clients: ${it.message}") }
            .getOrDefault(emptyList())
        Log.d(TAG, "Tracking service loaded ${trackedClients.size} clients")
    }

    private fun startLocationUpdates() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val hasFine = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine) {
            Log.w(TAG, "No fine location permission, stopping service")
            stopSelf()
            return
        }

        // Start in driving mode (low power)
        applyTrackingMode(TrackingMode.DRIVING)
    }

    /**
     * Switches GPS provider settings based on tracking mode.
     * DRIVING = low power (20s, 25m, GPS only)
     * ARRIVAL = high accuracy (5s, 0m, GPS + network)
     */
    private fun applyTrackingMode(mode: TrackingMode) {
        val hasFine = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) return

        // Remove existing listeners before re-registering
        locationManager?.removeUpdates(locationListener)

        val (interval, minDist) = when (mode) {
            TrackingMode.DRIVING -> DRIVING_INTERVAL_MS to DRIVING_MIN_DISTANCE
            TrackingMode.ARRIVAL -> ARRIVAL_INTERVAL_MS to ARRIVAL_MIN_DISTANCE
        }

        // Always register GPS
        runCatching {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, interval, minDist, locationListener
            )
        }

        // Network fallback only in arrival mode when GPS is weak/stale/unavailable
        val now = System.currentTimeMillis()
        val shouldEnableNetworkFallback =
            mode == TrackingMode.ARRIVAL && modeController.shouldUseNetworkFallback(
                gpsEnabled = isGpsProviderEnabled(),
                nowMillis = now
            )
        if (shouldEnableNetworkFallback) {
            runCatching {
                val networkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
                if (networkEnabled) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, interval, minDist, locationListener
                    )
                }
            }
        }

        modeController.onTrackingModeApplied(mode, shouldEnableNetworkFallback, now)
        Log.d(
            TAG,
            "Tracking mode → $mode (interval=${interval}ms, minDist=${minDist}m, networkFallback=${modeController.networkFallbackActive})"
        )
    }

    private fun maybeRefreshArrivalProviders() {
        val now = System.currentTimeMillis()
        if (!modeController.isArrivalRefreshWindowOpen(now)) return

        val shouldEnableNetworkFallback = modeController.shouldUseNetworkFallback(
            gpsEnabled = isGpsProviderEnabled(),
            nowMillis = now
        )
        if (shouldEnableNetworkFallback != modeController.networkFallbackActive) {
            Log.d(
                TAG,
                "Refreshing ARRIVAL providers (networkFallback: ${modeController.networkFallbackActive} → $shouldEnableNetworkFallback)"
            )
            applyTrackingMode(TrackingMode.ARRIVAL)
        } else {
            modeController.markProviderRefreshCheck(now)
        }
    }

    /**
     * Checks if we should switch between DRIVING and ARRIVAL mode.
     * Switches to ARRIVAL when within 200m of any client.
     * Switches back to DRIVING when >200m from all clients AND all active arrivals.
     */
    private fun checkAndSwitchMode(location: Location) {
        val now = System.currentTimeMillis()
        // Cooldown to prevent rapid toggling at the boundary
        if (!modeController.canEvaluateModeSwitch(now)) return

        val nearAnyClient = isNearAnyClient(location, MODE_SWITCH_RADIUS)
        // Also stay in arrival mode while we have active arrivals (working on-site)
        val hasActiveWork = arrivalDepartureEngine.hasActiveArrivals()

        when {
            modeController.currentMode == TrackingMode.DRIVING && (nearAnyClient || hasActiveWork) -> {
                applyTrackingMode(TrackingMode.ARRIVAL)
            }
            modeController.currentMode == TrackingMode.ARRIVAL && !nearAnyClient && !hasActiveWork -> {
                applyTrackingMode(TrackingMode.DRIVING)
            }
        }

        maybeRefreshArrivalProviders()
    }

    private fun isGpsProviderEnabled(): Boolean {
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }

    /**
     * Returns true if the location is within [radiusMeters] of any client.
     */
    private fun isNearAnyClient(location: Location, radiusMeters: Float): Boolean {
        return ClientProximityHelper.isNearAnyClient(location, trackedClients, radiusMeters)
    }

    private fun checkForClientArrival(location: Location) {
        val now = System.currentTimeMillis()
        val prompt = arrivalDepartureEngine.evaluateArrival(
            location = location,
            trackedClients = trackedClients,
            arrivalRadiusMeters = ARRIVAL_RADIUS_METERS,
            dwellThresholdMs = DWELL_THRESHOLD_MS,
            nowMillis = now
        )

        if (prompt != null) {
            Log.d(TAG, "Dwell threshold reached at ${prompt.client.name}! Triggering arrival.")
            val handler = android.os.Handler(mainLooper)
            handler.post {
                Log.d(TAG, "Firing arrival event for ${prompt.client.name}")
                trackingNotifier.postArrivalNotification(prompt.client, prompt.location, prompt.arrivedAtMillis)
                trackingEventBus.tryEmit(
                    TrackingEvent.ClientArrival(prompt.client, prompt.arrivedAtMillis, prompt.location)
                )
            }
        }
    }

    /**
     * Checks if the user has departed from any client they previously arrived at.
     * Uses [ONSITE_RADIUS_METERS] (150m) to accommodate large yards.
     * Won't fire departure if the user is still near adjacent clients in the same cluster.
     *
     * When 2+ clients depart in the same tick (cluster scenario), a single
     * [TrackingEvent.ClusterComplete] is emitted instead of individual JobComplete events.
     */
    private fun checkForDepartures(location: Location) {
        val now = System.currentTimeMillis()
        val evaluation = arrivalDepartureEngine.evaluateDepartures(
            location = location,
            trackedClients = trackedClients,
            onSiteRadiusMeters = ONSITE_RADIUS_METERS,
            clusterRadiusMeters = CLUSTER_RADIUS_METERS,
            jobMinDurationMs = JOB_MIN_DURATION_MS,
            nowMillis = now
        )

        for (clientId in evaluation.departedClientIds) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.cancel(ARRIVAL_NOTIF_BASE + clientId.hashCode())
        }

        val completable = evaluation.completionCandidates

        // --- Emit completions: batch if cluster, individual if single ---
        if (completable.size >= 2) {
            val members = completable.map { s ->
                ClusterMember(
                    client = s.client,
                    timeOnSiteMillis = s.timeOnSiteMillis,
                    arrivedAtMillis = s.arrivedAtMillis,
                    location = s.location
                )
            }
            val names = members.joinToString(", ") { it.client.name }
            Log.d(TAG, "Cluster departure: ${members.size} clients ($names)")

            val handler = android.os.Handler(mainLooper)
            handler.post {
                val notifId = trackingNotifier.postClusterCompletionNotification(members)
                Log.d(TAG, "Posted cluster completion notification for ${members.size} clients ($names, notifId=$notifId)")
                trackingEventBus.tryEmit(TrackingEvent.ClusterComplete(members))
            }
        } else if (completable.size == 1) {
            val s = completable[0]
            val timeOnSite = s.timeOnSiteMillis
            val minutesOnSite = (timeOnSite / 60_000).toInt()

            val handler = android.os.Handler(mainLooper)
            handler.post {
                Log.d(TAG, "Firing job complete for ${s.client.name} (${minutesOnSite}min)")
                val notifId = COMPLETE_NOTIF_BASE + s.client.id.hashCode()
                trackingNotifier.postCompletionNotification(s.client, minutesOnSite, s.location, s.arrivedAtMillis)
                Log.d(TAG, "Posted completion notification for ${s.client.name} (${minutesOnSite}min, notifId=$notifId)")
                trackingEventBus.tryEmit(TrackingEvent.JobComplete(s.client, timeOnSite, s.arrivedAtMillis, s.location))
            }
        }
    }

    // ─── Non-client stop detection ─────────────────────────────

    /**
     * Detects when the user is stationary at a non-client location for longer
     * than the configured threshold. Silently inserts a record into the DB
     * and reverse-geocodes the address asynchronously.
     *
     * State machine:
     * 1. No active arrival & no nearby client → start pending timer
     * 2. Still stationary after threshold → insert NonClientStop, promote to active
     * 3. Move away from active stop → update departure time
     * 4. Enter a client radius → cancel pending (it was approach time)
     */
    private fun checkForNonClientStop(location: Location) {
        // Feature toggle
        if (!preferencesRepository.nonClientLoggingEnabled) {
            clearNonClientState()
            return
        }

        val now = System.currentTimeMillis()
        val thresholdMs = preferencesRepository.nonClientStopThresholdMinutes * 60_000L
        val nearClient = isNearAnyClient(location, ARRIVAL_RADIUS_METERS)

        val outcome = nonClientStopTracker.onLocationTick(
            location = location,
            nowMillis = now,
            isNearClientOrActiveArrival = nearClient || arrivalDepartureEngine.hasActiveArrivals(),
            thresholdMs = thresholdMs
        )

        if (outcome.pendingCancelledNearClient) {
            Log.d(TAG, "Non-client dwell cancelled — near a client")
        }

        outcome.pendingResetDistanceMeters?.let {
            Log.d(TAG, "Non-client dwell reset — moved ${it.toInt()}m")
        }

        outcome.closeActive?.let { request ->
            closeActiveNonClientStop(request)
        }

        outcome.createStop?.let { request ->
            Log.d(
                TAG,
                "Non-client stop detected! Stationary ${request.elapsedMillis / 60_000}min at (${request.lat}, ${request.lng})"
            )
            val entity = NonClientStopEntity(
                lat = request.lat,
                lng = request.lng,
                arrivedAtMillis = request.arrivedAtMillis
            )

            serviceScope.launch {
                try {
                    val id = nonClientStopDao.insertStop(entity)
                    nonClientStopTracker.onStopInserted(id, request.lat, request.lng, request.arrivedAtMillis)
                    Log.d(TAG, "Non-client stop #$id inserted")

                    val address = GeocodingHelper.reverseGeocode(request.lat, request.lng)
                    if (address != null) {
                        nonClientStopDao.updateAddress(id, address)
                        Log.d(TAG, "Non-client stop #$id address: $address")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to log non-client stop: ${e.message}")
                }
            }
        }
    }

    private fun closeActiveNonClientStop(request: CloseActiveStopRequest) {
        val duration = (request.departedAtMillis - request.arrivedAtMillis) / 60_000L
        Log.d(TAG, "Closing non-client stop #${request.stopId} (${duration}min)")
        serviceScope.launch {
            try {
                nonClientStopDao.updateDeparture(request.stopId, request.departedAtMillis, duration)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close non-client stop #${request.stopId}: ${e.message}")
            }
        }
    }

    private fun clearNonClientState() {
        val closeRequest = nonClientStopTracker.clearAll(System.currentTimeMillis())
        if (closeRequest != null) {
            closeActiveNonClientStop(closeRequest)
        }
    }

    // ─── Destination dwell detection ───────────────────────────

    /**
     * Detects when the user has been stationary at the active destination for
     * [DESTINATION_DWELL_MS] (3 min). Fires [TrackingEvent.DestinationReached]
     * and inserts a labeled non-client stop record.
     *
     * Skipped when near a tracked client (doing a job on the way).
     */
    private fun checkForDestinationArrival(location: Location) {
        val dest = preferencesRepository.activeDestination ?: run {
            resetDestDwell()
            return
        }

        val nearClient = isNearAnyClient(location, ARRIVAL_RADIUS_METERS)
        if (nearClient || arrivalDepartureEngine.hasActiveArrivals()) {
            resetDestDwell()
            return
        }

        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, dest.lat, dest.lng, results)
        val distMeters = results[0]

        if (distMeters > DESTINATION_RADIUS_METERS) {
            resetDestDwell()
            return
        }

        val now = System.currentTimeMillis()

        if (destDwellStartMs == 0L) {
            destDwellStartMs = now
            destDwellLat = location.latitude
            destDwellLng = location.longitude
            return
        }

        if (destDwellFired) return

        val elapsed = now - destDwellStartMs
        if (elapsed >= DESTINATION_DWELL_MS) {
            destDwellFired = true
            Log.d(TAG, "Destination reached: ${dest.name} (${elapsed / 1000}s dwell)")

            // Insert a labeled non-client stop
            val entity = NonClientStopEntity(
                lat = destDwellLat,
                lng = destDwellLng,
                address = dest.address,
                arrivedAtMillis = destDwellStartMs,
                label = dest.name
            )
            serviceScope.launch {
                try {
                    nonClientStopDao.insertStop(entity)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to log destination stop: ${e.message}")
                }
            }

            val handler = android.os.Handler(mainLooper)
            handler.post {
                trackingEventBus.tryEmit(
                    TrackingEvent.DestinationReached(dest.name, destDwellStartMs, location)
                )
            }
        }
    }

    private fun resetDestDwell() {
        destDwellStartMs = 0L
        destDwellFired = false
    }

    // ─── Notifications ─────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

}
