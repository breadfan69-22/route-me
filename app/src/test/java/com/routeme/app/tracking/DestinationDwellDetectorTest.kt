package com.routeme.app.tracking

import android.location.Location
import com.routeme.app.SavedDestination
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationDwellDetectorTest {

    @Test
    fun `fires destination reached after dwell threshold`() {
        var nowMillis = 0L
        val detector = newDetector(nowProvider = { nowMillis })
        val destination = destination("dest-1", 42.0, -85.0)
        val here = location(42.0, -85.0)

        nowMillis = 1_000L
        val first = detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = false
        )
        assertNull(first.destinationReached)

        nowMillis = 100_000L
        val second = detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = false
        )
        assertNull(second.destinationReached)

        nowMillis = 181_000L
        val third = detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = false
        )

        val reached = third.destinationReached
        assertNotNull(reached)
        assertEquals("dest-1", reached!!.destination.id)
        assertEquals(1_000L, reached.arrivedAtMillis)
        assertEquals(42.0, reached.anchorLat, 0.0)
        assertEquals(-85.0, reached.anchorLng, 0.0)
        assertEquals(180_000L, reached.elapsedMillis)

        nowMillis = 250_000L
        val duplicate = detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = false
        )
        assertNull(duplicate.destinationReached)
    }

    @Test
    fun `resets dwell when active destination is cleared`() {
        var nowMillis = 0L
        val detector = newDetector(nowProvider = { nowMillis })
        val destination = destination("dest-2", 42.0, -85.0)
        val here = location(42.0, -85.0)

        nowMillis = 1_000L
        detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = false
        )

        detector.onLocationTick(
            location = here,
            activeDestination = null,
            isNearClientOrActiveArrival = false
        )

        nowMillis = 2_000L
        detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = false
        )

        nowMillis = 182_000L
        val reached = detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = false
        ).destinationReached

        assertNotNull(reached)
        assertEquals(2_000L, reached!!.arrivedAtMillis)
    }

    @Test
    fun `resets dwell when near client or active arrival`() {
        var nowMillis = 0L
        val detector = newDetector(nowProvider = { nowMillis })
        val destination = destination("dest-3", 42.0, -85.0)
        val here = location(42.0, -85.0)

        nowMillis = 1_000L
        detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = false
        )

        detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = true
        )

        nowMillis = 5_000L
        detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = false
        )

        nowMillis = 185_000L
        val reached = detector.onLocationTick(
            location = here,
            activeDestination = destination,
            isNearClientOrActiveArrival = false
        ).destinationReached

        assertNotNull(reached)
        assertEquals(5_000L, reached!!.arrivedAtMillis)
        assertTrue(reached.elapsedMillis >= 180_000L)
    }

    private fun newDetector(nowProvider: () -> Long): DestinationDwellDetector {
        return DestinationDwellDetector(
            destinationRadiusMeters = 150f,
            destinationDwellMs = 180_000L,
            nowProvider = nowProvider,
            distanceCalculator = { fromLat, fromLng, toLat, toLng ->
                val dLat = (fromLat - toLat) * 111_000.0
                val dLng = (fromLng - toLng) * 111_000.0
                kotlin.math.sqrt(dLat * dLat + dLng * dLng).toFloat()
            }
        )
    }

    private fun location(lat: Double, lng: Double): Location {
        val location = mockk<Location>()
        every { location.latitude } returns lat
        every { location.longitude } returns lng
        return location
    }

    private fun destination(id: String, lat: Double, lng: Double): SavedDestination {
        return SavedDestination(
            id = id,
            name = "Destination-$id",
            address = "Addr $id",
            lat = lat,
            lng = lng
        )
    }
}