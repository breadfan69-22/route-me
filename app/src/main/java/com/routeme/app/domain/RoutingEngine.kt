package com.routeme.app.domain

import android.location.Location
import com.routeme.app.Client
import com.routeme.app.ClientProximityHelper
import com.routeme.app.ClientSuggestion
import com.routeme.app.RouteDirection
import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.SavedDestination
import com.routeme.app.ServiceType
import com.routeme.app.network.GeocodingHelper
import com.routeme.app.util.AppConfig
import com.routeme.app.util.DateUtils
import java.util.Calendar
import java.util.Locale

class RoutingEngine {
    private data class ScoredSuggestion(
        val suggestion: ClientSuggestion,
        val score: Double
    )

    fun rankClients(
        clients: List<Client>,
        serviceTypes: Set<ServiceType>,
        minDays: Int,
        lastLocation: Location?,
        cuOverrideEnabled: Boolean,
        routeDirection: RouteDirection,
        skippedClientIds: Set<String> = emptySet(),
        destination: SavedDestination? = null
    ): List<ClientSuggestion> {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val destLat = destination?.lat ?: SHOP_LAT
        val destLng = destination?.lng ?: SHOP_LNG
        val userDistanceToShopMiles = lastLocation?.let { loc ->
            distanceMilesBetween(loc.latitude, loc.longitude, destLat, destLng)
        }

        val scored = clients
            .asSequence()
            .filter { it.id !in skippedClientIds }
            .mapNotNull { client ->
                // Determine which of the requested types this client is eligible for
                val eligible = serviceTypes.filter { st ->
                    val subscribed = when (st) {
                        ServiceType.INCIDENTAL -> true
                        ServiceType.GRUB -> client.hasGrub
                        else -> client.subscribedSteps.contains(st.stepNumber)
                    }
                    if (!subscribed) return@filter false
                    if (!cuOverrideEnabled && isCuBlockedForService(client, st)) return@filter false
                    val days = daysSinceLast(client, st)
                    days == null || days >= minDays
                }.toSet()
                if (eligible.isEmpty()) return@mapNotNull null

                val distance = distanceMiles(client, lastLocation)
                val distanceToShopMiles = if (client.latitude != null && client.longitude != null) {
                    val lat = client.latitude
                    val lng = client.longitude
                    distanceMilesBetween(lat, lng, destLat, destLng)
                } else {
                    null
                }
                val mowPreferred = isGoodDayToVisit(today, client.mowDayOfWeek)
                // Use the MOST overdue step for ranking
                val mostOverdueDays = eligible.maxOfOrNull { st ->
                    daysSinceLast(client, st) ?: Int.MAX_VALUE
                }
                // CU override flag: true if any eligible step is CU-blocked
                val requiresCuOverride = eligible.any { isCuBlockedForService(client, it) }

                ClientSuggestion(
                    client = client,
                    daysSinceLast = mostOverdueDays?.let { if (it == Int.MAX_VALUE) null else it },
                    distanceMiles = distance,
                    distanceToShopMiles = distanceToShopMiles,
                    mowWindowPreferred = mowPreferred,
                    requiresCuOverride = requiresCuOverride,
                    eligibleSteps = eligible
                )
            }
            .map {
                ScoredSuggestion(
                    suggestion = it,
                    score = calculateRouteScore(
                        suggestion = it,
                        routeDirection = routeDirection,
                        userDistanceToShopMiles = userDistanceToShopMiles,
                        minDays = minDays,
                        today = today
                    )
                )
            }
            .toList()

        return orderRoute(
            scored = scored,
            currentLocation = lastLocation,
            routeDirection = routeDirection,
            destination = destination
        )
    }

