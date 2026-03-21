package com.routeme.app

import android.location.Location

class ArrivalDepartureEngine(
    private val distanceCalculator: (Double, Double, Double, Double) -> Float = { fromLat, fromLng, toLat, toLng ->
        val results = FloatArray(1)
        Location.distanceBetween(fromLat, fromLng, toLat, toLng, results)
        results[0]
    }
) {
    companion object {
        private const val DEPARTURE_CONFIRM_MS = 20_000L
    }


    data class ArrivalPrompt(
        val client: Client,
        val arrivedAtMillis: Long,
        val location: Location
    )

    data class CompletionCandidate(
        val client: Client,
        val arrivedAtMillis: Long,
        val location: Location,
        val timeOnSiteMillis: Long
    )

    data class DepartureEvaluation(
        val departedClientIds: List<String>,
        val completionCandidates: List<CompletionCandidate>
    )

    private data class ActiveArrivalState(
        val client: Client,
        val arrivedAtMillis: Long,
        val location: Location,
        var completionNotified: Boolean = false,
        var outOfRangeSinceMillis: Long? = null
    )

    private var dwellClientId: String? = null
    private var dwellStartTime: Long = 0L
    private val activeArrivals = mutableMapOf<String, ActiveArrivalState>()

    fun hasActiveArrivals(): Boolean = activeArrivals.isNotEmpty()

    fun activeArrivalClientIds(): Set<String> = activeArrivals.keys

    fun reset() {
        dwellClientId = null
        dwellStartTime = 0L
        activeArrivals.clear()
    }

    fun evaluateArrival(
        location: Location,
        trackedClients: List<Client>,
        arrivalRadiusMeters: Float,
        clusterRadiusMeters: Float,
        dwellThresholdMs: Long,
        nowMillis: Long
    ): ArrivalPrompt? {
        val nearestClient = ClientProximityHelper.findNearestClient(
            location = location,
            clients = trackedClients,
            radiusMeters = arrivalRadiusMeters,
            distanceCalculator = distanceCalculator
        )
            ?: run {
                dwellClientId = null
                dwellStartTime = 0L
                return null
            }

        if (hasNearbyActiveArrival(nearestClient, clusterRadiusMeters)) {
            dwellClientId = null
            dwellStartTime = 0L
            return null
        }

        if (dwellClientId == nearestClient.id) {
            val dwelled = nowMillis - dwellStartTime
            if (dwelled >= dwellThresholdMs && !activeArrivals.containsKey(nearestClient.id)) {
                val state = ActiveArrivalState(
                    client = nearestClient,
                    arrivedAtMillis = dwellStartTime,
                    location = location
                )
                activeArrivals[nearestClient.id] = state
                return ArrivalPrompt(nearestClient, dwellStartTime, location)
            }
        } else {
            dwellClientId = nearestClient.id
            dwellStartTime = nowMillis
        }

        return null
    }

    private fun hasNearbyActiveArrival(candidate: Client, clusterRadiusMeters: Float): Boolean {
        if (activeArrivals.isEmpty()) return false
        if (activeArrivals.containsKey(candidate.id)) return false

        val candidateLat = candidate.latitude ?: return false
        val candidateLng = candidate.longitude ?: return false

        for (activeState in activeArrivals.values) {
            val activeLat = activeState.client.latitude ?: continue
            val activeLng = activeState.client.longitude ?: continue
            val distance = distanceCalculator(candidateLat, candidateLng, activeLat, activeLng)
            if (distance <= clusterRadiusMeters) {
                return true
            }
        }

        return false
    }

    fun evaluateDepartures(
        location: Location,
        trackedClients: List<Client>,
        onSiteRadiusMeters: Float,
        clusterRadiusMeters: Float,
        jobMinDurationMs: Long,
        nowMillis: Long
    ): DepartureEvaluation {
        val departed = mutableListOf<String>()
        val completable = mutableListOf<CompletionCandidate>()

        for ((clientId, state) in activeArrivals) {
            val lat = state.client.latitude ?: continue
            val lng = state.client.longitude ?: continue
            val dist = distanceCalculator(location.latitude, location.longitude, lat, lng)
            val effectiveOnSiteRadius = if (location.hasAccuracy()) {
                onSiteRadiusMeters + location.accuracy
            } else {
                onSiteRadiusMeters
            }

            if (dist > effectiveOnSiteRadius) {
                val outOfRangeSince = state.outOfRangeSinceMillis
                if (outOfRangeSince == null) {
                    state.outOfRangeSinceMillis = nowMillis
                    continue
                }

                if (nowMillis - outOfRangeSince < DEPARTURE_CONFIRM_MS) {
                    continue
                }

                val stillInCluster = ClientProximityHelper.isInCluster(
                    departingClient = state.client,
                    location = location,
                    trackedClients = trackedClients,
                    activeArrivalClientIds = activeArrivalClientIds(),
                    clusterRadiusMeters = clusterRadiusMeters,
                    onSiteRadiusMeters = onSiteRadiusMeters,
                    distanceCalculator = distanceCalculator
                )
                if (stillInCluster) continue

                val timeOnSite = nowMillis - state.arrivedAtMillis
                if (timeOnSite >= jobMinDurationMs && !state.completionNotified) {
                    state.completionNotified = true
                    completable.add(
                        CompletionCandidate(
                            client = state.client,
                            arrivedAtMillis = state.arrivedAtMillis,
                            location = state.location,
                            timeOnSiteMillis = timeOnSite
                        )
                    )
                }

                departed.add(clientId)
            } else {
                state.outOfRangeSinceMillis = null
            }
        }

        // Cluster expansion: include adjacent tracked clients with no active arrival.
        // Handles the "phone in truck" case where only one client fired an arrival but
        // the neighbor was also serviced. The user can uncheck any they didn't do.
        if (completable.isNotEmpty()) {
            val alreadyIncluded = completable.map { it.client.id }.toMutableSet()
            val synthetic = mutableListOf<CompletionCandidate>()
            for (candidate in completable) {
                val cLat = candidate.client.latitude ?: continue
                val cLng = candidate.client.longitude ?: continue
                for (neighbor in trackedClients) {
                    if (neighbor.id in alreadyIncluded) continue
                    if (neighbor.id in activeArrivals) continue
                    val nLat = neighbor.latitude ?: continue
                    val nLng = neighbor.longitude ?: continue
                    if (distanceCalculator(cLat, cLng, nLat, nLng) <= clusterRadiusMeters) {
                        synthetic.add(
                            CompletionCandidate(
                                client = neighbor,
                                arrivedAtMillis = candidate.arrivedAtMillis,
                                location = candidate.location,
                                timeOnSiteMillis = candidate.timeOnSiteMillis
                            )
                        )
                        alreadyIncluded.add(neighbor.id)
                    }
                }
            }
            completable.addAll(synthetic)
        }

        departed.forEach { activeArrivals.remove(it) }
        return DepartureEvaluation(departedClientIds = departed, completionCandidates = completable)
    }
}
