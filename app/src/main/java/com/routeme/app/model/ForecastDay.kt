package com.routeme.app.model

/**
 * Aggregated NWS forecast for one calendar day.
 */
data class ForecastDay(
    val dateMillis: Long,
    val highTempF: Int,
    val lowTempF: Int,
    val windSpeedMph: Int,
    val windGustMph: Int?,
    val precipProbabilityPct: Int,
    val shortForecast: String,
    val detailedForecast: String
) {
    fun toWeatherEmoji(): String = when {
        shortForecast.contains("thunder", ignoreCase = true) -> "⛈️"
        shortForecast.contains("rain", ignoreCase = true) ||
            shortForecast.contains("shower", ignoreCase = true) -> "🌧️"
        shortForecast.contains("snow", ignoreCase = true) -> "🌨️"
        shortForecast.contains("cloud", ignoreCase = true) -> "☁️"
        shortForecast.contains("partly", ignoreCase = true) -> "⛅"
        shortForecast.contains("sun", ignoreCase = true) ||
            shortForecast.contains("clear", ignoreCase = true) -> "☀️"
        else -> "🌤️"
    }

    fun toSummaryLine(): String {
        val windSummary = if (windGustMph != null && windGustMph > windSpeedMph) {
            "$windSpeedMph mph (gusts $windGustMph)"
        } else {
            "$windSpeedMph mph"
        }
        val precipSummary = if (precipProbabilityPct > 0) "$precipProbabilityPct% chance" else "No precip"
        return "${toWeatherEmoji()} $highTempF°/$lowTempF° • Wind $windSummary • $precipSummary"
    }
}
