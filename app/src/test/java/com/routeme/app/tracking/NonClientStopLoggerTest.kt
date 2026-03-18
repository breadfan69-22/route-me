package com.routeme.app.tracking

import android.location.Location
import com.routeme.app.NonClientStopDao
import com.routeme.app.NonClientStopEntity
import com.routeme.app.NonClientStopTracker
import com.routeme.app.data.PreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class NonClientStopLoggerTest {

    @Test
    fun `creates non-client stop and updates address after threshold`() {
        var nowMillis = 0L
        val dao = mockk<NonClientStopDao>(relaxed = true)
        val preferences = mockk<PreferencesRepository>()

        every { preferences.nonClientLoggingEnabled } returns true
        every { preferences.nonClientStopThresholdMinutes } returns 1

        coEvery { dao.insertStop(any()) } returns 9L

        val logger = newLogger(
            nonClientStopDao = dao,
            preferencesRepository = preferences,
            nowProvider = { nowMillis },
            reverseGeocode = { _, _ -> "123 Main St" }
        )

        val location = location(42.0, -85.0)

        nowMillis = 1_000L
        logger.onLocationTick(location)

        nowMillis = 61_000L
        logger.onLocationTick(location)

        coVerify(exactly = 1) {
            dao.insertStop(
                match<NonClientStopEntity> { entity ->
                    entity.lat == 42.0 &&
                        entity.lng == -85.0 &&
                        entity.arrivedAtMillis == 1_000L
                }
            )
        }
        coVerify(exactly = 1) { dao.updateAddress(9L, "123 Main St") }
        coVerify(exactly = 0) { dao.updateDeparture(any(), any(), any()) }
    }

    @Test
    fun `feature toggle off clears active stop and persists departure`() {
        var nowMillis = 0L
        val dao = mockk<NonClientStopDao>(relaxed = true)
        val preferences = mockk<PreferencesRepository>()

        every { preferences.nonClientLoggingEnabled } returns false

        val tracker = newTracker()
        tracker.onStopInserted(stopId = 7L, lat = 42.0, lng = -85.0, arrivedAtMillis = 1_000L)

        val logger = newLogger(
            nonClientStopDao = dao,
            preferencesRepository = preferences,
            nonClientStopTracker = tracker,
            nowProvider = { nowMillis }
        )

        nowMillis = 61_000L
        logger.onLocationTick(location(42.0, -85.0))

        coVerify(exactly = 1) { dao.updateDeparture(7L, 61_000L, 1L) }
        coVerify(exactly = 0) { dao.insertStop(any()) }
    }

    private fun newLogger(
        nonClientStopDao: NonClientStopDao,
        preferencesRepository: PreferencesRepository,
        nonClientStopTracker: NonClientStopTracker = newTracker(),
        nowProvider: () -> Long = { 0L },
        reverseGeocode: (Double, Double) -> String? = { _, _ -> null }
    ): NonClientStopLogger {
        return NonClientStopLogger(
            tag = "NonClientStopLoggerTest",
            preferencesRepository = preferencesRepository,
            nonClientStopDao = nonClientStopDao,
            nonClientStopTracker = nonClientStopTracker,
            arrivalRadiusMeters = 60f,
            hasActiveArrivals = { false },
            isNearAnyClient = { _, _ -> false },
            launchAsync = { block -> runBlocking { block() } },
            nowProvider = nowProvider,
            reverseGeocode = reverseGeocode,
            logDebug = { },
            logWarn = { }
        )
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
