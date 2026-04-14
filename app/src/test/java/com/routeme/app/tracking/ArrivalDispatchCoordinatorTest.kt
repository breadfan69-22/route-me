package com.routeme.app.tracking

import android.location.Location
import com.routeme.app.ArrivalDepartureEngine
import com.routeme.app.Client
import com.routeme.app.LocationTrackingNotifier
import com.routeme.app.TrackingEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalDispatchCoordinatorTest {

    @Test
    fun `emits arrival event and posts arrival notification after dwell`() {
        var nowMillis = 0L
        val events = mutableListOf<TrackingEvent>()
        val notifier = mockk<LocationTrackingNotifier>(relaxed = true)

        val coordinator = newCoordinator(
            notifier = notifier,
            nowProvider = { nowMillis },
            emitEvent = { events += it }
        )

        val client = client("c1", 42.0, -85.0)
        val here = location(42.0, -85.0)

        nowMillis = 1_000L
        coordinator.onLocationTick(here, listOf(client))

        nowMillis = 2_100L
        coordinator.onLocationTick(here, listOf(client))

        val arrivalEvents = events.filterIsInstance<TrackingEvent.ClientArrival>()
        assertEquals(1, arrivalEvents.size)
        assertEquals("c1", arrivalEvents.first().client.id)
        verify(exactly = 1) {
            notifier.postArrivalNotification(client, any(), 1_000L)
        }
    }

    @Test
    fun `emits completion event and cancels arrival notification on departure`() {
        var nowMillis = 0L
        val events = mutableListOf<TrackingEvent>()
        val canceledNotifications = mutableListOf<Int>()
        val notifier = mockk<LocationTrackingNotifier>(relaxed = true)

        val coordinator = newCoordinator(
            notifier = notifier,
            nowProvider = { nowMillis },
            cancelNotification = { canceledNotifications += it },
            emitEvent = { events += it },
            dwellThresholdMs = 1_000L,
            jobMinDurationMs = 1_000L
        )

        val client = client("c2", 42.0, -85.0)
        val near = location(42.0, -85.0)
        val far = location(42.005, -85.0)

        nowMillis = 1_000L
        coordinator.onLocationTick(near, listOf(client))

        nowMillis = 2_100L
        coordinator.onLocationTick(near, listOf(client))

        nowMillis = 66_000L
        coordinator.onLocationTick(far, listOf(client))

        nowMillis = 86_000L
        coordinator.onLocationTick(far, listOf(client))

        val expectedArrivalNotifId = 2_000 + client.id.hashCode()
        assertTrue(canceledNotifications.contains(expectedArrivalNotifId))

        val completionEvents = events.filterIsInstance<TrackingEvent.JobComplete>()
        assertEquals(1, completionEvents.size)
        assertEquals("c2", completionEvents.first().client.id)

        verify(exactly = 1) {
            notifier.postCompletionNotification(client, 1, any(), 1_000L, 86_000L)
        }
    }

    private fun newCoordinator(
        notifier: LocationTrackingNotifier,
        nowProvider: () -> Long,
        cancelNotification: (Int) -> Unit = {},
        emitEvent: (TrackingEvent) -> Unit,
        dwellThresholdMs: Long = 1_000L,
        jobMinDurationMs: Long = 180_000L
    ): ArrivalDispatchCoordinator {
        return ArrivalDispatchCoordinator(
            tag = "ArrivalDispatchCoordinatorTest",
            arrivalDepartureEngine = ArrivalDepartureEngine(
                distanceCalculator = { fromLat, fromLng, toLat, toLng ->
                    val dLat = (fromLat - toLat) * 111_000.0
                    val dLng = (fromLng - toLng) * 111_000.0
                    kotlin.math.sqrt(dLat * dLat + dLng * dLng).toFloat()
                }
            ),
            trackingNotifier = notifier,
            arrivalNotifBase = 2_000,
            completeNotifBase = 3_000,
            arrivalRadiusMeters = 60f,
            dwellThresholdMs = dwellThresholdMs,
            onSiteRadiusMeters = 150f,
            clusterRadiusMeters = 200f,
            jobMinDurationMs = jobMinDurationMs,
            nowProvider = nowProvider,
            cancelNotification = cancelNotification,
            postToMainThread = { block -> block() },
            emitEvent = emitEvent,
            logDebug = { }
        )
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
}