    private fun orderRoute(
        scored: List<ScoredSuggestion>,
        currentLocation: Location?,
        routeDirection: RouteDirection,
        destination: SavedDestination? = null
    ): List<ClientSuggestion> {
        if (scored.isEmpty()) return emptyList()

        if (currentLocation == null) {
            return scored
                .sortedWith(
                    compareByDescending<ScoredSuggestion> { it.score }
                        .thenBy { it.suggestion.distanceToShopMiles ?: Double.MAX_VALUE }
                        .thenByDescending { it.suggestion.daysSinceLast ?: Int.MAX_VALUE }
                )
                .map { it.suggestion }
        }

        val withCoords = scored
            .filter { it.suggestion.client.latitude != null && it.suggestion.client.longitude != null }
            .toMutableList()
        val withoutCoords = scored
            .filter { it.suggestion.client.latitude == null || it.suggestion.client.longitude == null }

        var currentLat = currentLocation.latitude
        var currentLng = currentLocation.longitude
        val destLat = destination?.lat ?: SHOP_LAT
        val destLng = destination?.lng ?: SHOP_LNG
        var currentDistToShop = distanceMilesBetween(currentLat, currentLng, destLat, destLng)
        // Street name of the last-picked stop. Null at the start (raw GPS, no address).
        var currentStreet: String? = null

        val ordered = mutableListOf<ScoredSuggestion>()

        while (withCoords.isNotEmpty()) {
            val next = withCoords.maxByOrNull { candidate ->
                val lat = candidate.suggestion.client.latitude ?: return@maxByOrNull Double.NEGATIVE_INFINITY
                val lng = candidate.suggestion.client.longitude ?: return@maxByOrNull Double.NEGATIVE_INFINITY

                val hopMiles = distanceMilesBetween(currentLat, currentLng, lat, lng)
                val hopPenalty = hopMiles * AppConfig.Routing.ORDER_HOP_PENALTY_PER_MILE
                val candidateStreet = ClientProximityHelper.extractStreetName(candidate.suggestion.client.address)
                // Cluster bonus: within proximity radius AND same street (or street unavailable).
                val clusterBonus = if (hopMiles <= AppConfig.Routing.CLUSTER_RADIUS_MILES &&
                    (currentStreet == null || candidateStreet == null || currentStreet == candidateStreet))
                    AppConfig.Routing.CLUSTER_NEIGHBOR_BONUS else 0.0
                // Same-street bonus: even outside cluster radius, boost same-street candidates.
                val sameStreetBonus = if (clusterBonus == 0.0 &&
                    currentStreet != null && candidateStreet != null && currentStreet == candidateStreet)
                    AppConfig.Routing.SAME_STREET_BONUS else 0.0

                val candidateDistToShop = candidate.suggestion.distanceToShopMiles
                    ?: distanceMilesBetween(lat, lng, destLat, destLng)
                val delta = candidateDistToShop - currentDistToShop

                val directionAdjustment = if (destination != null) {
                    // When a destination is active, always score toward it
                    when {
                        delta < AppConfig.Routing.DESTINATION_DELTA_BETTER_THRESHOLD_MILES -> AppConfig.Routing.DESTINATION_BETTER_BONUS
                        delta > AppConfig.Routing.DESTINATION_DELTA_WORSE_THRESHOLD_MILES -> AppConfig.Routing.DESTINATION_WORSE_PENALTY
                        else -> AppConfig.Routing.DESTINATION_NEUTRAL_ADJUSTMENT
                    }
                } else {
                    when (routeDirection) {
                        RouteDirection.OUTWARD -> when {
                            delta > AppConfig.Routing.OUTWARD_DELTA_BETTER_THRESHOLD_MILES -> AppConfig.Routing.OUTWARD_BETTER_BONUS
                            delta < AppConfig.Routing.OUTWARD_DELTA_WORSE_THRESHOLD_MILES -> AppConfig.Routing.OUTWARD_WORSE_PENALTY
                            else -> AppConfig.Routing.OUTWARD_NEUTRAL_ADJUSTMENT
                        }
                        RouteDirection.HOMEWARD -> when {
                            delta < AppConfig.Routing.HOMEWARD_DELTA_BETTER_THRESHOLD_MILES -> AppConfig.Routing.HOMEWARD_BETTER_BONUS
                            delta > AppConfig.Routing.HOMEWARD_DELTA_WORSE_THRESHOLD_MILES -> AppConfig.Routing.HOMEWARD_WORSE_PENALTY
                            else -> AppConfig.Routing.HOMEWARD_NEUTRAL_ADJUSTMENT
                        }
                    }
                }

                val overdueBonus = ((candidate.suggestion.daysSinceLast ?: 0) / AppConfig.Routing.ORDER_OVERDUE_DIVISOR_DAYS)
                    .coerceAtMost(AppConfig.Routing.ORDER_OVERDUE_BONUS_MAX)

                (candidate.score * AppConfig.Routing.ORDER_BASE_SCORE_WEIGHT) - hopPenalty + clusterBonus + sameStreetBonus + directionAdjustment + overdueBonus
            } ?: break

            ordered.add(next)
            withCoords.remove(next)

            currentLat = next.suggestion.client.latitude ?: currentLat
            currentLng = next.suggestion.client.longitude ?: currentLng
            currentDistToShop = next.suggestion.distanceToShopMiles
                ?: distanceMilesBetween(currentLat, currentLng, destLat, destLng)
            currentStreet = ClientProximityHelper.extractStreetName(next.suggestion.client.address)
        }

        val tail = withoutCoords.sortedWith(
            compareByDescending<ScoredSuggestion> { it.score }
                .thenByDescending { it.suggestion.daysSinceLast ?: Int.MAX_VALUE }
        )

        return (ordered + tail).map { it.suggestion }
    }

