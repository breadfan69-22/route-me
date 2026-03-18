package com.routeme.app.model

/**
 * Weather summary for a single day at a given location.
 * Sourced from NWS observation data.
 */
data class DailyWeather(
    val dateMillis: Long,
    val highTempF: Int?,
    val lowTempF: Int?,
    val windSpeedMph: Int?,
    val windGustMph: Int?,
    val precipitationInches: Double?,
    val description: String
) {
    /** One-line summary suitable for inclusion in route history text. */
    fun toSummaryLine(): String {
        val parts = mutableListOf<String>()

        if (highTempF != null && lowTempF != null) {
            parts += "${highTempF}°F Hi / ${lowTempF}°F Lo"
        } else if (highTempF != null) {
            parts += "${highTempF}°F"
        }

        if (windSpeedMph != null) {
            val windStr = if (windGustMph != null && windGustMph > windSpeedMph) {
                "Wind $windSpeedMph mph (gusts $windGustMph)"
            } else {
                "Wind $windSpeedMph mph"
            }
            parts += windStr
        }

        val precipStr = when {
            precipitationInches == null || precipitationInches <= 0.0 -> "No precip"
            else -> "Precip %.2f\"".format(precipitationInches)
        }
        parts += precipStr

        val icon = when {
            precipitationInches != null && precipitationInches > 0.0 -> "🌧️"
            description.contains("cloud", ignoreCase = true) ||
                description.contains("overcast", ignoreCase = true) -> "☁️"
            else -> "☀️"
        }

        return "$icon  ${parts.joinToString("  •  ")}"
    }
}
