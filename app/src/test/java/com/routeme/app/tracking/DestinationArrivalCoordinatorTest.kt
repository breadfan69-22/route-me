package com.routeme.app.tracking

import android.location.Location
import com.routeme.app.NonClientStopDao
import com.routeme.app.NonClientStopEntity
import com.routeme.app.SavedDestination
import com.routeme.app.TrackingEvent
import com.routeme.app.data.PreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationArrivalCoordinatorTest {

    @Test
    fun `emits destination reached and inserts labeled stop after dwell`() {
        var nowMillis = 0L
        val events = mutableListOf<TrackingEvent>()
        val preferences = mockk<PreferencesRepository>()
        val dao = mockk<NonClientStopDao>(relaxed = true)

        val destination = destination("dest-1", "Supply House", 42.0, -85.0)
        every { preferences.activeDestination } returns destination
        coEvery { dao.insertStop(any()) } returns 10L

        val coordinator = newCoordinator(
            preferencesRepository = preferences,
            nonClientStopDao = dao,
            nowProvider = { nowMillis },
            emitEvent = { events += it }
        )

        val here = location(42.0, -85.0)

        nowMillis = 1_000L
        coordinator.onLocationTick(here)

        nowMillis = 2_100L
        coordinator.onLocationTick(here)

        coVerify(exactly = 1) {
            dao.insertStop(
                match<NonClientStopEntity> { entity ->
                    entity.lat == 42.0 &&
                        entity.lng == -85.0 &&
                        entity.address == "123 Supply St" &&
                        entity.arrivedAtMillis == 1_000L &&
                        entity.label == "Supply House"
                }
            )
        }

        val reachedEvents = events.filterIsInstance<TrackingEvent.DestinationReached>()
        assertEquals(1, reachedEvents.size)
        assertEquals("Supply House", reachedEvents.first().destinationName)
        assertEquals(1_000L, reachedEvents.first().arrivedAtMillis)
    }

    @Test
    fun `does not fire when currently near client`() {
        var nowMillis = 0L
        val events = mutableListOf<TrackingEvent>()
        val preferences = mockk<PreferencesRepository>()
        val dao = mockk<NonClientStopDao>(relaxed = true)

        every { preferences.activeDestination } returns destination("dest-2", "Shop", 42.0, -85.0)

        val coordinator = newCoordinator(
            preferencesRepository = preferences,
            nonClientStopDao = dao,
            nowProvider = { nowMillis },
            isNearAnyClient = { _, _ -> true },
            emitEvent = { events += it }
        )

        val here = location(42.0, -85.0)

        nowMillis = 1_000L
        coordinator.onLocationTick(here)

        nowMillis = 2_100L
        coordinator.onLocationTick(here)

        coVerify(exactly = 0) { dao.insertStop(any()) }
        assertTrue(events.none { it is TrackingEvent.DestinationReached })
    }

    private fun newCoordinator(
        preferencesRepository: PreferencesRepository,
        nonClientStopDao: NonClientStopDao,
        nowProvider: () -> Long,
        isNearAnyClient: (Location, Float) -> Boolean = { _, _ -> false },
        hasActiveArrivals: () -> Boolean = { false },
        emitEvent: (TrackingEvent) -> Unit
    ): DestinationArrivalCoordinator {
        return DestinationArrivalCoordinator(
            tag = "DestinationArrivalCoordinatorTest",
            destinationDwellDetector = DestinationDwellDetector(
                destinationRadiusMeters = 150f,
                destinationDwellMs = 1_000L,
                nowProvider = nowProvider,
                distanceCalculator = { fromLat, fromLng, toLat, toLng ->
                    val dLat = (fromLat - toLat) * 111_000.0
                    val dLng = (fromLng - toLng) * 111_000.0
                    kotlin.math.sqrt(dLat * dLat + dLng * dLng).toFloat()
                }
            ),
            preferencesRepository = preferencesRepository,
            nonClientStopDao = nonClientStopDao,
            arrivalRadiusMeters = 60f,
            hasActiveArrivals = hasActiveArrivals,
            isNearAnyClient = isNearAnyClient,
            launchAsync = { block -> runBlocking { block() } },
            postToMainThread = { block -> block() },
            emitEvent = emitEvent,
            logDebug = { },
            logWarn = { }
        )
    }

    private fun location(lat: Double, lng: Double): Location {
        val location = mockk<Location>()
        every { location.latitude } returns lat
        every { location.longitude } returns lng
        return location
    }

    private fun destination(id: String, name: String, lat: Double, lng: Double): SavedDestination {
        return SavedDestination(
            id = id,
            name = name,
            address = "123 Supply St",
            lat = lat,
            lng = lng
        )
    }
}
