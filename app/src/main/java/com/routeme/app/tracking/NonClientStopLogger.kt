package com.routeme.app.tracking

import android.location.Location
import android.util.Log
import com.routeme.app.CloseActiveStopRequest
import com.routeme.app.CreateNonClientStopRequest
import com.routeme.app.NonClientStopDao
import com.routeme.app.NonClientStopEntity
import com.routeme.app.NonClientStopTracker
import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.network.GeocodingHelper
import com.routeme.app.util.AppConfig

class NonClientStopLogger(
    private val tag: String,
    private val preferencesRepository: PreferencesRepository,
    private val nonClientStopDao: NonClientStopDao,
    private val nonClientStopTracker: NonClientStopTracker,
    private val launchAsync: (suspend () -> Unit) -> Unit,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val reverseGeocode: (Double, Double) -> String? = { lat, lng ->
        GeocodingHelper.reverseGeocode(lat, lng)
    },
    private val logDebug: (String) -> Unit = { message -> Log.d(tag, message) },
    private val logWarn: (String) -> Unit = { message -> Log.w(tag, message) }
) {

    fun onLocationTick(location: Location, isNearClientOrActive: Boolean) {
        if (!preferencesRepository.nonClientLoggingEnabled) {
            clearState()
            return
        }

        val now = nowProvider()
        val thresholdMs = preferencesRepository.nonClientStopThresholdMinutes * 60_000L

        val outcome = nonClientStopTracker.onLocationTick(
            location = location,
            nowMillis = now,
            isNearClientOrActiveArrival = isNearClientOrActive,
            thresholdMs = thresholdMs
        )

        if (outcome.pendingCancelledNearClient) {
            logDebug("Non-client dwell cancelled — near a client")
        }

        outcome.pendingResetDistanceMeters?.let {
            logDebug("Non-client dwell reset — moved ${it.toInt()}m")
        }

        outcome.closeActive?.let { request ->
            handleCloseActiveRequest(request)
        }

        outcome.createStop?.let { request ->
            handleCreateStopRequest(request)
        }
    }

    fun clearState() {
        val closeRequest = nonClientStopTracker.clearAll(nowProvider())
        if (closeRequest != null) {
            handleCloseActiveRequest(closeRequest)
        }
    }

    private fun handleCloseActiveRequest(request: CloseActiveStopRequest) {
        val duration = (request.departedAtMillis - request.arrivedAtMillis) / 60_000L
        logDebug("Closing non-client stop #${request.stopId} (${duration}min)")

        launchAsync {
            try {
                nonClientStopDao.updateDeparture(request.stopId, request.departedAtMillis, duration)
            } catch (e: Exception) {
                logWarn("Failed to close non-client stop #${request.stopId}: ${e.message}")
            }
        }
    }

    private fun handleCreateStopRequest(request: CreateNonClientStopRequest) {
        logDebug(
            "Non-client stop detected! Stationary ${request.elapsedMillis / 60_000}min at (${request.lat}, ${request.lng})"
        )

        val shopResults = FloatArray(1)
        Location.distanceBetween(request.lat, request.lng, SHOP_LAT, SHOP_LNG, shopResults)
        val isAtShop = shopResults[0] <= AppConfig.Tracking.NON_CLIENT_SHOP_LABEL_RADIUS_METERS

        val entity = NonClientStopEntity(
            lat = request.lat,
            lng = request.lng,
            arrivedAtMillis = request.arrivedAtMillis,
            label = if (isAtShop) "Shop" else null
        )

        launchAsync {
            try {
                val id = nonClientStopDao.insertStop(entity)
                nonClientStopTracker.onStopInserted(id, request.lat, request.lng, request.arrivedAtMillis)
                logDebug("Non-client stop #$id inserted${if (isAtShop) " (shop)" else ""}")

                if (!isAtShop) {
                    val address = reverseGeocode(request.lat, request.lng)
                    if (address != null) {
                        nonClientStopDao.updateAddress(id, address)
                        logDebug("Non-client stop #$id address: $address")
                    }
                }
            } catch (e: Exception) {
                logWarn("Failed to log non-client stop: ${e.message}")
            }
        }
    }
}
