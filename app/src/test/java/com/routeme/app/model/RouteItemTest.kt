package com.routeme.app.model

import com.routeme.app.Client
import com.routeme.app.ServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteItemTest {

    @Test
    fun `toRouteItems inserts supply house stop at configured index`() {
        val day = PlannedDay(
            dateMillis = 0L,
            dayOfWeek = 2,
            dayName = "Monday",
            forecast = null,
            dayScore = 80,
            dayScoreLabel = "Great",
            clients = listOf(
                plannedClient(id = "1", lat = 42.1, lng = -85.1),
                plannedClient(id = "2", lat = 42.2, lng = -85.2)
            ),
            isWorkDay = true,
            supplyStopNeeded = true,
            supplyStopAfterIndex = 0
        )

        val items = day.toRouteItems()

        assertEquals(3, items.size)
        assertTrue(items[0] is RouteItem.ClientStop)
        assertTrue(items[1] is RouteItem.SupplyHouseStop)
        assertTrue(items[2] is RouteItem.ClientStop)
    }

    @Test
    fun `toRouteItems inserts supply house before first client when index is minus one`() {
        val day = PlannedDay(
            dateMillis = 0L,
            dayOfWeek = 2,
            dayName = "Monday",
            forecast = null,
            dayScore = 80,
            dayScoreLabel = "Great",
            clients = listOf(
                plannedClient(id = "1", lat = 42.1, lng = -85.1)
            ),
            isWorkDay = true,
            supplyStopNeeded = true,
            supplyStopAfterIndex = -1
        )

        val items = day.toRouteItems()

        assertEquals(2, items.size)
        assertTrue(items[0] is RouteItem.SupplyHouseStop)
        assertTrue(items[1] is RouteItem.ClientStop)
    }

    @Test
    fun `toSavedDestinationOrNull skips unmappable client and preserves supply house`() {
        val items = listOf(
            RouteItem.ClientStop(plannedClient(id = "1", lat = null, lng = null)),
            RouteItem.SupplyHouseStop()
        )

        val destinations = items.mapNotNull { it.toSavedDestinationOrNull() }

        assertEquals(1, destinations.size)
        assertEquals(WEEKLY_PLANNER_SUPPLY_HOUSE_DESTINATION_ID, destinations.first().id)
        assertEquals("SiteOne", destinations.first().name)
    }

    private fun plannedClient(id: String, lat: Double?, lng: Double?): PlannedClient {
        return PlannedClient(
            client = Client(
                id = id,
                name = "Client-$id",
                address = "123 Test St",
                zone = "KAL",
                notes = "",
                subscribedSteps = setOf(1),
                hasGrub = false,
                mowDayOfWeek = 0,
                lawnSizeSqFt = 10_000,
                sunShade = "",
                terrain = "",
                windExposure = "",
                latitude = lat,
                longitude = lng
            ),
            fitnessScore = 75,
            fitnessLabel = "Great",
            primaryReason = "Test",
            eligibleSteps = setOf(ServiceType.ROUND_1),
            daysOverdue = 20
        )
    }
}