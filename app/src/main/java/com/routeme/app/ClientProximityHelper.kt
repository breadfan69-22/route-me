package com.routeme.app

import android.location.Location

object ClientProximityHelper {

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