    fun isCuBlockedForService(client: Client, serviceType: ServiceType): Boolean {
        return when (serviceType) {
            ServiceType.ROUND_1 -> client.cuSpringPending
            ServiceType.ROUND_6 -> client.cuFallPending
            else -> false
        }
    }

    fun calculateRouteScore(
        suggestion: ClientSuggestion,
        routeDirection: RouteDirection,
        userDistanceToShopMiles: Double?,
        minDays: Int,
        today: Int
    ): Double {
        var score = 0.0

        score += mowWindowAdjustment(today, suggestion.client.mowDayOfWeek)

        val distance = suggestion.distanceMiles
        if (distance != null) {
            score += when {
                distance < AppConfig.Routing.DISTANCE_LT_0_5_MILES -> AppConfig.Routing.DISTANCE_LT_0_5_SCORE
                distance < AppConfig.Routing.DISTANCE_LT_1_MILES -> AppConfig.Routing.DISTANCE_LT_1_SCORE
                distance < AppConfig.Routing.DISTANCE_LT_2_MILES -> AppConfig.Routing.DISTANCE_LT_2_SCORE
                distance < AppConfig.Routing.DISTANCE_LT_3_MILES -> AppConfig.Routing.DISTANCE_LT_3_SCORE
                distance < AppConfig.Routing.DISTANCE_LT_5_MILES -> AppConfig.Routing.DISTANCE_LT_5_SCORE
                distance < AppConfig.Routing.DISTANCE_LT_8_MILES -> AppConfig.Routing.DISTANCE_LT_8_SCORE
                distance < AppConfig.Routing.DISTANCE_LT_12_MILES -> AppConfig.Routing.DISTANCE_LT_12_SCORE
                else -> AppConfig.Routing.DISTANCE_FAR_SCORE
            }
            score -= distance * AppConfig.Routing.DISTANCE_PENALTY_PER_MILE
        } else {
            val distanceToShop = suggestion.distanceToShopMiles
            if (distanceToShop != null) {
                score += (
                    AppConfig.Routing.NO_DISTANCE_BASE_SCORE -
                        (distanceToShop * AppConfig.Routing.NO_DISTANCE_DISTANCE_MULTIPLIER)
                    ).coerceAtLeast(AppConfig.Routing.NO_DISTANCE_MIN_SCORE)
            }
        }

        val clientDistanceToShopMiles = suggestion.distanceToShopMiles
        if (clientDistanceToShopMiles != null && userDistanceToShopMiles != null) {
            val delta = clientDistanceToShopMiles - userDistanceToShopMiles
            if (kotlin.math.abs(delta) <= AppConfig.Routing.DIRECTION_DELTA_NEAR_ZERO_MILES) {
                score += AppConfig.Routing.DIRECTION_NEAR_ZERO_BONUS
            } else {
                when (routeDirection) {
                    RouteDirection.OUTWARD -> {
                        score += when {
                            delta in AppConfig.Routing.OUTWARD_DELTA_RANGE_MIN_MILES..AppConfig.Routing.OUTWARD_DELTA_RANGE_MAX_MILES -> AppConfig.Routing.OUTWARD_RANGE_BONUS
                            delta > AppConfig.Routing.OUTWARD_DELTA_RANGE_MAX_MILES -> AppConfig.Routing.OUTWARD_FAR_BONUS
                            delta < AppConfig.Routing.OUTWARD_REVERSE_THRESHOLD_MILES -> AppConfig.Routing.OUTWARD_REVERSE_PENALTY
                            else -> AppConfig.Routing.OUTWARD_DEFAULT_ADJUSTMENT
                        }
                    }
                    RouteDirection.HOMEWARD -> {
                        score += when {
                            delta in AppConfig.Routing.HOMEWARD_DELTA_RANGE_MIN_MILES..AppConfig.Routing.HOMEWARD_DELTA_RANGE_MAX_MILES -> AppConfig.Routing.HOMEWARD_RANGE_BONUS
                            delta < AppConfig.Routing.HOMEWARD_DELTA_RANGE_MIN_MILES -> AppConfig.Routing.HOMEWARD_FAR_BONUS
                            delta > AppConfig.Routing.HOMEWARD_REVERSE_THRESHOLD_MILES -> AppConfig.Routing.HOMEWARD_REVERSE_PENALTY
                            else -> AppConfig.Routing.HOMEWARD_DEFAULT_ADJUSTMENT
                        }
                    }
                }
            }
        }

        val days = suggestion.daysSinceLast
        if (days == null) {
            score += AppConfig.Routing.NEVER_SERVED_BONUS
        } else if (days >= minDays) {
            val daysOverdue = days - minDays
            score += AppConfig.Routing.OVERDUE_BASE_BONUS +
                (daysOverdue * AppConfig.Routing.OVERDUE_PER_DAY_BONUS)
            if (days >= AppConfig.Routing.OVERDUE_60_DAY_THRESHOLD) {
                score += AppConfig.Routing.OVERDUE_60_DAY_BONUS
            }
            if (days >= AppConfig.Routing.OVERDUE_90_DAY_THRESHOLD) {
                score += AppConfig.Routing.OVERDUE_90_DAY_BONUS
            }
        }

        if (suggestion.requiresCuOverride) {
            score -= AppConfig.Routing.CU_OVERRIDE_PENALTY
        }

        return score
    }

