package com.routeme.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached daily weather observation from NWS.
 * One row per day (keyed by local-day start millis).
 */
@Entity(tableName = "daily_weather")
data class DailyWeatherEntity(
    @PrimaryKey val dateMillis: Long,
    val highTempF: Int?,
    val lowTempF: Int?,
    val windSpeedMph: Int?,
    val windGustMph: Int?,
    val precipitationInches: Double?,
    val description: String,
    val fetchedAtMillis: Long
)
