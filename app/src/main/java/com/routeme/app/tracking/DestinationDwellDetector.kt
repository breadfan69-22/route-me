package com.routeme.app.tracking

import android.location.Location
import com.routeme.app.SavedDestination

class DestinationDwellDetector(
    private val destinationRadiusMeters: Float,
    private val destinationDwellMs: Long,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val distanceCalculator: (Double, Double, Double, Double) -> Float =
        { fromLat, fromLng, toLat, toLng ->
            val results = FloatArray(1)
            Location.distanceBetween(fromLat, fromLng, toLat, toLng, results)
            results[0]
        }
) {
    data class DestinationReachedOutcome(
        val destination: SavedDestination,
        val arrivedAtMillis: Long,
        val anchorLat: Double,
        val anchorLng: Double,
        val elapsedMillis: Long
    )

    data class Outcome(
        val destinationReached: DestinationReachedOutcome? = null
    )

    private var destDwellStartMs: Long = 0L
    private var destDwellLat: Double = 0.0
    private var destDwellLng: Double = 0.0
    private var destDwellFired: Boolean = false

    fun onLocationTick(
        location: Location,
        activeDestination: SavedDestination?,
        isNearClientOrActiveArrival: Boolean
    ): Outcome {
        val destination = activeDestination ?: run {
            resetDestDwell()
            return Outcome()
        }

        if (isNearClientOrActiveArrival) {
            resetDestDwell()
            return Outcome()
        }

        val distanceMeters = distanceCalculator(
            location.latitude,
            location.longitude,
            destination.lat,
            destination.lng
        )
        if (distanceMeters > destinationRadiusMeters) {
            resetDestDwell()
            return Outcome()
        }

        val now = nowProvider()
        if (destDwellStartMs == 0L) {
            destDwellStartMs = now
            destDwellLat = location.latitude
            destDwellLng = location.longitude
            return Outcome()
        }

        if (destDwellFired) return Outcome()

        val elapsed = now - destDwellStartMs
        if (elapsed >= destinationDwellMs) {
            destDwellFired = true
            return Outcome(
                destinationReached = DestinationReachedOutcome(
                    destination = destination,
                    arrivedAtMillis = destDwellStartMs,
                    anchorLat = destDwellLat,
                    anchorLng = destDwellLng,
                    elapsedMillis = elapsed
                )
            )
        }

        return Outcome()
    }

    fun reset() {
        resetDestDwell()
    }

    private fun resetDestDwell() {
        destDwellStartMs = 0L
        destDwellFired = false
    }
}