    private fun mowWindowAdjustment(today: Int, mowDay: Int): Double {
        if (mowDay == 0) return 0.0

        val daysUntilMow = (mowDay - today + 7) % 7
        val daysSinceMow = (today - mowDay + 7) % 7

        return when {
            daysUntilMow == 0 -> AppConfig.Routing.MOW_SAME_DAY_PENALTY
            daysUntilMow == 1 || daysSinceMow == 1 -> AppConfig.Routing.MOW_ADJACENT_DAY_PENALTY
            daysUntilMow in 2..3 -> AppConfig.Routing.MOW_NEAR_TERM_BONUS
            else -> AppConfig.Routing.MOW_DEFAULT_BONUS
        }
    }

    fun isGoodDayToVisit(today: Int, mowDay: Int): Boolean {
        if (mowDay == 0) return true

        val daysUntilMow = (mowDay - today + 7) % 7
        val daysSinceMow = (today - mowDay + 7) % 7
        val inAvoidWindow = daysUntilMow <= 1 || daysSinceMow == 1

        return !inAvoidWindow
    }

    fun daysSinceLast(client: Client, serviceType: ServiceType): Int? {
        val last = client.records
            .filter { it.serviceType == serviceType || serviceType == ServiceType.INCIDENTAL }
            .maxByOrNull { it.completedAtMillis }
            ?: return null

        val diff = System.currentTimeMillis() - last.completedAtMillis
        return (diff / AppConfig.Routing.MILLIS_PER_DAY).toInt()
    }

