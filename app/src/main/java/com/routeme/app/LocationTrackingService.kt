package com.routeme.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WeatherRepository
import com.routeme.app.tracking.ArrivalDispatchCoordinator
import com.routeme.app.tracking.DestinationArrivalCoordinator
import com.routeme.app.tracking.DestinationDwellDetector
import com.routeme.app.tracking.LocationDispatcher
import com.routeme.app.tracking.NonClientStopLogger
import com.routeme.app.util.AppConfig
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
 * - DRIVING mode: Low power (30s interval, 25m movement gate, GPS only)
 * - ARRIVAL mode: High accuracy (10s interval, 0m gate, GPS + network fallback)
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
        const val ACTION_REFRESH_TRACKED_CLIENTS = "com.routeme.app.action.REFRESH_TRACKED_CLIENTS"
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
        const val EXTRA_COMPLETE_ACTION = "complete_action"
        const val COMPLETE_ACTION_PROMPT = "complete_action_prompt"
        const val COMPLETE_ACTION_DONE = "complete_action_done"
        const val COMPLETE_ACTION_NOT_YET = "complete_action_not_yet"
        const val COMPLETE_ACTION_PROPERTY = "complete_action_property"
        const val EXTRA_CLUSTER_CLIENT_IDS = "cluster_client_ids"
        const val EXTRA_CLUSTER_MINUTES = "cluster_minutes"
        const val EXTRA_CLUSTER_ARRIVED_AT = "cluster_arrived_at"
        const val EXTRA_CLUSTER_WEATHER_TEMP_F = "cluster_weather_temp_f"
        const val EXTRA_CLUSTER_WEATHER_WIND_MPH = "cluster_weather_wind_mph"
        const val EXTRA_CLUSTER_WEATHER_DESC = "cluster_weather_desc"
    }

    private var locationDispatcher: LocationDispatcher? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Core collaborators
    private val modeController = TrackingModeController(
        modeSwitchCooldownMs = AppConfig.Tracking.MODE_SWITCH_COOLDOWN_MS,
        gpsStaleForFallbackMs = AppConfig.Tracking.GPS_STALE_FOR_FALLBACK_MS,
        gpsWeakAccuracyMeters = AppConfig.Tracking.GPS_WEAK_ACCURACY_METERS,
        providerRefreshCooldownMs = AppConfig.Tracking.PROVIDER_REFRESH_COOLDOWN_MS
    )
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackedClients: List<Client> = emptyList()

    // Injected dependencies
    private val clientRepository: ClientRepository by inject()
    private val trackingEventBus: TrackingEventBus by inject()
    private val preferencesRepository: PreferencesRepository by inject()
    private val nonClientStopDao: NonClientStopDao by inject()
    private val weatherRepository: WeatherRepository by inject()

    private val trackingNotifier by lazy {
        LocationTrackingNotifier(
            context = this,
            channelId = CHANNEL_ID,
            arrivalNotifBase = ARRIVAL_NOTIF_BASE,
            completeNotifBase = COMPLETE_NOTIF_BASE,
            clusterNotifBase = CLUSTER_NOTIF_BASE
        )
    }
    private val arrivalDispatchCoordinator by lazy {
        ArrivalDispatchCoordinator(
            tag = TAG,
            arrivalDepartureEngine = ArrivalDepartureEngine(),
            trackingNotifier = trackingNotifier,
            arrivalNotifBase = ARRIVAL_NOTIF_BASE,
            completeNotifBase = COMPLETE_NOTIF_BASE,
            arrivalRadiusMeters = AppConfig.Tracking.ARRIVAL_RADIUS_METERS,
            dwellThresholdMs = AppConfig.Tracking.DWELL_THRESHOLD_MS,
            onSiteRadiusMeters = AppConfig.Tracking.ONSITE_RADIUS_METERS,
            clusterRadiusMeters = AppConfig.Tracking.CLUSTER_RADIUS_METERS,
            jobMinDurationMs = AppConfig.Tracking.JOB_MIN_DURATION_MS,
            cancelNotification = { notifId ->
                getSystemService(NotificationManager::class.java).cancel(notifId)
            },
            postToMainThread = { block -> mainHandler.post { block() } },
            emitEvent = { event ->
                onTrackingEventFromArrivalCoordinator(event)
            }
        )
    }
    private val nonClientStopLogger by lazy {
        NonClientStopLogger(
            tag = TAG,
            preferencesRepository = preferencesRepository,
            nonClientStopDao = nonClientStopDao,
            nonClientStopTracker = NonClientStopTracker(
                nonClientStopRadiusMeters = AppConfig.Tracking.NON_CLIENT_STOP_RADIUS_METERS,
                nonClientDepartRadiusMeters = AppConfig.Tracking.NON_CLIENT_DEPART_RADIUS_METERS
            ),
            launchAsync = { block ->
                serviceScope.launch { block() }
            }
        )
    }
    private val destinationArrivalCoordinator by lazy {
        DestinationArrivalCoordinator(
            tag = TAG,
            destinationDwellDetector = DestinationDwellDetector(
                destinationRadiusMeters = AppConfig.Tracking.DESTINATION_RADIUS_METERS,
                destinationDwellMs = AppConfig.Tracking.DESTINATION_DWELL_MS
            ),
            preferencesRepository = preferencesRepository,
            nonClientStopDao = nonClientStopDao,
            launchAsync = { block ->
                serviceScope.launch { block() }
            },
            postToMainThread = { block -> mainHandler.post { block() } },
            emitEvent = { event ->
                trackingEventBus.tryEmit(event)
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_TRACKED_CLIENTS && locationDispatcher != null) {
            serviceScope.launch {
                refreshTrackedClients()
            }
            Log.d(TAG, "Tracking clients refreshed")
            return START_STICKY
        }

        if (locationDispatcher != null) {
            serviceScope.launch {
                refreshTrackedClients()
            }
            trackingEventBus.setTrackingActive(true)
            Log.d(TAG, "Location tracking already running")
            return START_STICKY
        }

        val notification = trackingNotifier.buildTrackingNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            refreshTrackedClients()
        }
        startLocationDispatcher()
        trackingEventBus.setTrackingActive(true)
        Log.d(TAG, "Location tracking started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        locationDispatcher?.stopLocationUpdates()
        locationDispatcher = null
        serviceScope.cancel()
        trackingEventBus.setTrackingActive(false)
        trackingEventBus.setLatestLocation(null)
        trackedClients = emptyList()
        nonClientStopLogger.clearState()
        destinationArrivalCoordinator.reset()
        arrivalDispatchCoordinator.reset()
        modeController.reset()
        Log.d(TAG, "Location tracking stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun refreshTrackedClients() {
        trackedClients = runCatching { clientRepository.loadAllClients() }
            .onFailure { Log.w(TAG, "Failed to load tracked clients: ${it.message}") }
            .getOrDefault(emptyList())
            .filter { it.subscribedSteps.isNotEmpty() || it.hasGrub }
        Log.d(TAG, "Tracking service loaded ${trackedClients.size} clients")
    }

    private fun startLocationDispatcher() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationDispatcher = LocationDispatcher(
            context = this,
            locationManager = locationManager,
            modeController = modeController,
            config = LocationDispatcher.Config(
                logTag = TAG,
                drivingIntervalMs = AppConfig.Tracking.DRIVING_INTERVAL_MS,
                drivingMinDistanceMeters = AppConfig.Tracking.DRIVING_MIN_DISTANCE_METERS,
                arrivalIntervalMs = AppConfig.Tracking.ARRIVAL_INTERVAL_MS,
                arrivalMinDistanceMeters = AppConfig.Tracking.ARRIVAL_MIN_DISTANCE_METERS,
                modeSwitchRadiusMeters = AppConfig.Tracking.MODE_SWITCH_RADIUS_METERS
            ),
            onMissingFineLocationPermission = { stopSelf() },
            isNearAnyClient = ::isNearAnyClient,
            hasActiveWork = { arrivalDispatchCoordinator.hasActiveArrivals() },
            onLocationUpdated = { location ->
                trackingEventBus.setLatestLocation(location)
                trackingEventBus.tryEmit(TrackingEvent.LocationUpdated(location))
            },
            onLocationTick = { location ->
                arrivalDispatchCoordinator.onLocationTick(location, trackedClients)
                // Compute proximity once; both remaining coordinators share the result
                // to avoid redundant O(N) client-list scans on every GPS tick.
                val nearClientOrActive =
                    isNearAnyClient(location, AppConfig.Tracking.ARRIVAL_RADIUS_METERS) ||
                        arrivalDispatchCoordinator.hasActiveArrivals()
                nonClientStopLogger.onLocationTick(location, nearClientOrActive)
                destinationArrivalCoordinator.onLocationTick(location, nearClientOrActive)
            }
        )
        locationDispatcher?.startLocationUpdates()
    }

    private fun onTrackingEventFromArrivalCoordinator(event: TrackingEvent) {
        trackingEventBus.tryEmit(event)

        if (event is TrackingEvent.ClientArrival) {
            val lat = event.location.latitude
            val lng = event.location.longitude
            val clientId = event.client.id
            val arrivedAtMillis = event.arrivedAtMillis

            serviceScope.launch {
                val snapshot = runCatching {
                    weatherRepository.fetchCurrentSnapshot(lat, lng)
                }.getOrNull()

                arrivalDispatchCoordinator.recordArrivalWeather(
                    clientId = clientId,
                    arrivedAtMillis = arrivedAtMillis,
                    tempF = snapshot?.tempF,
                    windMph = snapshot?.windMph,
                    desc = snapshot?.description
                )
            }
        }
    }

    /**
     * Returns true if the location is within [radiusMeters] of any client.
     */
    private fun isNearAnyClient(location: Location, radiusMeters: Float): Boolean {
        return ClientProximityHelper.isNearAnyClient(location, trackedClients, radiusMeters)
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
