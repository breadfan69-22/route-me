package com.routeme.app

import android.content.Context
import android.location.Geocoder
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Geocodes client addresses to lat/lng.
 * Primary: Google Geocoding REST API (requires MAPS_API_KEY).
 * Fallback: Android's built-in Geocoder (Play Services).
 */
object GeocodingHelper {

    private const val TAG = "GeocodingHelper"
    private const val GOOGLE_GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json"

    /** Set this from BuildConfig (same key used by Distance Matrix) */
    var apiKey: String = ""

    /** Zone code → default city for geocoding when no zip is in the address. */
    private val ZONE_CITY = mapOf(
        "KAL"  to "Kalamazoo",
        "KZOO" to "Kalamazoo",
        "N09"  to "Kalamazoo",     // 49009 area, north side
        "S09"  to "Kalamazoo",     // 49009 area, south side
        "POR"  to "Portage",
        "RIC"  to "Richland",
        "PW"   to "Plainwell",
        "MAR"  to "Marshall"
    )

    /** Common abbreviations found in addresses → full city name. */
    private val CITY_ALIASES = mapOf(
        "kzoo"        to "Kalamazoo",
        "k-zoo"       to "Kalamazoo",
        "kalamazoo"   to "Kalamazoo",
        "portage"     to "Portage",
        "parchment"   to "Parchment",
        "richland"    to "Richland",
        "plainwell"   to "Plainwell",
        "otsego"      to "Otsego",
        "mattawan"    to "Mattawan",
        "schoolcraft" to "Schoolcraft",
        "three rivers"     to "Three Rivers",
        "threerivers"      to "Three Rivers",
        "scotts"      to "Scotts",
        "galesburg"   to "Galesburg",
        "marshall"    to "Marshall",
        "battle creek"     to "Battle Creek",
        "battlecreek"      to "Battle Creek",
        "comstock"    to "Comstock",
        "vicksburg"   to "Vicksburg",
        "augusta"     to "Augusta",
        "climax"      to "Climax",
        "cooper"      to "Cooper",
        "texas twp"   to "Kalamazoo",
        "pavilion"    to "Pavilion"
    )

    private val ZIP_PATTERN = Regex("\\b\\d{5}\\b")

    data class GeocodingResult(
        val geocodedCount: Int,
        val failedCount: Int,
        val alreadyHadCount: Int,
        val message: String
    )

    private data class GoogleGeocodeResult(
        val coords: Pair<Double, Double>?,
        val status: String?,
        val errorMessage: String? = null
    )

    /**
     * Enriches an address so the geocoder can resolve it reliably.
     * - Expands city abbreviations (Kzoo → Kalamazoo)
     * - Appends ", MI" if not already present
     * - Uses zone as fallback city when the address has no recognizable city
     */
    fun enrichAddress(rawAddress: String, zone: String): String {
        var addr = rawAddress.trim()
        if (addr.isBlank()) return addr

        val lower = addr.lowercase()

        // Already has a zip code — just make sure MI is there
        if (ZIP_PATTERN.containsMatchIn(addr)) {
            return if (!lower.contains(" mi") && !lower.contains(",mi")) {
                addr.replace(ZIP_PATTERN, "MI $0")
            } else addr
        }

        // Try to expand known city abbreviations
        var foundCity = false
        for ((alias, fullName) in CITY_ALIASES) {
            // Match the alias as a whole word (case-insensitive)
            val aliasRegex = Regex("\\b${Regex.escape(alias)}\\b", RegexOption.IGNORE_CASE)
            if (aliasRegex.containsMatchIn(addr)) {
                addr = aliasRegex.replace(addr, fullName)
                foundCity = true
                break
            }
        }

        // If no known city found, use the zone mapping as fallback
        if (!foundCity) {
            val zoneCity = ZONE_CITY[zone.uppercase()]
            if (zoneCity != null) {
                addr = "$addr, $zoneCity"
            }
        }

        // Append state if not already there
        if (!addr.lowercase().contains(" mi")) {
            addr = "$addr, MI"
        }

        return addr
    }

    /**
     * Geocodes a single address string and returns lat/lng, or null on failure.
     * Must be called on a background thread.
     */
    fun geocodeAddress(address: String): Pair<Double, Double>? {
        return geocodeWithGoogle(address).coords
    }

