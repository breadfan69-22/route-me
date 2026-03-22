package com.routeme.app.model

/**
 * Runtime weather signals used for routing/planning adjustments.
 * Values come from point-based model data (Open-Meteo), not on-property sensors.
 */
data class RecentWeatherSignal(
    val rainLast24hInches: Double?,
    val rainLast48hInches: Double?,
    val soilMoistureSurface: Double?,
    val fetchedAtMillis: Long
)
