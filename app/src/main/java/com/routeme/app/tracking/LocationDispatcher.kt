package com.routeme.app.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.routeme.app.TrackingMode
import com.routeme.app.TrackingModeController

class LocationDispatcher(
    private val context: Context,
    private val locationManager: LocationManager,
    private val modeController: TrackingModeController,
    private val config: Config,
    private val onMissingFineLocationPermission: () -> Unit,
    private val isNearAnyClient: (Location, Float) -> Boolean,
    private val hasActiveWork: () -> Boolean,
    private val onLocationUpdated: (Location) -> Unit,
    private val onLocationTick: (Location) -> Unit
) {
    data class Config(
        val logTag: String,
        val drivingIntervalMs: Long,
        val drivingMinDistanceMeters: Float,
        val arrivalIntervalMs: Long,
        val arrivalMinDistanceMeters: Float,
        val modeSwitchRadiusMeters: Float
    )

    private val locationListener = LocationListener { location ->
        if (location.provider == LocationManager.GPS_PROVIDER) {
            modeController.recordGpsFix(
                nowMillis = System.currentTimeMillis(),
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
            )
        }

        onLocationUpdated(location)
        Log.d(
            config.logTag,
            "Location update [${modeController.currentMode}]: ${location.latitude}, ${location.longitude}"
        )
        checkAndSwitchMode(location)
        onLocationTick(location)
    }

    fun startLocationUpdates() {
        if (!hasFineLocationPermission()) {
            Log.w(config.logTag, "No fine location permission, stopping service")
            onMissingFineLocationPermission()
            return
        }

        applyTrackingMode(TrackingMode.DRIVING)
    }

    fun stopLocationUpdates() {
        locationManager.removeUpdates(locationListener)
    }

    private fun applyTrackingMode(mode: TrackingMode) {
        if (!hasFineLocationPermission()) return

        locationManager.removeUpdates(locationListener)

        val (interval, minDist) = when (mode) {
            TrackingMode.DRIVING -> config.drivingIntervalMs to config.drivingMinDistanceMeters
            TrackingMode.ARRIVAL -> config.arrivalIntervalMs to config.arrivalMinDistanceMeters
        }

        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                interval,
                minDist,
                locationListener
            )
        }

        val now = System.currentTimeMillis()
        val shouldEnableNetworkFallback =
            mode == TrackingMode.ARRIVAL && modeController.shouldUseNetworkFallback(
                gpsEnabled = isGpsProviderEnabled(),
                nowMillis = now
            )
        if (shouldEnableNetworkFallback) {
            runCatching {
                val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                if (networkEnabled) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        interval,
                        minDist,
                        locationListener
                    )
                }
            }
        }

        modeController.onTrackingModeApplied(mode, shouldEnableNetworkFallback, now)
        Log.d(
            config.logTag,
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
                config.logTag,
                "Refreshing ARRIVAL providers (networkFallback: ${modeController.networkFallbackActive} → $shouldEnableNetworkFallback)"
            )
            applyTrackingMode(TrackingMode.ARRIVAL)
        } else {
            modeController.markProviderRefreshCheck(now)
        }
    }

    private fun checkAndSwitchMode(location: Location) {
        val now = System.currentTimeMillis()
        if (!modeController.canEvaluateModeSwitch(now)) return

        val nearAnyClient = isNearAnyClient(location, config.modeSwitchRadiusMeters)
        val hasActiveClientWork = hasActiveWork()

        when {
            modeController.currentMode == TrackingMode.DRIVING && (nearAnyClient || hasActiveClientWork) -> {
                applyTrackingMode(TrackingMode.ARRIVAL)
            }

            modeController.currentMode == TrackingMode.ARRIVAL && !nearAnyClient && !hasActiveClientWork -> {
                applyTrackingMode(TrackingMode.DRIVING)
            }
        }

        maybeRefreshArrivalProviders()
    }

    private fun isGpsProviderEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun hasFineLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