    /**
     * Geocodes all clients that don't yet have lat/lng.
     * Must be called on a background thread.
     */
    fun geocodeClients(context: Context, clients: List<Client>): GeocodingResult {
        val hasAndroidGeocoder = Geocoder.isPresent()
        val hasGoogleGeocoder = apiKey.isNotBlank()
        if (!hasAndroidGeocoder && !hasGoogleGeocoder) {
            return GeocodingResult(0, 0, 0, "No geocoding provider available (Google API key missing and Android Geocoder unavailable).")
        }

        val geocoder = if (hasAndroidGeocoder) Geocoder(context, Locale.US) else null
        var geocoded = 0
        var failed = 0
        var alreadyHad = 0
        var googleDisabledForBatch = false
        var googleDisableReason: String? = null

        for (client in clients) {
            if (client.latitude != null && client.longitude != null) {
                alreadyHad++
                continue
            }

            if (client.address.isBlank()) {
                failed++
                continue
            }

            val searchAddress = enrichAddress(client.address, client.zone)

            try {
                if (!googleDisabledForBatch) {
                    val googleResult = geocodeWithGoogle(searchAddress)
                    val googleCoords = googleResult.coords
                    if (googleCoords != null) {
                        client.latitude = googleCoords.first
                        client.longitude = googleCoords.second
                        geocoded++
                        Log.d(TAG, "Geocoded ${client.name} via Google API: ${client.latitude}, ${client.longitude} ($searchAddress)")
                        Thread.sleep(80)
                        continue
                    }

                    val fatalStatuses = setOf("REQUEST_DENIED", "OVER_DAILY_LIMIT", "OVER_QUERY_LIMIT", "INVALID_REQUEST")
                    if (googleResult.status in fatalStatuses) {
                        googleDisabledForBatch = true
                        googleDisableReason = googleResult.errorMessage
                            ?: "Google Geocoding API returned ${googleResult.status}"
                        Log.w(TAG, "Disabling Google geocoding for current batch: $googleDisableReason")
                    }
                }

                if (geocoder != null) {
                    @Suppress("DEPRECATION")
                    val results = geocoder.getFromLocationName(searchAddress, 1)
                    if (!results.isNullOrEmpty()) {
                        client.latitude = results[0].latitude
                        client.longitude = results[0].longitude
                        geocoded++
                        Log.d(TAG, "Geocoded ${client.name} via Android Geocoder: ${client.latitude}, ${client.longitude} ($searchAddress)")
                    } else {
                        failed++
                        Log.w(TAG, "No results for ${client.name}: $searchAddress")
                    }
                } else {
                    failed++
                    Log.w(TAG, "Google geocoding failed and Android Geocoder unavailable for ${client.name}: $searchAddress")
                }

                // Small delay to avoid hammering providers
                Thread.sleep(80)
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "Failed to geocode ${client.name}: ${e.message}")
            }
        }

        val message = buildString {
            append("Geocoded $geocoded addresses")
            if (alreadyHad > 0) append(", $alreadyHad already had coordinates")
            if (failed > 0) append(", $failed failed")
            append(".")
            if (googleDisableReason != null) {
                append(" ⚠ $googleDisableReason")
            }
        }
        return GeocodingResult(geocoded, failed, alreadyHad, message)
    }

    private fun geocodeWithGoogle(address: String): GoogleGeocodeResult {
        if (apiKey.isBlank()) return GoogleGeocodeResult(null, null)

        return try {
            val encodedAddress = URLEncoder.encode(address, "UTF-8")
            val url = "$GOOGLE_GEOCODE_URL?address=$encodedAddress&key=$apiKey"

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(response)
            val status = json.optString("status")
            if (status != "OK") {
                val errorMsg = json.optString("error_message", "")
                Log.w(TAG, "Google geocode status=$status for address: $address" +
                    if (errorMsg.isNotBlank()) " — $errorMsg" else "")
                return GoogleGeocodeResult(null, status, errorMsg.ifBlank { null })
            }

            val results = json.optJSONArray("results") ?: return GoogleGeocodeResult(null, status)
            if (results.length() == 0) return GoogleGeocodeResult(null, status)

            val location = results
                .getJSONObject(0)
                .getJSONObject("geometry")
                .getJSONObject("location")

            val lat = location.getDouble("lat")
            val lng = location.getDouble("lng")
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                Log.w(TAG, "Google geocode returned out-of-range coords: $lat, $lng")
                return GoogleGeocodeResult(null, status)
            }
            GoogleGeocodeResult(Pair(lat, lng), status)
        } catch (e: Exception) {
            Log.w(TAG, "Google geocoding request failed for '$address': ${e.message}")
            GoogleGeocodeResult(null, null)
        }
    }

    /**
     * Reverse-geocodes lat/lng to a human-readable address string.
     * Uses Google Geocoding API (same key as forward geocoding).
     * Must be called on a background thread.
     *
     * @return formatted address or null if lookup failed.
     */
    fun reverseGeocode(lat: Double, lng: Double): String? {
        if (apiKey.isBlank()) {
            Log.w(TAG, "Reverse geocode skipped: no API key")
            return null
        }

        return try {
            val url = "$GOOGLE_GEOCODE_URL?latlng=$lat,$lng&key=$apiKey"

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(response)
            val status = json.optString("status")
            if (status != "OK") {
                Log.w(TAG, "Reverse geocode status=$status for ($lat, $lng)")
                return null
            }

            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) return null

            val formatted = results.getJSONObject(0).optString("formatted_address", "")
            if (formatted.isNotBlank()) {
                Log.d(TAG, "Reverse geocoded ($lat, $lng) → $formatted")
                formatted
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed for ($lat, $lng): ${e.message}")
            null
        }
    }
}
