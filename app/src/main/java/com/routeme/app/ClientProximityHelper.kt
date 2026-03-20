package com.routeme.app

import android.location.Location
import java.util.Locale

object ClientProximityHelper {

    private val HOUSE_NUMBER_RE = Regex("""^\d+[A-Za-z]?\s+""")
    private val ROAD_SUFFIX_RE = Regex(
        """\s+(ave|avenue|blvd|boulevard|cir|circle|ct|court|cr|cres|crescent|dr|drive|expy|expressway|fwy|freeway|hwy|highway|ln|lane|pkwy|parkway|pl|place|rd|road|sq|square|st|street|ter|terrace|trl|trail|way)$"""
    )

    /**
     * Extracts the street name from a standard US address string, lower-cased,
     * with common road-type suffixes stripped so that e.g. "High Pointe Cir" and
     * "High Pointe" (same physical cul-de-sac, inconsistent suffix in client data)
     * compare as equal.
     * e.g. "123 Oak Ave, Anytown, ST" → "oak"
     *      "6275 High Pointe Cir, Portage" → "high pointe"
     *      "6290 High Pointe, Portage"    → "high pointe"
     * Returns null if the address is blank or no street portion can be parsed.
     */
    fun extractStreetName(address: String): String? {
        val streetPortion = address.trim().substringBefore(',').trim()
        val withoutNumber = streetPortion.replaceFirst(HOUSE_NUMBER_RE, "")
        val lower = withoutNumber.lowercase(Locale.getDefault()).trimEnd()
        val withoutSuffix = lower.replace(ROAD_SUFFIX_RE, "")
        return withoutSuffix.ifBlank { null }
    }

    private val defaultDistanceCalculator: (Double, Double, Double, Double) -> Float = { fromLat, fromLng, toLat, toLng ->
        val results = FloatArray(1)
        Location.distanceBetween(fromLat, fromLng, toLat, toLng, results)
        results[0]
    }

    fun isNearAnyClient(
        location: Location,
        clients: List<Client>,
        radiusMeters: Float,
        distanceCalculator: (Double, Double, Double, Double) -> Float = defaultDistanceCalculator
    ): Boolean {
        for (client in clients) {
            val lat = client.latitude ?: continue
            val lng = client.longitude ?: continue
            if (distanceMeters(location.latitude, location.longitude, lat, lng, distanceCalculator) <= radiusMeters) {
                return true
            }
        }
        return false
    }

    fun findNearestClient(
        location: Location,
        clients: List<Client>,
        radiusMeters: Float,
        distanceCalculator: (Double, Double, Double, Double) -> Float = defaultDistanceCalculator
    ): Client? {
        var nearest: Client? = null
        var nearestDist = Float.MAX_VALUE

        for (client in clients) {
            val lat = client.latitude ?: continue
            val lng = client.longitude ?: continue

            val dist = distanceMeters(location.latitude, location.longitude, lat, lng, distanceCalculator)
            if (dist < radiusMeters && dist < nearestDist) {
                nearest = client
                nearestDist = dist
            }
        }

        return nearest
    }

    fun isInCluster(
        departingClient: Client,
        location: Location,
        trackedClients: List<Client>,
        activeArrivalClientIds: Set<String>,
        clusterRadiusMeters: Float,
        onSiteRadiusMeters: Float,
        distanceCalculator: (Double, Double, Double, Double) -> Float = defaultDistanceCalculator
    ): Boolean {
        val depLat = departingClient.latitude ?: return false
        val depLng = departingClient.longitude ?: return false

        for (neighbor in trackedClients) {
            if (neighbor.id == departingClient.id) continue

            val nLat = neighbor.latitude ?: continue
            val nLng = neighbor.longitude ?: continue

            val clusterDist = distanceMeters(depLat, depLng, nLat, nLng, distanceCalculator)
            if (clusterDist > clusterRadiusMeters) continue

            val userDist = distanceMeters(location.latitude, location.longitude, nLat, nLng, distanceCalculator)
            if (userDist <= onSiteRadiusMeters || neighbor.id in activeArrivalClientIds) {
                return true
            }
        }

        return false
    }

    private fun distanceMeters(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        distanceCalculator: (Double, Double, Double, Double) -> Float
    ): Float {
        return distanceCalculator(fromLat, fromLng, toLat, toLng)
    }
}
