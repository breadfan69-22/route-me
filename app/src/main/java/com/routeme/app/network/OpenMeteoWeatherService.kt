package com.routeme.app.network

import android.util.Log
import com.routeme.app.model.RecentWeatherSignal
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Fetches recent point weather signals from Open-Meteo.
 * Free, no key required.
 */
object OpenMeteoWeatherService {
    private const val TAG = "OpenMeteoWeather"
    private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
    private const val CONNECT_TIMEOUT = 3_000
    private const val READ_TIMEOUT = 3_000
    private const val MM_PER_INCH = 25.4

    fun fetchRecentWeatherSignal(lat: Double, lng: Double): RecentWeatherSignal? {
        return try {
            val url = buildUrl(lat, lng)
            val json = httpGet(url) ?: return null
            val hourly = json.optJSONObject("hourly") ?: return null

            val precipArray = hourly.optJSONArray("precipitation") ?: return null
            val soilArray = hourly.optJSONArray("soil_moisture_0_to_1cm")

            val rain24Mm = sumTail(precipArray, 24)
            val rain48Mm = sumTail(precipArray, 48)
            val soilSurface = lastNonNull(soilArray)

            if (rain24Mm == null && rain48Mm == null && soilSurface == null) return null

            RecentWeatherSignal(
                rainLast24hInches = rain24Mm?.div(MM_PER_INCH),
                rainLast48hInches = rain48Mm?.div(MM_PER_INCH),
                soilMoistureSurface = soilSurface,
                fetchedAtMillis = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo fetch failed: ${e.message}")
            null
        }
    }

    private fun buildUrl(lat: Double, lng: Double): String {
        val latStr = "%.5f".format(lat)
        val lngStr = "%.5f".format(lng)
        return "$BASE_URL?latitude=$latStr&longitude=$lngStr&hourly=precipitation,soil_moisture_0_to_1cm&past_days=2&forecast_days=1&timezone=UTC"
    }

    private fun sumTail(array: org.json.JSONArray, count: Int): Double? {
        if (array.length() <= 0 || count <= 0) return null

        val start = max(0, array.length() - count)
        var sum = 0.0
        var hasAny = false

        for (index in start until array.length()) {
            if (array.isNull(index)) continue
            val value = array.optDouble(index, Double.NaN)
            if (!value.isNaN()) {
                hasAny = true
                sum += value
            }
        }

        return if (hasAny) sum else null
    }

    private fun lastNonNull(array: org.json.JSONArray?): Double? {
        if (array == null || array.length() <= 0) return null

        for (index in array.length() - 1 downTo 0) {
            if (array.isNull(index)) continue
            val value = array.optDouble(index, Double.NaN)
            if (!value.isNaN()) return value
        }
        return null
    }

    private fun httpGet(url: String): JSONObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
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
