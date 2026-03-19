package com.routeme.app.domain

import com.routeme.app.Client

class ArrivalUseCase(
    private val routingEngine: RoutingEngine,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    data class GeoPoint(
        val latitude: Double,
        val longitude: Double
    )

    data class MarkArrivalResult(
        val selectedClient: Client,
        val selectedClientDetails: String,
        val arrivalStartedAtMillis: Long,
        val arrivalLat: Double,
        val arrivalLng: Double
    )

    data class StaleArrivalPrompt(
        val clientName: String,
        val minutesElapsed: Long
    )

    sealed class StartArrivalResult {
        data class Started(val arrival: MarkArrivalResult) : StartArrivalResult()
        data class Error(val message: String) : StartArrivalResult()
    }

    sealed class ResolveStaleResult {
        data class ConfirmAndContinue(val deferredAction: (() -> Unit)?) : ResolveStaleResult()
        data class DiscardAndContinue(
            val statusMessage: String,
            val deferredAction: (() -> Unit)?
        ) : ResolveStaleResult()
    }

    private var pendingActionAfterStaleResolve: (() -> Unit)? = null
    private var staleArrivalSuppressed = false

    fun startArrivalForSelected(selectedClient: Client?, currentLocation: GeoPoint?): StartArrivalResult {
        if (selectedClient == null) {
            return StartArrivalResult.Error("Pick a client first")
        }
        if (currentLocation == null) {
            return StartArrivalResult.Error("Unable to get current location")
        }

        return StartArrivalResult.Started(
            markArrivalForClient(
                client = selectedClient,
                location = currentLocation,
                startedAtMillis = nowProvider()
            )
        )
    }

    fun cancelArrival(arrivalStartedAtMillis: Long?, selectedClientName: String?): String? {
        if (arrivalStartedAtMillis == null) return null
        val name = selectedClientName ?: "client"
        return "Cancelled arrival for $name"
    }

    fun createStaleArrivalPrompt(
        arrivalStartedAtMillis: Long?,
        selectedClientName: String?,
        deferredAction: () -> Unit
    ): StaleArrivalPrompt? {
        val started = arrivalStartedAtMillis ?: return null
        val clientName = selectedClientName ?: return null
        if (staleArrivalSuppressed) return null

        val minutes = ((nowProvider() - started) / 60_000L).coerceAtLeast(1)
        pendingActionAfterStaleResolve = deferredAction
        return StaleArrivalPrompt(clientName, minutes)
    }

    fun resolveStaleArrival(markComplete: Boolean, selectedClientName: String?): ResolveStaleResult {
        val action = pendingActionAfterStaleResolve
        pendingActionAfterStaleResolve = null
        staleArrivalSuppressed = false

        return if (markComplete) {
            ResolveStaleResult.ConfirmAndContinue(action)
        } else {
            val name = selectedClientName ?: "client"
            ResolveStaleResult.DiscardAndContinue(
                statusMessage = "Discarded arrival for $name",
                deferredAction = action
            )
        }
    }

    fun dropPendingStaleAction() {
        pendingActionAfterStaleResolve = null
    }

    /** Suppress stale-arrival prompts and run the pending action. Arrival timer keeps ticking. */
    fun hideStaleArrival(): (() -> Unit)? {
        val action = pendingActionAfterStaleResolve
        pendingActionAfterStaleResolve = null
        staleArrivalSuppressed = true
        return action
    }

    fun resetStaleArrivalSuppression() {
        staleArrivalSuppressed = false
    }

    fun markArrivalForClient(
        client: Client,
        location: GeoPoint,
        startedAtMillis: Long = nowProvider()
    ): MarkArrivalResult {
        return MarkArrivalResult(
            selectedClient = client,
            selectedClientDetails = routingEngine.buildClientDetails(client),
            arrivalStartedAtMillis = startedAtMillis,
            arrivalLat = location.latitude,
            arrivalLng = location.longitude
        )
    }
}