    fun distanceMiles(client: Client, lastLocation: Location?): Double? {
        val current = lastLocation ?: return null
        val lat = client.latitude ?: return null
        val lng = client.longitude ?: return null

        return distanceMilesBetween(current.latitude, current.longitude, lat, lng)
    }

    fun distanceMilesBetween(startLat: Double, startLng: Double, endLat: Double, endLng: Double): Double {
        val dLat = Math.toRadians(endLat - startLat)
        val dLng = Math.toRadians(endLng - startLng)
        val lat1 = Math.toRadians(startLat)
        val lat2 = Math.toRadians(endLat)

        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2) *
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return AppConfig.Routing.EARTH_RADIUS_MILES * c
    }

    fun buildClientDetails(client: Client): String {
        val lines = mutableListOf<String>()
        lines.add("Selected: ${client.name}")
        lines.add("Address: ${client.address}")
        lines.add("Zone: ${client.zone}")
        if (client.latitude == null || client.longitude == null) {
            lines.add("⚠ Not geocoded")
        }
        lines.add("Steps: ${client.subscribedSteps.sorted().joinToString(", ")}")
        if (client.hasGrub) lines.add("Grub treatment: Yes")
        if (client.mowDayOfWeek != 0) lines.add("Mow day: ${DateUtils.dayName(client.mowDayOfWeek)}")
        if (client.notes.isNotBlank()) lines.add("Notes: ${client.notes}")
        val lastService = client.records.maxByOrNull { it.completedAtMillis }
        if (lastService != null) {
            lines.add("Last service: ${lastService.serviceType.label} on ${DateUtils.formatTimestamp(lastService.completedAtMillis)}")
        }
        return lines.joinToString("\n")
    }

    /**
     * Optimize the visit order for a list of destinations using nearest-neighbor + 2-opt.
     * Returns the same destinations in an improved order starting from [startLat]/[startLng].
     */
    fun optimizeDestinationOrder(
        destinations: List<SavedDestination>,
        startLat: Double,
        startLng: Double
    ): List<SavedDestination> {
        if (destinations.size <= 1) return destinations

        // --- Nearest-neighbor initial route ---
        val remaining = destinations.toMutableList()
        val route = mutableListOf<SavedDestination>()
        var curLat = startLat
        var curLng = startLng

        while (remaining.isNotEmpty()) {
            val next = remaining.minByOrNull { distanceMilesBetween(curLat, curLng, it.lat, it.lng) }!!
            route.add(next)
            remaining.remove(next)
            curLat = next.lat
            curLng = next.lng
        }

        if (route.size <= 2) return route

        // --- 2-opt improvement ---
        // Prepend a virtual start node for distance calculations
        val lats = DoubleArray(route.size + 1)
        val lngs = DoubleArray(route.size + 1)
        lats[0] = startLat; lngs[0] = startLng
        for (i in route.indices) { lats[i + 1] = route[i].lat; lngs[i + 1] = route[i].lng }

        val n = lats.size
        val order = IntArray(n) { it } // indices into lats/lngs

        fun segDist(i: Int, j: Int) = distanceMilesBetween(lats[order[i]], lngs[order[i]], lats[order[j]], lngs[order[j]])

        var improved = true
        var passes = 0
        while (improved && passes < AppConfig.Routing.TWO_OPT_MAX_PASSES) {
            improved = false
            passes++
            for (i in 1 until n - 1) {
                for (j in i + 1 until n) {
                    val oldDist = segDist(i - 1, i) + if (j + 1 < n) segDist(j, j + 1) else 0.0
                    val newDist = segDist(i - 1, j) + if (j + 1 < n) segDist(i, j + 1) else 0.0
                    if (newDist < oldDist - AppConfig.Routing.TWO_OPT_IMPROVEMENT_EPSILON) {
                        // Reverse the segment between i and j
                        var left = i; var right = j
                        while (left < right) {
                            val tmp = order[left]; order[left] = order[right]; order[right] = tmp
                            left++; right--
                        }
                        improved = true
                    }
                }
            }
        }

        // Map back, skipping index 0 (the virtual start node)
        return (1 until n).map { destinations[order[it] - 1] }
    }
}
