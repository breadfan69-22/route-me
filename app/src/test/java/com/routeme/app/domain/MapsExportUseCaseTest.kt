package com.routeme.app.domain

import com.routeme.app.Client
import com.routeme.app.ClientSuggestion
import com.routeme.app.RouteDirection
import com.routeme.app.SavedDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsExportUseCaseTest {

    @Test
    fun `exportErrandsRoute returns NoDestinations when queue is empty`() {
        val useCase = MapsExportUseCase()

        val result = useCase.exportErrandsRoute(
            destinationQueue = emptyList(),
            activeDestinationIndex = 0,
            originLocation = null
        )

        assertTrue(result is MapsExportUseCase.ExportResult.NoDestinations)
    }

    @Test
    fun `exportErrandsRoute starts from active index and preserves queue order`() {
        val useCase = MapsExportUseCase()
        val queue = listOf(
            destination("a", 42.10, -85.10),
            destination("b", 42.20, -85.20),
            destination("c", 42.30, -85.30)
        )

        val result = useCase.exportErrandsRoute(
            destinationQueue = queue,
            activeDestinationIndex = 1,
            originLocation = MapsExportUseCase.GeoPoint(42.00, -85.00)
        )

        assertTrue(result is MapsExportUseCase.ExportResult.Success)
        val success = result as MapsExportUseCase.ExportResult.Success
        assertEquals(2, success.routeExport.includedStops)
        assertEquals(2, success.routeExport.requestedStops)
        assertTrue(success.routeExport.uri.contains("origin=42.0%2C-85.0"))
        assertTrue(success.routeExport.uri.contains("waypoints=42.2%2C-85.2"))
        assertTrue(success.routeExport.uri.contains("destination=42.3%2C-85.3"))
    }

    @Test
    fun `exportErrandsRoute enforces Google waypoint cap`() {
        val useCase = MapsExportUseCase()
        val queue = (1..12).map { idx ->
            destination(
                id = "d$idx",
                lat = 42.0 + idx / 100.0,
                lng = -85.0 - idx / 100.0
            )
        }

        val result = useCase.exportErrandsRoute(
            destinationQueue = queue,
            activeDestinationIndex = 0,
            originLocation = null
        )

        assertTrue(result is MapsExportUseCase.ExportResult.Success)
        val success = result as MapsExportUseCase.ExportResult.Success
        assertEquals(9, success.routeExport.includedStops)
        assertEquals(12, success.routeExport.requestedStops)
    }

    @Test
    fun `exportErrandsRoute uses shop as fallback origin when location missing`() {
        val useCase = MapsExportUseCase()
        val queue = listOf(destination("a", 42.10, -85.10))

        val result = useCase.exportErrandsRoute(
            destinationQueue = queue,
            activeDestinationIndex = 0,
            originLocation = null
        )

        assertTrue(result is MapsExportUseCase.ExportResult.Success)
        val success = result as MapsExportUseCase.ExportResult.Success
        assertTrue(success.routeExport.uri.contains("origin=42.2478%2C-85.564"))
    }

    @Test
    fun `exportTopRoute returns no suggestions when list is empty`() {
        val useCase = MapsExportUseCase()

        val result = useCase.exportTopRoute(
            suggestions = emptyList(),
            routeDirection = RouteDirection.OUTWARD,
            activeDestination = null,
            originLocation = null
        )

        assertTrue(result is MapsExportUseCase.ExportResult.NoSuggestions)
    }

    @Test
    fun `exportTopRoute returns no mappable clients when all suggestions are unmappable`() {
        val useCase = MapsExportUseCase()
        val suggestions = listOf(
            suggestion(testClient("1").copy(latitude = null, longitude = null, address = "  ")),
            suggestion(testClient("2").copy(latitude = null, longitude = null, address = ""))
        )

        val result = useCase.exportTopRoute(
            suggestions = suggestions,
            routeDirection = RouteDirection.OUTWARD,
            activeDestination = null,
            originLocation = null
        )

        assertTrue(result is MapsExportUseCase.ExportResult.NoMappableClients)
    }

    @Test
    fun `outward export clips to max Google stops`() {
        val useCase = MapsExportUseCase()
        val suggestions = (1..12).map { idx ->
            suggestion(
                testClient("$idx").copy(
                    latitude = 42.0 + idx / 1000.0,
                    longitude = -85.0 - idx / 1000.0
                )
            )
        }

        val result = useCase.exportTopRoute(
            suggestions = suggestions,
            routeDirection = RouteDirection.OUTWARD,
            activeDestination = null,
            originLocation = MapsExportUseCase.GeoPoint(42.2478, -85.564)
        )

        assertTrue(result is MapsExportUseCase.ExportResult.Success)
        val success = result as MapsExportUseCase.ExportResult.Success
        assertEquals(9, success.routeExport.includedStops)
        assertEquals(12, success.routeExport.requestedStops)
        assertTrue(success.routeExport.uri.contains("origin=42.2478%2C-85.564"))
    }

    @Test
    fun `homeward export always ends at shop`() {
        val useCase = MapsExportUseCase()
        val suggestions = listOf(suggestion(testClient("1")), suggestion(testClient("2")))

        val result = useCase.exportTopRoute(
            suggestions = suggestions,
            routeDirection = RouteDirection.HOMEWARD,
            activeDestination = null,
            originLocation = null
        )

        assertTrue(result is MapsExportUseCase.ExportResult.Success)
        val success = result as MapsExportUseCase.ExportResult.Success
        assertTrue(success.routeExport.uri.contains("destination=42.2478%2C-85.564"))
    }

    @Test
    fun `active destination export uses destination coordinates and waypoint cap`() {
        val useCase = MapsExportUseCase()
        val suggestions = (1..12).map { idx -> suggestion(testClient("$idx")) }
        val destination = SavedDestination(
            id = "dest-1",
            name = "Warehouse",
            address = "123 Warehouse",
            lat = 42.555,
            lng = -85.777
        )

        val result = useCase.exportTopRoute(
            suggestions = suggestions,
            routeDirection = RouteDirection.OUTWARD,
            activeDestination = destination,
            originLocation = null
        )

        assertTrue(result is MapsExportUseCase.ExportResult.Success)
        val success = result as MapsExportUseCase.ExportResult.Success
        assertEquals(8, success.routeExport.includedStops)
        assertEquals(12, success.routeExport.requestedStops)
        assertTrue(success.routeExport.uri.contains("destination=42.555%2C-85.777"))
    }

    private fun suggestion(client: Client): ClientSuggestion {
        return ClientSuggestion(
            client = client,
            daysSinceLast = 30,
            distanceMiles = 1.0,
            distanceToShopMiles = 1.0,
            mowWindowPreferred = true
        )
    }

    private fun destination(id: String, lat: Double, lng: Double): SavedDestination {
        return SavedDestination(
            id = id,
            name = "Destination-$id",
            address = "123 Destination St",
            lat = lat,
            lng = lng
        )
    }

    private fun testClient(id: String): Client {
        return Client(
            id = id,
            name = "Client-$id",
            address = "123 Test St",
            zone = "KAL",
            notes = "",
            subscribedSteps = setOf(1, 2, 3),
            hasGrub = false,
            mowDayOfWeek = 0,
            lawnSizeSqFt = 0,
            sunShade = "",
            terrain = "",
            windExposure = "",
            latitude = 42.2,
            longitude = -85.5,
            records = mutableListOf()
        )
    }
}
