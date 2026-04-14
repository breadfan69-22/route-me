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
            clusterRadiusMeters = 200f,
            dwellThresholdMs = 60_000L,
            nowMillis = 1_000L
        )
        assertNull(first)

        val second = engine.evaluateArrival(
            location = here,
            trackedClients = listOf(client),
            arrivalRadiusMeters = 60f,
            clusterRadiusMeters = 200f,
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
            clusterRadiusMeters = 200f,
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
        engine.evaluateArrival(here, listOf(client), 60f, 200f, 1_000L, 1_000L)
        engine.evaluateArrival(here, listOf(client), 60f, 200f, 1_000L, 2_100L)

        val firstEval = engine.evaluateDepartures(
            location = far,
            trackedClients = listOf(client),
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 3 * 60 * 1000L,
            nowMillis = 3 * 60 * 1000L + 2_100L
        )

        assertTrue(firstEval.departedClientIds.isEmpty())
        assertTrue(firstEval.completionCandidates.isEmpty())

        val eval = engine.evaluateDepartures(
            location = far,
            trackedClients = listOf(client),
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 3 * 60 * 1000L,
            nowMillis = 3 * 60 * 1000L + 22_100L
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
        engine.evaluateArrival(atA, tracked, 60f, 200f, 500L, 1_000L)
        engine.evaluateArrival(atA, tracked, 60f, 200f, 500L, 1_600L)
        // B arrival should be suppressed while A is active and nearby
        engine.evaluateArrival(atB, tracked, 60f, 200f, 500L, 2_000L)
        val bPrompt = engine.evaluateArrival(atB, tracked, 60f, 200f, 500L, 2_600L)
        assertNull(bPrompt)

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
    fun `cluster expansion includes adjacent neighbor with no active arrival`() {
        val engine = newEngine()
        val a = client("a", 42.0, -85.0)     // client whose arrival fired normally
        val b = client("b", 42.0003, -85.0)  // next-door neighbor, phone stayed in truck
        val tracked = listOf(a, b)

        val atA = location(42.0, -85.0)
        val far = location(42.005, -85.0)    // drove away from both

        // Only A gets an arrival
        engine.evaluateArrival(atA, tracked, 60f, 200f, 500L, 1_000L)
        engine.evaluateArrival(atA, tracked, 60f, 200f, 500L, 1_600L)

        val firstEval = engine.evaluateDepartures(
            location = far,
            trackedClients = tracked,
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 1_000L,
            nowMillis = 10_000L
        )

        assertTrue(firstEval.departedClientIds.isEmpty())
        assertTrue(firstEval.completionCandidates.isEmpty())

        val eval = engine.evaluateDepartures(
            location = far,
            trackedClients = tracked,
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 1_000L,
            nowMillis = 31_000L
        )

        // Both A and B should appear so the user can confirm or uncheck B
        assertEquals(2, eval.completionCandidates.size)
        val ids = eval.completionCandidates.map { it.client.id }.toSet()
        assertTrue("a" in ids)
        assertTrue("b" in ids)
        // B inherits A's arrival time as the best estimate
        val bCandidate = eval.completionCandidates.first { it.client.id == "b" }
        assertEquals(1_000L, bCandidate.arrivedAtMillis)
    }

    @Test
    fun `cluster expansion does not include client outside cluster radius`() {
        val engine = newEngine()
        val a = client("a", 42.0, -85.0)
        val b = client("b", 42.005, -85.0)   // ~555m from A, outside 200m cluster radius
        val tracked = listOf(a, b)

        val atA = location(42.0, -85.0)
        val far = location(42.008, -85.0)    // ~888m from A, ~333m from B

        // Only A gets an arrival
        engine.evaluateArrival(atA, tracked, 60f, 200f, 500L, 1_000L)
        engine.evaluateArrival(atA, tracked, 60f, 200f, 500L, 1_600L)

        val firstEval = engine.evaluateDepartures(
            location = far,
            trackedClients = tracked,
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 1_000L,
            nowMillis = 10_000L
        )

        assertTrue(firstEval.departedClientIds.isEmpty())
        assertTrue(firstEval.completionCandidates.isEmpty())

        val eval = engine.evaluateDepartures(
            location = far,
            trackedClients = tracked,
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 1_000L,
            nowMillis = 31_000L
        )

        // B is too far from A to be pulled in by cluster expansion
        assertEquals(1, eval.completionCandidates.size)
        assertEquals("a", eval.completionCandidates.first().client.id)
    }

    @Test
    fun `cluster expansion includes corner-lot neighbor on different street`() {
        // Reproduces the edge case: next-door property has a perpendicular street address
        // (e.g. 100 Main St vs 100 Oak Ave on a corner lot) but is physically within radius.
        val engine = newEngine()
        val a = client("a", 42.0, -85.0).copy(address = "100 Main St, Anytown")
        val corner = client("corner", 42.0003, -85.0).copy(address = "100 Oak Ave, Anytown")
        val tracked = listOf(a, corner)

        val atA = location(42.0, -85.0)
        val far = location(42.005, -85.0)    // drove away from both

        // Only A gets an arrival (corner lot client had no GPS dwell)
        engine.evaluateArrival(atA, tracked, 60f, 200f, 500L, 1_000L)
        engine.evaluateArrival(atA, tracked, 60f, 200f, 500L, 1_600L)

        val firstEval = engine.evaluateDepartures(
            location = far,
            trackedClients = tracked,
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 1_000L,
            nowMillis = 10_000L
        )

        assertTrue(firstEval.departedClientIds.isEmpty())
        assertTrue(firstEval.completionCandidates.isEmpty())

        val eval = engine.evaluateDepartures(
            location = far,
            trackedClients = tracked,
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 1_000L,
            nowMillis = 31_000L
        )

        // Corner-lot neighbor should be included despite different street name
        assertEquals(2, eval.completionCandidates.size)
        val ids = eval.completionCandidates.map { it.client.id }.toSet()
        assertTrue("a" in ids)
        assertTrue("corner" in ids)
    }

    @Test
    fun `reset clears active state`() {
        val engine = newEngine()
        val client = client("c1", 42.0, -85.0)
        val here = location(42.0, -85.0)

        engine.evaluateArrival(here, listOf(client), 60f, 200f, 1_000L, 1_000L)
        engine.evaluateArrival(here, listOf(client), 60f, 200f, 1_000L, 2_100L)
        assertTrue(engine.hasActiveArrivals())

        engine.reset()
        assertFalse(engine.hasActiveArrivals())
        assertTrue(engine.activeArrivalClientIds().isEmpty())
    }

    @Test
    fun `completed client does not re-trigger arrival after departure`() {
        val engine = newEngine()
        val client = client("c1", 42.0, -85.0)
        val here = location(42.0, -85.0)
        val far = location(42.005, -85.0)

        // Arrive
        engine.evaluateArrival(here, listOf(client), 60f, 200f, 500L, 1_000L)
        engine.evaluateArrival(here, listOf(client), 60f, 200f, 500L, 1_600L)
        assertTrue(engine.hasActiveArrivals())

        // Depart (out-of-range + confirm)
        engine.evaluateDepartures(far, listOf(client), 150f, 200f, 1_000L, 10_000L)
        engine.evaluateDepartures(far, listOf(client), 150f, 200f, 1_000L, 31_000L)
        assertFalse(engine.hasActiveArrivals())

        // Still parked near the same client — should NOT re-trigger arrival
        val reDwell = engine.evaluateArrival(here, listOf(client), 60f, 200f, 500L, 50_000L)
        assertNull(reDwell)
        val reDwell2 = engine.evaluateArrival(here, listOf(client), 60f, 200f, 500L, 50_600L)
        assertNull(reDwell2)
        assertFalse(engine.hasActiveArrivals())
    }

    @Test
    fun `transient out-of-range blip does not clear active arrival`() {
        val engine = newEngine()
        val client = client("c1", 42.0, -85.0)
        val near = location(42.0, -85.0)
        val farBlip = location(42.005, -85.0)

        engine.evaluateArrival(near, listOf(client), 60f, 200f, 1_000L, 1_000L)
        engine.evaluateArrival(near, listOf(client), 60f, 200f, 1_000L, 2_100L)
        assertTrue(engine.hasActiveArrivals())

        val blipEval = engine.evaluateDepartures(
            location = farBlip,
            trackedClients = listOf(client),
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 1_000L,
            nowMillis = 5_000L
        )
        assertTrue(blipEval.departedClientIds.isEmpty())
        assertTrue(engine.hasActiveArrivals())

        val recoveredEval = engine.evaluateDepartures(
            location = near,
            trackedClients = listOf(client),
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = 1_000L,
            nowMillis = 8_000L
        )
        assertTrue(recoveredEval.departedClientIds.isEmpty())
        assertTrue(engine.hasActiveArrivals())
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
        every { location.hasAccuracy() } returns false
        every { location.accuracy } returns 0f
        return location
    }

    private val distanceCalculator: (Double, Double, Double, Double) -> Float = { fromLat, fromLng, toLat, toLng ->
        val dLat = (fromLat - toLat) * 111_000.0
        val dLng = (fromLng - toLng) * 111_000.0
        kotlin.math.sqrt(dLat * dLat + dLng * dLng).toFloat()
    }
}
