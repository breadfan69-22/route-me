package com.routeme.app.tracking

import android.location.Location
import android.util.Log
import com.routeme.app.NonClientStopDao
import com.routeme.app.NonClientStopEntity
import com.routeme.app.TrackingEvent
import com.routeme.app.data.PreferencesRepository

class DestinationArrivalCoordinator(
    private val tag: String,
    private val destinationDwellDetector: DestinationDwellDetector,
    private val preferencesRepository: PreferencesRepository,
    private val nonClientStopDao: NonClientStopDao,
    private val arrivalRadiusMeters: Float,
    private val hasActiveArrivals: () -> Boolean,
    private val isNearAnyClient: (Location, Float) -> Boolean,
    private val launchAsync: (suspend () -> Unit) -> Unit,
    private val postToMainThread: ((() -> Unit) -> Unit),
    private val emitEvent: (TrackingEvent) -> Unit,
    private val logDebug: (String) -> Unit = { message -> Log.d(tag, message) },
    private val logWarn: (String) -> Unit = { message -> Log.w(tag, message) }
) {

    fun onLocationTick(location: Location) {
        val outcome = destinationDwellDetector.onLocationTick(
            location = location,
            activeDestination = preferencesRepository.activeDestination,
            isNearClientOrActiveArrival = isNearAnyClient(location, arrivalRadiusMeters) ||
                hasActiveArrivals()
        )

        val reached = outcome.destinationReached ?: return
        logDebug("Destination reached: ${reached.destination.name} (${reached.elapsedMillis / 1000}s dwell)")

        val entity = NonClientStopEntity(
            lat = reached.anchorLat,
            lng = reached.anchorLng,
            address = reached.destination.address,
            arrivedAtMillis = reached.arrivedAtMillis,
            label = reached.destination.name
        )
        launchAsync {
            try {
                nonClientStopDao.insertStop(entity)
            } catch (e: Exception) {
                logWarn("Failed to log destination stop: ${e.message}")
            }
        }

        postToMainThread {
            emitEvent(
                TrackingEvent.DestinationReached(
                    reached.destination.name,
                    reached.arrivedAtMillis,
                    location
                )
            )
        }
    }

    fun reset() {
        destinationDwellDetector.reset()
    }
}
