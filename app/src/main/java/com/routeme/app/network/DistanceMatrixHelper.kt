package com.routeme.app.network

import android.util.Log
import com.routeme.app.Client
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Queries the Google Distance Matrix API for driving distances and times.
 * Requires a valid API key with the Distance Matrix API enabled.
 *
 * Set [apiKey] before calling [fetchDrivingTimes].
 */
object DistanceMatrixHelper {

    private const val TAG = "DistanceMatrix"
    private const val BASE_URL = "https://maps.googleapis.com/maps/api/distancematrix/json"

    /** Set this to your Google Cloud API key. */
    var apiKey: String = ""

    data class DrivingInfo(
        val clientId: String,
        val distanceMeters: Int?,
        val distanceText: String?,
        val durationSeconds: Int?,
        val durationText: String?
    )

    /**
     * Fetches driving distance and time from [originLat],[originLng] to each client.
     * Clients without lat/lng are skipped and returned with null values.
     *
     * Must be called on a background thread.
     * Returns results in the same order as the input [clients] list.
     */
    fun fetchDrivingTimes(
        originLat: Double,
        originLng: Double,
        clients: List<Client>
    ): List<DrivingInfo> {
        if (apiKey.isBlank()) {
            Log.w(TAG, "No API key set — returning empty results")
            return clients.map { DrivingInfo(it.id, null, null, null, null) }
        }

        // Separate clients with coordinates
        val withCoords = clients.filter { it.latitude != null && it.longitude != null }

        if (withCoords.isEmpty()) {
            return clients.map { DrivingInfo(it.id, null, null, null, null) }
        }

        // Build destinations string: lat,lng|lat,lng|...
        val destinations = withCoords.joinToString("|") { "${it.latitude},${it.longitude}" }
        val origin = "$originLat,$originLng"

        val url = "$BASE_URL?origins=${URLEncoder.encode(origin, "UTF-8")}" +
            "&destinations=${URLEncoder.encode(destinations, "UTF-8")}" +
            "&units=imperial" +
            "&key=$apiKey"

        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(response)
            val status = json.getString("status")

            if (status != "OK") {
                Log.w(TAG, "API error: $status")
                return clients.map { DrivingInfo(it.id, null, null, null, null) }
            }

            val elements = json.getJSONArray("rows")
                .getJSONObject(0)
                .getJSONArray("elements")

            // Build results map for clients with coords
            val resultMap = mutableMapOf<String, DrivingInfo>()
            withCoords.forEachIndexed { index, client ->
                val element = elements.getJSONObject(index)
                val elemStatus = element.getString("status")

                if (elemStatus == "OK") {
                    val distance = element.getJSONObject("distance")
                    val duration = element.getJSONObject("duration")
                    resultMap[client.id] = DrivingInfo(
                        clientId = client.id,
                        distanceMeters = distance.getInt("value"),
                        distanceText = distance.getString("text"),
                        durationSeconds = duration.getInt("value"),
                        durationText = duration.getString("text")
                    )
                } else {
                    resultMap[client.id] = DrivingInfo(client.id, null, null, null, null)
                }
            }

            // Return in original order
            clients.map { client ->
                resultMap[client.id] ?: DrivingInfo(client.id, null, null, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Distance Matrix request failed: ${e.message}")
            clients.map { DrivingInfo(it.id, null, null, null, null) }
        }
    }
}
