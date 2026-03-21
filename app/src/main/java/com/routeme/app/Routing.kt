package com.routeme.app

enum class RouteDirection {
    OUTWARD,
    HOMEWARD
}

data class SavedDestination(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double
)

data class ClientSuggestion(
    val client: Client,
    val daysSinceLast: Int?,
    val distanceMiles: Double?,
    val distanceToShopMiles: Double?,
    val mowWindowPreferred: Boolean,
    val requiresCuOverride: Boolean = false,
    var drivingTime: String? = null,
    var drivingDistance: String? = null,
    var weatherFitSummary: String? = null,
    /** Which of the active service types this client is actually due for. */
    val eligibleSteps: Set<ServiceType> = emptySet()
)
