package com.routeme.app.domain

import com.routeme.app.Client
import com.routeme.app.ClientProperty
import com.routeme.app.RouteDirection
import com.routeme.app.ServiceRecord
import com.routeme.app.ServiceType
import com.routeme.app.SunShade
import com.routeme.app.WindExposure
import com.routeme.app.model.DailyWeather
import com.routeme.app.model.RecentWeatherSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RoutingEngineTest {
    private val engine = RoutingEngine()

    @Test
    fun `rankClients returns empty for no matching service type`() {
        val clients = listOf(
            testClient(id = "1", subscribedSteps = setOf(1)),
            testClient(id = "2", subscribedSteps = setOf(2))
        )

        val results = engine.rankClients(
            clients = clients,
            serviceTypes = setOf(ServiceType.ROUND_6),
            minDays = 21,
            lastLocation = null,
            cuOverrideEnabled = false,
            routeDirection = RouteDirection.OUTWARD
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `rankClients favors more overdue clients when distance data unavailable`() {
        val now = System.currentTimeMillis()
        val overdue = testClient(
            id = "overdue",
            latitude = null,
            longitude = null,
            records = mutableListOf(ServiceRecord(serviceType = ServiceType.ROUND_1, completedAtMillis = now - 30L * DAY_MS, durationMinutes = 20, lat = null, lng = null))
        )
        val recent = testClient(
            id = "recent",
            latitude = null,
            longitude = null,
            records = mutableListOf(ServiceRecord(serviceType = ServiceType.ROUND_1, completedAtMillis = now - 22L * DAY_MS, durationMinutes = 20, lat = null, lng = null))
        )

        val results = engine.rankClients(
            clients = listOf(recent, overdue),
            serviceTypes = setOf(ServiceType.ROUND_1),
            minDays = 21,
            lastLocation = null,
            cuOverrideEnabled = false,
            routeDirection = RouteDirection.OUTWARD
        )

        assertEquals("overdue", results.first().client.id)
    }

    @Test
    fun `distanceMilesBetween returns shorter distance for near point than far point`() {
        val originLat = 42.2478
        val originLng = -85.5640
        val nearDistance = engine.distanceMilesBetween(originLat, originLng, 42.2480, -85.5642)
        val farDistance = engine.distanceMilesBetween(originLat, originLng, 42.4500, -85.8500)

        assertTrue(nearDistance < farDistance)
        assertTrue(nearDistance >= 0.0)
    }

    @Test
    fun `isCuBlockedForService handles spring and fall rules`() {
        val springBlocked = testClient(id = "s", cuSpringPending = true)
        val fallBlocked = testClient(id = "f", cuFallPending = true)

        assertTrue(engine.isCuBlockedForService(springBlocked, ServiceType.ROUND_1))
        assertTrue(engine.isCuBlockedForService(fallBlocked, ServiceType.ROUND_6))
        assertFalse(engine.isCuBlockedForService(springBlocked, ServiceType.ROUND_3))
    }

    @Test
    fun `isGoodDayToVisit returns false on mow day`() {
        assertFalse(engine.isGoodDayToVisit(Calendar.MONDAY, Calendar.MONDAY))
    }

    @Test
    fun `daysSinceLast returns null when no records`() {
        val client = testClient(id = "x", records = mutableListOf())
        val days = engine.daysSinceLast(client, ServiceType.ROUND_1)
        assertEquals(null, days)
    }

    @Test
    fun `daysSinceLast returns value for existing records`() {
        val now = System.currentTimeMillis()
        val client = testClient(
            id = "y",
            records = mutableListOf(ServiceRecord(serviceType = ServiceType.ROUND_1, completedAtMillis = now - 10L * DAY_MS, durationMinutes = 20, lat = null, lng = null))
        )

        val days = engine.daysSinceLast(client, ServiceType.ROUND_1)

        assertTrue(days != null)
        assertTrue(days!! >= 9)
        assertTrue(days <= 11)
    }

    @Test
    fun `rankClients penalizes exposed properties in high wind`() {
        val exposed = testClient(id = "exposed")
        val sheltered = testClient(id = "sheltered")
        val weather = DailyWeather(
            dateMillis = System.currentTimeMillis(),
            highTempF = 70,
            lowTempF = 50,
            windSpeedMph = 22,
            windGustMph = 35,
            windDirection = "W",
            precipitationInches = 0.0,
            description = "Windy"
        )

        val results = engine.rankClients(
            clients = listOf(exposed, sheltered),
            serviceTypes = setOf(ServiceType.ROUND_1),
            minDays = 21,
            lastLocation = null,
            cuOverrideEnabled = false,
            routeDirection = RouteDirection.OUTWARD,
            weather = weather,
            recentPrecipInches = 0.0,
            propertyMap = mapOf(
                exposed.id to ClientProperty(
                    clientId = exposed.id,
                    lawnSizeSqFt = 20000,
                    sunShade = SunShade.UNKNOWN,
                    windExposure = WindExposure.EXPOSED,
                    hasSteepSlopes = false,
                    hasIrrigation = false,
                    propertyNotes = "",
                    updatedAtMillis = System.currentTimeMillis()
                ),
                sheltered.id to ClientProperty(
                    clientId = sheltered.id,
                    lawnSizeSqFt = 20000,
                    sunShade = SunShade.UNKNOWN,
                    windExposure = WindExposure.SHELTERED,
                    hasSteepSlopes = false,
                    hasIrrigation = false,
                    propertyNotes = "",
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        )

        assertEquals("sheltered", results.first().client.id)
    }

    @Test
    fun `rankClients penalizes steep slopes with recent rain`() {
        val steep = testClient(id = "steep")
        val flat = testClient(id = "flat")
        val weather = DailyWeather(
            dateMillis = System.currentTimeMillis(),
            highTempF = 68,
            lowTempF = 45,
            windSpeedMph = 6,
            windGustMph = null,
            windDirection = "N",
            precipitationInches = 0.0,
            description = "Cloudy"
        )

        val results = engine.rankClients(
            clients = listOf(steep, flat),
            serviceTypes = setOf(ServiceType.ROUND_1),
            minDays = 21,
            lastLocation = null,
            cuOverrideEnabled = false,
            routeDirection = RouteDirection.OUTWARD,
            weather = weather,
            recentPrecipInches = 0.4,
            propertyMap = mapOf(
                steep.id to ClientProperty(
                    clientId = steep.id,
                    lawnSizeSqFt = 10000,
                    sunShade = SunShade.UNKNOWN,
                    windExposure = WindExposure.UNKNOWN,
                    hasSteepSlopes = true,
                    hasIrrigation = false,
                    propertyNotes = "",
                    updatedAtMillis = System.currentTimeMillis()
                ),
                flat.id to ClientProperty(
                    clientId = flat.id,
                    lawnSizeSqFt = 10000,
                    sunShade = SunShade.UNKNOWN,
                    windExposure = WindExposure.UNKNOWN,
                    hasSteepSlopes = false,
                    hasIrrigation = false,
                    propertyNotes = "",
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        )

        assertEquals("flat", results.first().client.id)
    }

    @Test
    fun `rankClients penalizes saturated soil on steep slopes`() {
        val steep = testClient(id = "steep-soil")
        val flat = testClient(id = "flat-soil")
        val weather = DailyWeather(
            dateMillis = System.currentTimeMillis(),
            highTempF = 74,
            lowTempF = 58,
            windSpeedMph = 7,
            windGustMph = null,
            windDirection = "W",
            precipitationInches = 0.0,
            description = "Clear"
        )

        val results = engine.rankClients(
            clients = listOf(steep, flat),
            serviceTypes = setOf(ServiceType.ROUND_1),
            minDays = 21,
            lastLocation = null,
            cuOverrideEnabled = false,
            routeDirection = RouteDirection.OUTWARD,
            weather = weather,
            recentPrecipInches = 0.0,
            propertyMap = mapOf(
                steep.id to ClientProperty(
                    clientId = steep.id,
                    lawnSizeSqFt = 11000,
                    sunShade = SunShade.UNKNOWN,
                    windExposure = WindExposure.UNKNOWN,
                    hasSteepSlopes = true,
                    hasIrrigation = false,
                    propertyNotes = "",
                    updatedAtMillis = System.currentTimeMillis()
                ),
                flat.id to ClientProperty(
                    clientId = flat.id,
                    lawnSizeSqFt = 11000,
                    sunShade = SunShade.UNKNOWN,
                    windExposure = WindExposure.UNKNOWN,
                    hasSteepSlopes = false,
                    hasIrrigation = false,
                    propertyNotes = "",
                    updatedAtMillis = System.currentTimeMillis()
                )
            ),
            recentWeatherByClientId = mapOf(
                steep.id to RecentWeatherSignal(
                    rainLast24hInches = 0.02,
                    rainLast48hInches = 0.04,
                    soilMoistureSurface = 0.52,
                    fetchedAtMillis = System.currentTimeMillis()
                ),
                flat.id to RecentWeatherSignal(
                    rainLast24hInches = 0.02,
                    rainLast48hInches = 0.04,
                    soilMoistureSurface = 0.18,
                    fetchedAtMillis = System.currentTimeMillis()
                )
            )
        )

        assertEquals("flat-soil", results.first().client.id)
    }

    private fun testClient(
        id: String,
        subscribedSteps: Set<Int> = setOf(1, 2, 3, 4, 5, 6),
        latitude: Double? = 42.2478,
        longitude: Double? = -85.5640,
        cuSpringPending: Boolean = false,
        cuFallPending: Boolean = false,
        records: List<ServiceRecord> = emptyList()
    ): Client {
        return Client(
            id = id,
            name = "Client-$id",
            address = "123 Test St",
            zone = "KAL",
            notes = "",
            subscribedSteps = subscribedSteps,
            hasGrub = false,
            mowDayOfWeek = 0,
            lawnSizeSqFt = 0,
            sunShade = "",
            terrain = "",
            windExposure = "",
            cuSpringPending = cuSpringPending,
            cuFallPending = cuFallPending,
            latitude = latitude,
            longitude = longitude,
            records = records
        )
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
