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
        val description: String
    )

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

            val desc = props.optString("textDescription", "").ifBlank { "Unknown" }

            WeatherSnapshot(tempF = tempF, windMph = windMph, description = desc)
        } catch (e: Exception) {
            Log.w(TAG, "Latest obs fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Resolve the nearest observation station for a lat/lon via the NWS points API.
     */
    private fun resolveNearestStation(lat: Double, lng: Double): String? {
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
        return stationProps?.optString("stationIdentifier")
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
            precipitationInches = if (totalPrecipMm > 0) totalPrecipMm / 25.4 else 0.0,
            description = lastDescription.ifBlank { "Unknown" }
        )
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
