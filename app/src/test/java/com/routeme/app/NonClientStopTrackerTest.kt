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

class NonClientStopTrackerTest {

    @Test
    fun `creates stop after threshold when stationary`() {
        val tracker = newTracker()
        val here = location(42.0, -85.0)

        val first = tracker.onLocationTick(
            location = here,
            nowMillis = 1_000L,
            isNearClientOrActiveArrival = false,
            thresholdMs = 300_000L
        )
        assertNull(first.createStop)

        val second = tracker.onLocationTick(
            location = here,
            nowMillis = 301_000L,
            isNearClientOrActiveArrival = false,
            thresholdMs = 300_000L
        )

        val create = second.createStop
        assertNotNull(create)
        assertEquals(42.0, create!!.lat, 0.0)
        assertEquals(-85.0, create.lng, 0.0)
        assertEquals(1_000L, create.arrivedAtMillis)
        assertEquals(300_000L, create.elapsedMillis)
    }

    @Test
    fun `pending dwell is cancelled when entering client area`() {
        val tracker = newTracker()
        val here = location(42.0, -85.0)

        tracker.onLocationTick(here, nowMillis = 1_000L, isNearClientOrActiveArrival = false, thresholdMs = 300_000L)
        val cancel = tracker.onLocationTick(here, nowMillis = 2_000L, isNearClientOrActiveArrival = true, thresholdMs = 300_000L)

        assertTrue(cancel.pendingCancelledNearClient)
        assertNull(cancel.createStop)
    }

    @Test
    fun `active stop closes when moved beyond depart radius`() {
        val tracker = newTracker()
        val far = location(42.005, -85.0)

        tracker.onStopInserted(stopId = 77L, lat = 42.0, lng = -85.0, arrivedAtMillis = 1_000L)

        val outcome = tracker.onLocationTick(
            location = far,
            nowMillis = 61_000L,
            isNearClientOrActiveArrival = false,
            thresholdMs = 300_000L
        )

        val close = outcome.closeActive
        assertNotNull(close)
        assertEquals(77L, close!!.stopId)
        assertEquals(1_000L, close.arrivedAtMillis)
        assertEquals(61_000L, close.departedAtMillis)
    }

    @Test
    fun `clearAll closes active stop and clears pending`() {
        val tracker = newTracker()
        val here = location(42.0, -85.0)

        tracker.onLocationTick(here, nowMillis = 10L, isNearClientOrActiveArrival = false, thresholdMs = 1000L)
        tracker.onStopInserted(stopId = 4L, lat = 42.0, lng = -85.0, arrivedAtMillis = 100L)

        val close = tracker.clearAll(nowMillis = 1_100L)
        assertNotNull(close)
        assertEquals(4L, close!!.stopId)

        // already cleared
        assertNull(tracker.clearAll(nowMillis = 1_200L))
        val next = tracker.onLocationTick(here, nowMillis = 2_000L, isNearClientOrActiveArrival = false, thresholdMs = 300_000L)
        assertFalse(next.pendingCancelledNearClient)
    }

    private fun newTracker(): NonClientStopTracker {
        return NonClientStopTracker(
            nonClientStopRadiusMeters = 60f,
            nonClientDepartRadiusMeters = 80f,
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
}
