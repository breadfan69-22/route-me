package com.routeme.app

enum class TrackingMode { DRIVING, ARRIVAL }

class TrackingModeController(
    private val modeSwitchCooldownMs: Long,
    private val gpsStaleForFallbackMs: Long,
    private val gpsWeakAccuracyMeters: Float,
    private val providerRefreshCooldownMs: Long
) {
    var currentMode: TrackingMode = TrackingMode.DRIVING
        private set

    var networkFallbackActive: Boolean = false
        private set

    private var lastModeSwitchTime: Long = 0L
    private var lastProviderRefreshTime: Long = 0L
    private var lastGpsFixTime: Long = 0L
    private var lastGpsAccuracyMeters: Float = Float.MAX_VALUE

    fun recordGpsFix(nowMillis: Long, accuracyMeters: Float?) {
        lastGpsFixTime = nowMillis
        if (accuracyMeters != null) {
            lastGpsAccuracyMeters = accuracyMeters
        }
    }

    fun canEvaluateModeSwitch(nowMillis: Long): Boolean {
        return (nowMillis - lastModeSwitchTime) >= modeSwitchCooldownMs
    }

    fun shouldUseNetworkFallback(gpsEnabled: Boolean, nowMillis: Long): Boolean {
        if (!gpsEnabled) return true

        val gpsStale = lastGpsFixTime == 0L || (nowMillis - lastGpsFixTime) > gpsStaleForFallbackMs
        val gpsWeak = lastGpsFixTime != 0L && lastGpsAccuracyMeters > gpsWeakAccuracyMeters
        return gpsStale || gpsWeak
    }

    fun isArrivalRefreshWindowOpen(nowMillis: Long): Boolean {
        if (currentMode != TrackingMode.ARRIVAL) return false
        return (nowMillis - lastProviderRefreshTime) >= providerRefreshCooldownMs
    }

    fun onTrackingModeApplied(mode: TrackingMode, networkFallbackEnabled: Boolean, nowMillis: Long) {
        val changed = currentMode != mode
        currentMode = mode
        networkFallbackActive = networkFallbackEnabled
        if (changed) {
            lastModeSwitchTime = nowMillis
        }
        lastProviderRefreshTime = nowMillis
    }

    fun markProviderRefreshCheck(nowMillis: Long) {
        lastProviderRefreshTime = nowMillis
    }

    fun reset() {
        currentMode = TrackingMode.DRIVING
        networkFallbackActive = false
        lastModeSwitchTime = 0L
        lastProviderRefreshTime = 0L
        lastGpsFixTime = 0L
        lastGpsAccuracyMeters = Float.MAX_VALUE
    }
}
