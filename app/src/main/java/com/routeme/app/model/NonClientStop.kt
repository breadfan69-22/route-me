package com.routeme.app

/**
 * Represents a stop at a location that is NOT a client property —
 * e.g. gas station, lunch break, supply store.
 * Logged silently by the tracking service when the user is stationary
 * for longer than the configured threshold.
 */
data class NonClientStop(
    val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val arrivedAtMillis: Long,
    val departedAtMillis: Long? = null,
    val durationMinutes: Long = 0,
    val label: String? = null
)
