package com.routeme.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "forecast_days")
data class ForecastDayEntity(
    @PrimaryKey val dateMillis: Long,
    val highTempF: Int,
    val lowTempF: Int,
    val windSpeedMph: Int,
    val windGustMph: Int?,
    val precipProbabilityPct: Int,
    val shortForecast: String,
    val detailedForecast: String,
    val fetchedAtMillis: Long
)
