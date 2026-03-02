package com.routeme.app

import android.location.Location
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds per-client info when completing a cluster of adjacent properties at once.
 */
data class ClusterMember(
    val client: Client,
    val timeOnSiteMillis: Long,
    val arrivedAtMillis: Long,
    val location: Location
)

sealed interface TrackingEvent {
    data class ClientArrival(val client: Client, val arrivedAtMillis: Long, val location: Location) : TrackingEvent
    data class JobComplete(val client: Client, val timeOnSiteMillis: Long, val arrivedAtMillis: Long, val location: Location) : TrackingEvent
    /** Fired when the user departs a cluster of 2+ adjacent clients at once. */
    data class ClusterComplete(val members: List<ClusterMember>) : TrackingEvent
    data class LocationUpdated(val location: Location) : TrackingEvent
}

class TrackingEventBus {
    private val _events = MutableSharedFlow<TrackingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TrackingEvent> = _events.asSharedFlow()
    private val _latestLocation = MutableStateFlow<Location?>(null)
    val latestLocation: StateFlow<Location?> = _latestLocation.asStateFlow()
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    fun tryEmit(event: TrackingEvent) {
        _events.tryEmit(event)
    }

    fun setLatestLocation(location: Location?) {
        _latestLocation.value = location
    }

    fun setTrackingActive(active: Boolean) {
        _isTracking.value = active
    }
}
