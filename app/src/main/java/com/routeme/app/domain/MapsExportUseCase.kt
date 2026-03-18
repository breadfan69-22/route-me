package com.routeme.app.domain

import com.routeme.app.Client
import com.routeme.app.ClientSuggestion
import com.routeme.app.RouteDirection
import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.SavedDestination
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MapsExportUseCase(
    private val routeExportTopN: Int = 12,
    private val maxGoogleWaypoints: Int = 8
) {
    data class GeoPoint(
        val latitude: Double,
        val longitude: Double
    )

    data class RouteExport(
        val uri: String,
        val includedStops: Int,
        val requestedStops: Int
    )

    sealed interface ExportResult {
        data class Success(val routeExport: RouteExport) : ExportResult
        data object NoSuggestions : ExportResult
        data object NoMappableClients : ExportResult
    }

    fun exportTopRoute(
        suggestions: List<ClientSuggestion>,
        routeDirection: RouteDirection,
        activeDestination: SavedDestination?,
        originLocation: GeoPoint?
    ): ExportResult {
        if (suggestions.isEmpty()) {
            return ExportResult.NoSuggestions
        }

        val routeExport = buildTopRouteExportFromSuggestions(
            suggestions = suggestions,
            routeDirection = routeDirection,
            activeDestination = activeDestination,
            originLocation = originLocation
        ) ?: return ExportResult.NoMappableClients

        return ExportResult.Success(routeExport)
    }

    private fun buildTopRouteExportFromSuggestions(
        suggestions: List<ClientSuggestion>,
        routeDirection: RouteDirection,
        activeDestination: SavedDestination?,
        originLocation: GeoPoint?
    ): RouteExport? {
        val mappableClients = suggestions
            .map { it.client }
            .filter { (it.latitude != null && it.longitude != null) || it.address.isNotBlank() }

        if (mappableClients.isEmpty()) return null

        val requestedStops = minOf(routeExportTopN, mappableClients.size)
        val topStops = mappableClients.take(requestedStops)

        return buildMapsRouteExport(
            clients = topStops,
            routeDirection = routeDirection,
            activeDestination = activeDestination,
            originLocation = originLocation
        )
    }

    private fun buildMapsRouteExport(
        clients: List<Client>,
        routeDirection: RouteDirection,
        activeDestination: SavedDestination?,
        originLocation: GeoPoint?
    ): RouteExport? {
        if (clients.isEmpty()) return null

        val origin = originLocation?.let { "${it.latitude},${it.longitude}" } ?: "$SHOP_LAT,$SHOP_LNG"

        if (activeDestination != null) {
            val usableStops = clients.take(maxGoogleWaypoints)
            val destination = "${activeDestination.lat},${activeDestination.lng}"
            val waypoints = usableStops.mapNotNull { locationToken(it) }
            val uri = buildMapsDirectionsUrl(origin, destination, waypoints)
            return RouteExport(uri, usableStops.size, clients.size)
        }

        return when (routeDirection) {
            RouteDirection.OUTWARD -> {
                val usableStops = clients.take(maxGoogleWaypoints + 1)
                if (usableStops.isEmpty()) return null

                val destination = locationToken(usableStops.last()) ?: return null
                val waypoints = usableStops.dropLast(1).mapNotNull { locationToken(it) }
                val uri = buildMapsDirectionsUrl(origin, destination, waypoints)
                RouteExport(uri, usableStops.size, clients.size)
            }

            RouteDirection.HOMEWARD -> {
                val usableStops = clients.take(maxGoogleWaypoints)
                val destination = "$SHOP_LAT,$SHOP_LNG"
                val waypoints = usableStops.mapNotNull { locationToken(it) }
                val uri = buildMapsDirectionsUrl(origin, destination, waypoints)
                RouteExport(uri, usableStops.size, clients.size)
            }
        }
    }

    private fun buildMapsDirectionsUrl(origin: String, destination: String, waypoints: List<String>): String {
        val base = StringBuilder("https://www.google.com/maps/dir/?api=1")
        base.append("&travelmode=driving")
        base.append("&origin=").append(encodeQueryParam(origin))
        base.append("&destination=").append(encodeQueryParam(destination))
        if (waypoints.isNotEmpty()) {
            base.append("&waypoints=").append(encodeQueryParam(waypoints.joinToString("|")))
        }
        return base.toString()
    }

    private fun locationToken(client: Client): String? {
        val lat = client.latitude
        val lng = client.longitude
        if (lat != null && lng != null) {
            return "$lat,$lng"
        }

        val address = client.address.trim()
        return address.takeIf { it.isNotBlank() }
    }

    private fun encodeQueryParam(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }
}
