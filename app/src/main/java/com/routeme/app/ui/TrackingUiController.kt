package com.routeme.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.routeme.app.LocationTrackingService
import com.routeme.app.R
import com.routeme.app.TrackingEventBus

class TrackingUiController(
    private val activity: AppCompatActivity,
    private val viewModel: MainViewModel,
    private val trackingEventBus: TrackingEventBus,
    private val onTrackingSessionReset: () -> Unit
) {
    companion object {
        const val REQUEST_LOCATION_PERMISSION = 102
        const val REQUEST_POST_NOTIFICATIONS = 103
    }

    fun toggleTracking() {
        if (viewModel.uiState.value.isTracking) {
            stopTracking()
        } else {
            startTracking()
        }
    }

    fun startTracking() {
        if (trackingEventBus.isTracking.value) {
            viewModel.setTrackingActive(true)
            return
        }

        if (!hasFineLocationPermission()) {
            requestLocationPermissions()
            return
        }

        maybeRequestNotificationPermission()

        startTrackingService()

        trackingEventBus.setTrackingActive(true)
        viewModel.setTrackingActive(true)
        onTrackingSessionReset()
        viewModel.postStatus(activity.getString(R.string.status_tracking_started))
    }

    fun stopTracking() {
        activity.stopService(Intent(activity, LocationTrackingService::class.java))
        trackingEventBus.setTrackingActive(false)
        viewModel.setTrackingActive(false)
        onTrackingSessionReset()
        viewModel.postStatus(activity.getString(R.string.status_tracking_stopped))
    }

    fun refreshTrackedClients() {
        if (!trackingEventBus.isTracking.value && !viewModel.uiState.value.isTracking) {
            return
        }

        startTrackingService(LocationTrackingService.ACTION_REFRESH_TRACKED_CLIENTS)
    }

    fun syncTrackingState() {
        viewModel.setTrackingActive(trackingEventBus.isTracking.value)
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == REQUEST_LOCATION_PERMISSION && grantResults.isNotEmpty()) {
            if (hasFineLocationPermission()) {
                startTracking()
            }
        }
    }

    fun dismissNotification(notificationId: Int) {
        val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }

    private fun hasFineLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQUEST_LOCATION_PERMISSION
        )
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val hasNotificationPermission = ActivityCompat.checkSelfPermission(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasNotificationPermission) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        }
    }

    private fun startTrackingService(action: String? = null) {
        val intent = Intent(activity, LocationTrackingService::class.java).apply {
            this.action = action
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent)
        } else {
            activity.startService(intent)
        }
    }
}
