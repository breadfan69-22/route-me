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

class ArrivalDepartureEngineTest {

    @Test
    fun `arrival prompt emits after dwell threshold`() {
        val engine = newEngine()
        val client = client("c1", 42.0, -85.0)
        val here = location(42.0, -85.0)

        val first = engine.evaluateArrival(
            location = here,
            trackedClients = listOf(client),
            arrivalRadiusMeters = 60f,
            dwellThresholdMs = 60_000L,
            nowMillis = 1_000L
        )
        assertNull(first)

        val second = engine.evaluateArrival(
            location = here,
            trackedClients = listOf(client),
            arrivalRadiusMeters = 60f,
            dwellThresholdMs = 60_000L,
            nowMillis = 61_000L
        )
        assertNotNull(second)
        assertEquals("c1", second!!.client.id)
        assertEquals(1_000L, second.arrivedAtMillis)
        assertTrue(engine.hasActiveArrivals())

        // no duplicate prompt while active
        val third = engine.evaluateArrival(
            location = here,
            trackedClients = listOf(client),
            arrivalRadiusMeters = 60f,
            dwellThresholdMs = 60_000L,
            nowMillis = 121_000L
        )
        assertNull(third)
    }

    @Test
    fun `departure emits completion after minimum duration`() {
        val engine = newEngine()
        val client = client("c1", 42.0, -85.0)
        val here = location(42.0, -85.0)
        val far = location(42.005, -85.0)

        // arm dwell then trigger arrival
        engine.evaluateArrival(here, listOf(client), 60f, 1_000L, 1_000L)
        engine.evaluateArrival(here, listOf(client), 60f, 1_000L, 2_100L)

        val eval = engine.evaluateDepartures(
            location = far,
            trackedClients = listOf(client),
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 3 * 60 * 1000L,
            nowMillis = 3 * 60 * 1000L + 2_100L
        )

        assertEquals(listOf("c1"), eval.departedClientIds)
        assertEquals(1, eval.completionCandidates.size)
        assertEquals("c1", eval.completionCandidates.first().client.id)
        assertFalse(engine.hasActiveArrivals())
    }

    @Test
    fun `cluster keeps departure from firing while adjacent active neighbor exists`() {
        val engine = newEngine()
        val a = client("a", 42.0, -85.0)
        val b = client("b", 42.0003, -85.0)
        val tracked = listOf(a, b)

        val atA = location(42.0, -85.0)
        val atB = location(42.0003, -85.0)

        // Activate A
        engine.evaluateArrival(atA, tracked, 60f, 500L, 1_000L)
        engine.evaluateArrival(atA, tracked, 60f, 500L, 1_600L)
        // Activate B
        engine.evaluateArrival(atB, tracked, 60f, 500L, 2_000L)
        engine.evaluateArrival(atB, tracked, 60f, 500L, 2_600L)

        // User is near B and far from A: A should not depart due cluster rule (B active + adjacent)
        val eval = engine.evaluateDepartures(
            location = atB,
            trackedClients = tracked,
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 1_000L,
            nowMillis = 10_000L
        )

        assertTrue(eval.departedClientIds.isEmpty())
        assertTrue(engine.hasActiveArrivals())
    }

    @Test
    fun `reset clears active state`() {
        val engine = newEngine()
        val client = client("c1", 42.0, -85.0)
        val here = location(42.0, -85.0)

        engine.evaluateArrival(here, listOf(client), 60f, 1_000L, 1_000L)
        engine.evaluateArrival(here, listOf(client), 60f, 1_000L, 2_100L)
        assertTrue(engine.hasActiveArrivals())

        engine.reset()
        assertFalse(engine.hasActiveArrivals())
        assertTrue(engine.activeArrivalClientIds().isEmpty())
    }

    private fun newEngine(): ArrivalDepartureEngine {
        return ArrivalDepartureEngine(distanceCalculator = distanceCalculator)
    }

    private fun client(id: String, lat: Double, lng: Double): Client {
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
