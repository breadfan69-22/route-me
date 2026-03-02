package com.routeme.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingModeControllerTest {

    @Test
    fun `initial state is driving with fallback off`() {
        val controller = newController()

        assertEquals(TrackingMode.DRIVING, controller.currentMode)
        assertFalse(controller.networkFallbackActive)
    }

    @Test
    fun `mode switch cooldown is enforced`() {
        val controller = newController(modeSwitchCooldownMs = 1_000L)

        controller.onTrackingModeApplied(TrackingMode.ARRIVAL, networkFallbackEnabled = false, nowMillis = 5_000L)

        assertFalse(controller.canEvaluateModeSwitch(5_500L))
        assertTrue(controller.canEvaluateModeSwitch(6_000L))
    }

    @Test
    fun `fallback turns on for stale or weak gps`() {
        val controller = newController(gpsStaleForFallbackMs = 25_000L, gpsWeakAccuracyMeters = 45f)

        // no gps fix yet
        assertTrue(controller.shouldUseNetworkFallback(gpsEnabled = true, nowMillis = 10_000L))

        controller.recordGpsFix(nowMillis = 10_000L, accuracyMeters = 10f)
        assertFalse(controller.shouldUseNetworkFallback(gpsEnabled = true, nowMillis = 20_000L))

        // stale
        assertTrue(controller.shouldUseNetworkFallback(gpsEnabled = true, nowMillis = 40_100L))

        // weak
        controller.recordGpsFix(nowMillis = 50_000L, accuracyMeters = 120f)
        assertTrue(controller.shouldUseNetworkFallback(gpsEnabled = true, nowMillis = 51_000L))
    }

    @Test
    fun `fallback turns on when gps provider disabled`() {
        val controller = newController()
        controller.recordGpsFix(nowMillis = 10_000L, accuracyMeters = 5f)

        assertTrue(controller.shouldUseNetworkFallback(gpsEnabled = false, nowMillis = 11_000L))
    }

    @Test
    fun `arrival refresh window requires arrival mode and cooldown`() {
        val controller = newController(providerRefreshCooldownMs = 30_000L)

        assertFalse(controller.isArrivalRefreshWindowOpen(60_000L))

        controller.onTrackingModeApplied(TrackingMode.ARRIVAL, networkFallbackEnabled = false, nowMillis = 10_000L)
        assertFalse(controller.isArrivalRefreshWindowOpen(20_000L))
        assertTrue(controller.isArrivalRefreshWindowOpen(40_000L))

        controller.markProviderRefreshCheck(40_000L)
        assertFalse(controller.isArrivalRefreshWindowOpen(60_000L))
        assertTrue(controller.isArrivalRefreshWindowOpen(70_000L))
    }

    @Test
    fun `reset returns to defaults`() {
        val controller = newController()
        controller.recordGpsFix(nowMillis = 10_000L, accuracyMeters = 1f)
        controller.onTrackingModeApplied(TrackingMode.ARRIVAL, networkFallbackEnabled = true, nowMillis = 10_000L)

        controller.reset()

        assertEquals(TrackingMode.DRIVING, controller.currentMode)
        assertFalse(controller.networkFallbackActive)
        assertTrue(controller.shouldUseNetworkFallback(gpsEnabled = true, nowMillis = 20_000L))
    }

    private fun newController(
        modeSwitchCooldownMs: Long = 10_000L,
        gpsStaleForFallbackMs: Long = 25_000L,
        gpsWeakAccuracyMeters: Float = 45f,
        providerRefreshCooldownMs: Long = 30_000L
    ): TrackingModeController {
        return TrackingModeController(
            modeSwitchCooldownMs = modeSwitchCooldownMs,
            gpsStaleForFallbackMs = gpsStaleForFallbackMs,
            gpsWeakAccuracyMeters = gpsWeakAccuracyMeters,
            providerRefreshCooldownMs = providerRefreshCooldownMs
        )
    }
}
