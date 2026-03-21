package com.routeme.app.network

import android.util.Log
import com.routeme.app.model.DailyWeather
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches weather observations from the National Weather Service (NWS) API.
 *
 * Flow:
 * 1. `/points/{lat},{lon}` → nearest observation station URL
 * 2. `/stations/{stationId}/observations?start=...&end=...` → observations for a day
 * 3. Aggregate into a [DailyWeather] summary.
 *
 * Free, no API key required. Requires a `User-Agent` header.
 */
object NwsWeatherService {

    private const val TAG = "NwsWeather"
    private const val BASE_URL = "https://api.weather.gov"
    private const val USER_AGENT = "RouteMe/1.0 (lawn-route-app)"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 10_000

    /**
     * Nearest observation station ID keyed by "%.1f,%.1f" (lat/lng rounded to 0.1°, ~11 km).
     * Station IDs are stable landmarks; there's no need to re-resolve on every request.
     */
    private val stationCache = ConcurrentHashMap<String, String>()

    /** Cached hourly forecast URL keyed by "%.2f,%.2f" (lat/lng rounded to 0.01°, ~1 km). */
    private val forecastUrlCache = ConcurrentHashMap<String, String>()

    /**
     * Fetch a daily weather summary for the given location and date.
     *
     * Must be called on a background thread.
     * Returns null if unable to fetch data.
     */
    fun fetchDailyWeather(lat: Double, lng: Double, dateMillis: Long): DailyWeather? {
        return try {
            val stationId = resolveNearestStation(lat, lng) ?: return null
            fetchObservationsForDay(stationId, dateMillis)
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Compact weather snapshot for a single point in time — used when saving a stop event.
     */
    data class WeatherSnapshot(
        val tempF: Int,
        val windMph: Int,
        val windDirection: String?,
        val windGustMph: Int?,
        val description: String
    )

    /**
     * Hourly forecast period from NWS.
     */
    data class HourlyForecast(
        val tempF: Int,
        val windSpeedMph: Int?,
        val windDirection: String?,
        val description: String,
        /** e.g. "2pm", "3pm" */
        val timeLabel: String
    )

    /**
     * Fetch the next hour's forecast near [lat],[lng].
     * Returns null on failure.
     * Must be called on a background thread.
     */
    fun fetchNextHourForecast(lat: Double, lng: Double): HourlyForecast? {
        return try {
            val forecastUrl = resolveForecastUrl(lat, lng) ?: return null
            val json = httpGet(forecastUrl) ?: return null
            val periods = json.optJSONObject("properties")?.optJSONArray("periods")
            if (periods == null || periods.length() == 0) return null

            // First period is the next hour
            val period = periods.getJSONObject(0)
            val tempF = period.optInt("temperature", 0)
            val windSpeed = period.optString("windSpeed", "") // e.g. "10 mph"
            val windSpeedMph = windSpeed.replace(Regex("[^0-9]"), "").toIntOrNull()
            val windDir = period.optString("windDirection", "").ifBlank { null }
            val desc = period.optString("shortForecast", "").ifBlank { "Unknown" }
            val startTime = period.optString("startTime", "")
            val timeLabel = parseHourLabel(startTime)

            HourlyForecast(
                tempF = tempF,
                windSpeedMph = windSpeedMph,
                windDirection = windDir,
                description = desc,
                timeLabel = timeLabel
            )
        } catch (e: Exception) {
            Log.w(TAG, "Hourly forecast fetch failed: ${e.message}")
            null
        }
    }

    /** Parse ISO timestamp to hour label like "2pm" */
    private fun parseHourLabel(isoTime: String): String {
        return try {
            val instant = java.time.OffsetDateTime.parse(isoTime)
            val hour = instant.hour
            val suffix = if (hour < 12) "am" else "pm"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            "$displayHour$suffix"
        } catch (e: Exception) {
            ""
        }
    }

    /** Resolve the hourly forecast URL for a lat/lon via the NWS points API. */
    private fun resolveForecastUrl(lat: Double, lng: Double): String? {
        val cacheKey = "%.2f,%.2f".format(lat, lng)
        forecastUrlCache[cacheKey]?.let { return it }

        val latStr = "%.4f".format(lat)
        val lngStr = "%.4f".format(lng)
        val url = "$BASE_URL/points/$latStr,$lngStr"

        val json = httpGet(url) ?: return null
        val properties = json.optJSONObject("properties") ?: return null
        val forecastUrl = properties.optString("forecastHourly", "")
        if (forecastUrl.isBlank()) return null

        forecastUrlCache[cacheKey] = forecastUrl
        return forecastUrl
    }

    /**
     * Fetch the latest observation near [lat],[lng].
     * Returns a compact snapshot (temp, wind, description) or null on failure.
     * Must be called on a background thread.
     */
    fun fetchLatestObservation(lat: Double, lng: Double): WeatherSnapshot? {
        return try {
            val stationId = resolveNearestStation(lat, lng) ?: return null
            val url = "$BASE_URL/stations/$stationId/observations/latest"
            val json = httpGet(url) ?: return null
            val props = json.optJSONObject("properties") ?: return null

            val tempC = props.optJSONObject("temperature")?.optDouble("value", Double.NaN)
            if (tempC == null || tempC.isNaN()) return null
            val tempF = (tempC * 9.0 / 5.0 + 32.0).toInt()

            val windKmh = props.optJSONObject("windSpeed")?.optDouble("value", Double.NaN)
            val windMph = if (windKmh != null && !windKmh.isNaN()) (windKmh * 0.621371).toInt() else 0

            val windDirDeg = props.optJSONObject("windDirection")?.optDouble("value", Double.NaN)
            val windDir = if (windDirDeg != null && !windDirDeg.isNaN()) degreesToCompass(windDirDeg) else null

            val gustKmh = props.optJSONObject("windGust")?.optDouble("value", Double.NaN)
            val gustMph = if (gustKmh != null && !gustKmh.isNaN()) (gustKmh * 0.621371).toInt() else null

            val desc = props.optString("textDescription", "").ifBlank { "Unknown" }

            WeatherSnapshot(tempF = tempF, windMph = windMph, windDirection = windDir, windGustMph = gustMph, description = desc)
        } catch (e: Exception) {
            Log.w(TAG, "Latest obs fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Resolve the nearest observation station for a lat/lon via the NWS points API.
     * Result is cached by rounded grid cell (~11 km) to avoid redundant network calls.
     */
    private fun resolveNearestStation(lat: Double, lng: Double): String? {
        val cacheKey = "%.1f,%.1f".format(lat, lng)
        stationCache[cacheKey]?.let { return it }

        // NWS wants max 4 decimal places
        val latStr = "%.4f".format(lat)
        val lngStr = "%.4f".format(lng)
        val url = "$BASE_URL/points/$latStr,$lngStr"

        val json = httpGet(url) ?: return null
        val properties = json.optJSONObject("properties") ?: return null
        val stationsUrl = properties.optString("observationStations", "")
        if (stationsUrl.isBlank()) return null

        // Fetch the stations list — first one is nearest
        val stationsJson = httpGet(stationsUrl) ?: return null
        val features = stationsJson.optJSONArray("features")
        if (features == null || features.length() == 0) return null

        val stationProps = features.getJSONObject(0).optJSONObject("properties")
        val stationId = stationProps?.optString("stationIdentifier") ?: return null

        stationCache[cacheKey] = stationId
        return stationId
    }

    /**
     * Fetch observations for a specific station and day, then aggregate into [DailyWeather].
     */
    private fun fetchObservationsForDay(stationId: String, dateMillis: Long): DailyWeather? {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
        val startIso = date.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val endIso = date.plusDays(1).atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        val url = "$BASE_URL/stations/$stationId/observations?start=$startIso&end=$endIso"
        val json = httpGet(url) ?: return null
        val features = json.optJSONArray("features")
        if (features == null || features.length() == 0) return null

        val temps = mutableListOf<Double>()
        val winds = mutableListOf<Double>()
        val gusts = mutableListOf<Double>()
        val windDirs = mutableListOf<Double>()
        var totalPrecipMm = 0.0
        var lastDescription = ""

        for (i in 0 until features.length()) {
            val props = features.getJSONObject(i).optJSONObject("properties") ?: continue

            // Temperature (Celsius → Fahrenheit)
            val tempObj = props.optJSONObject("temperature")
            val tempC = tempObj?.optDouble("value", Double.NaN)
            if (tempC != null && !tempC.isNaN()) {
                temps += tempC * 9.0 / 5.0 + 32.0
            }

            // Wind speed (km/h → mph)
            val windObj = props.optJSONObject("windSpeed")
            val windKmh = windObj?.optDouble("value", Double.NaN)
            if (windKmh != null && !windKmh.isNaN()) {
                winds += windKmh * 0.621371
            }

            // Wind gust
            val gustObj = props.optJSONObject("windGust")
            val gustKmh = gustObj?.optDouble("value", Double.NaN)
            if (gustKmh != null && !gustKmh.isNaN()) {
                gusts += gustKmh * 0.621371
            }

            // Wind direction (degrees)
            val windDirObj = props.optJSONObject("windDirection")
            val windDirDeg = windDirObj?.optDouble("value", Double.NaN)
            if (windDirDeg != null && !windDirDeg.isNaN()) {
                windDirs += windDirDeg
            }

            // Precipitation (mm → inches)  — use precipitationLastHour
            val precipObj = props.optJSONObject("precipitationLastHour")
            val precipMm = precipObj?.optDouble("value", Double.NaN)
            if (precipMm != null && !precipMm.isNaN() && precipMm > 0) {
                totalPrecipMm += precipMm
            }

            // Description — take the latest non-blank one
            val desc = props.optString("textDescription", "")
            if (desc.isNotBlank()) {
                lastDescription = desc
            }
        }

        if (temps.isEmpty()) return null

        return DailyWeather(
            dateMillis = dateMillis,
            highTempF = temps.maxOrNull()?.toInt(),
            lowTempF = temps.minOrNull()?.toInt(),
            windSpeedMph = winds.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            windGustMph = gusts.maxOrNull()?.toInt(),
            windDirection = windDirs.takeIf { it.isNotEmpty() }?.average()?.let { degreesToCompass(it) },
            precipitationInches = if (totalPrecipMm > 0) totalPrecipMm / 25.4 else 0.0,
            description = lastDescription.ifBlank { "Unknown" }
        )
    }

    /**
     * Convert wind direction in degrees (0-360) to compass direction.
     * 0° = N, 90° = E, 180° = S, 270° = W
     */
    private fun degreesToCompass(degrees: Double): String {
        val normalized = ((degrees % 360) + 360) % 360
        return when {
            normalized < 22.5 || normalized >= 337.5 -> "N"
            normalized < 67.5 -> "NE"
            normalized < 112.5 -> "E"
            normalized < 157.5 -> "SE"
            normalized < 202.5 -> "S"
            normalized < 247.5 -> "SW"
            normalized < 292.5 -> "W"
            else -> "NW"
        }
    }

    private fun httpGet(url: String): JSONObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/geo+json")
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT

        return try {
            if (connection.responseCode != 200) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                return null
            }
            val body = connection.inputStream.bufferedReader().readText()
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
