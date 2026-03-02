package com.routeme.app

import android.location.Location

data class NonClientStopTickOutcome(
    val pendingCancelledNearClient: Boolean = false,
    val pendingResetDistanceMeters: Float? = null,
    val closeActive: CloseActiveStopRequest? = null,
    val createStop: CreateNonClientStopRequest? = null
)

data class CloseActiveStopRequest(
    val stopId: Long,
    val arrivedAtMillis: Long,
    val departedAtMillis: Long
)

data class CreateNonClientStopRequest(
    val lat: Double,
    val lng: Double,
    val arrivedAtMillis: Long,
    val elapsedMillis: Long
)

class NonClientStopTracker(
    private val nonClientStopRadiusMeters: Float,
    private val nonClientDepartRadiusMeters: Float,
    private val distanceCalculator: (Double, Double, Double, Double) -> Float = { fromLat, fromLng, toLat, toLng ->
        val results = FloatArray(1)
        Location.distanceBetween(fromLat, fromLng, toLat, toLng, results)
        results[0]
    }
) {
    private var pendingLat: Double? = null
    private var pendingLng: Double? = null
    private var pendingSince: Long = 0L

    private var activeStopId: Long? = null
    private var activeLat: Double? = null
    private var activeLng: Double? = null
    private var activeArrivedAt: Long = 0L

    fun onLocationTick(
        location: Location,
        nowMillis: Long,
        isNearClientOrActiveArrival: Boolean,
        thresholdMs: Long
    ): NonClientStopTickOutcome {
        if (isNearClientOrActiveArrival) {
            val hadPending = pendingLat != null
            clearPending()
            val close = closeActiveIfPresent(nowMillis)
            return NonClientStopTickOutcome(
                pendingCancelledNearClient = hadPending,
                closeActive = close
            )
        }

        val aLat = activeLat
        val aLng = activeLng
        if (aLat != null && aLng != null) {
            val dist = distanceMeters(location.latitude, location.longitude, aLat, aLng)
            if (dist > nonClientDepartRadiusMeters) {
                val close = closeActiveIfPresent(nowMillis)
                clearPending()
                return NonClientStopTickOutcome(closeActive = close)
            }
            return NonClientStopTickOutcome()
        }

        val pLat = pendingLat
        val pLng = pendingLng
        if (pLat != null && pLng != null) {
            val dist = distanceMeters(location.latitude, location.longitude, pLat, pLng)
            if (dist > nonClientStopRadiusMeters) {
                pendingLat = location.latitude
                pendingLng = location.longitude
                pendingSince = nowMillis
                return NonClientStopTickOutcome(pendingResetDistanceMeters = dist)
            }

            val elapsed = nowMillis - pendingSince
            if (elapsed >= thresholdMs) {
                val request = CreateNonClientStopRequest(
                    lat = pLat,
                    lng = pLng,
                    arrivedAtMillis = pendingSince,
                    elapsedMillis = elapsed
                )
                clearPending()
                return NonClientStopTickOutcome(createStop = request)
            }
            return NonClientStopTickOutcome()
        }

        pendingLat = location.latitude
        pendingLng = location.longitude
        pendingSince = nowMillis
        return NonClientStopTickOutcome()
    }

    fun onStopInserted(stopId: Long, lat: Double, lng: Double, arrivedAtMillis: Long) {
        activeStopId = stopId
        activeLat = lat
        activeLng = lng
        activeArrivedAt = arrivedAtMillis
    }

    fun clearAll(nowMillis: Long): CloseActiveStopRequest? {
        clearPending()
        return closeActiveIfPresent(nowMillis)
    }

    private fun closeActiveIfPresent(departedAtMillis: Long): CloseActiveStopRequest? {
        val stopId = activeStopId ?: return null
        val request = CloseActiveStopRequest(
            stopId = stopId,
            arrivedAtMillis = activeArrivedAt,
            departedAtMillis = departedAtMillis
        )
        activeStopId = null
        activeLat = null
        activeLng = null
        activeArrivedAt = 0L
        return request
    }

    private fun clearPending() {
        pendingLat = null
        pendingLng = null
        pendingSince = 0L
    }

    private fun distanceMeters(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): Float {
        return distanceCalculator(fromLat, fromLng, toLat, toLng)
    }
}
