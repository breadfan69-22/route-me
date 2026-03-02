package com.routeme.app

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientProximityHelperTest {

    @Test
    fun `isNearAnyClient respects radius`() {
        val location = location(42.0, -85.0)
        val nearClient = client("near", 42.0001, -85.0)
        val farClient = client("far", 42.05, -85.0)

        val isNear = ClientProximityHelper.isNearAnyClient(
            location = location,
            clients = listOf(farClient, nearClient),
            radiusMeters = 50f,
            distanceCalculator = distanceCalculator
        )

        assertTrue(isNear)

        val isNearTinyRadius = ClientProximityHelper.isNearAnyClient(
            location = location,
            clients = listOf(nearClient),
            radiusMeters = 5f,
            distanceCalculator = distanceCalculator
        )
        assertFalse(isNearTinyRadius)
    }

    @Test
    fun `findNearestClient returns nearest within radius`() {
        val location = location(42.0, -85.0)
        val c1 = client("a", 42.002, -85.0)
        val c2 = client("b", 42.0005, -85.0)

        val nearest = ClientProximityHelper.findNearestClient(
            location = location,
            clients = listOf(c1, c2),
            radiusMeters = 500f,
            distanceCalculator = distanceCalculator
        )

        assertNotNull(nearest)
        assertEquals("b", nearest!!.id)

        val none = ClientProximityHelper.findNearestClient(
            location = location,
            clients = listOf(c1, c2),
            radiusMeters = 10f,
            distanceCalculator = distanceCalculator
        )
        assertNull(none)
    }

    @Test
    fun `isInCluster true when neighbor close and active or on-site`() {
        val departing = client("dep", 42.0, -85.0)
        val neighbor = client("n1", 42.0003, -85.0)
        val far = client("far", 42.03, -85.0)

        val userNearNeighbor = location(42.00031, -85.0)
        val byOnSite = ClientProximityHelper.isInCluster(
            departingClient = departing,
            location = userNearNeighbor,
            trackedClients = listOf(departing, neighbor, far),
            activeArrivalClientIds = emptySet(),
            clusterRadiusMeters = 100f,
            onSiteRadiusMeters = 50f,
            distanceCalculator = distanceCalculator
        )
        assertTrue(byOnSite)

        val userFarButNeighborActive = location(42.01, -85.0)
        val byActiveNeighbor = ClientProximityHelper.isInCluster(
            departingClient = departing,
            location = userFarButNeighborActive,
            trackedClients = listOf(departing, neighbor),
            activeArrivalClientIds = setOf("n1"),
            clusterRadiusMeters = 100f,
            onSiteRadiusMeters = 50f,
            distanceCalculator = distanceCalculator
        )
        assertTrue(byActiveNeighbor)
    }

    @Test
    fun `isInCluster false when no qualifying neighbors`() {
        val departing = client("dep", 42.0, -85.0)
        val far = client("far", 42.03, -85.0)
        val user = location(42.01, -85.0)

        val result = ClientProximityHelper.isInCluster(
            departingClient = departing,
            location = user,
            trackedClients = listOf(departing, far),
            activeArrivalClientIds = emptySet(),
            clusterRadiusMeters = 100f,
            onSiteRadiusMeters = 50f,
            distanceCalculator = distanceCalculator
        )

        assertFalse(result)
    }

    private fun client(id: String, lat: Double?, lng: Double?): Client {
        return Client(
            id = id,
            name = "Client-$id",
            address = "123 Test",
            zone = "KAL",
            notes = "",
            subscribedSteps = setOf(1),
            hasGrub = false,
            mowDayOfWeek = 0,
            lawnSizeSqFt = 0,
            sunShade = "",
            terrain = "",
            windExposure = "",
            latitude = lat,
            longitude = lng,
            records = mutableListOf()
        )
    }

    private fun location(lat: Double, lng: Double): Location {
        val location = mockk<Location>()
        every { location.latitude } returns lat
        every { location.longitude } returns lng
        return location
    }

    private val distanceCalculator: (Double, Double, Double, Double) -> Float = { fromLat, fromLng, toLat, toLng ->
        val dLat = (fromLat - toLat) * 111_000.0
        val dLng = (fromLng - toLng) * 111_000.0
        kotlin.math.sqrt(dLat * dLat + dLng * dLng).toFloat()
    }
}
