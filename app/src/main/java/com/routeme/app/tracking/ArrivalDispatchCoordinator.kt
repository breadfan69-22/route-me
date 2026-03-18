package com.routeme.app.tracking

import android.location.Location
import android.util.Log
import com.routeme.app.ArrivalDepartureEngine
import com.routeme.app.Client
import com.routeme.app.ClusterMember
import com.routeme.app.LocationTrackingNotifier
import com.routeme.app.TrackingEvent

class ArrivalDispatchCoordinator(
    private val tag: String,
    private val arrivalDepartureEngine: ArrivalDepartureEngine,
    private val trackingNotifier: LocationTrackingNotifier,
    private val arrivalNotifBase: Int,
    private val completeNotifBase: Int,
    private val arrivalRadiusMeters: Float,
    private val dwellThresholdMs: Long,
    private val onSiteRadiusMeters: Float,
    private val clusterRadiusMeters: Float,
    private val jobMinDurationMs: Long,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val cancelNotification: (Int) -> Unit,
    private val postToMainThread: ((() -> Unit) -> Unit),
    private val emitEvent: (TrackingEvent) -> Unit,
    private val logDebug: (String) -> Unit = { message -> Log.d(tag, message) }
) {

    fun onLocationTick(location: Location, trackedClients: List<Client>) {
        checkForClientArrival(location, trackedClients)
        checkForDepartures(location, trackedClients)
    }

    fun hasActiveArrivals(): Boolean {
        return arrivalDepartureEngine.hasActiveArrivals()
    }

    fun reset() {
        arrivalDepartureEngine.reset()
    }

    private fun checkForClientArrival(location: Location, trackedClients: List<Client>) {
        val now = nowProvider()
        val prompt = arrivalDepartureEngine.evaluateArrival(
            location = location,
            trackedClients = trackedClients,
            arrivalRadiusMeters = arrivalRadiusMeters,
            dwellThresholdMs = dwellThresholdMs,
            nowMillis = now
        )

        if (prompt != null) {
            logDebug("Dwell threshold reached at ${prompt.client.name}! Triggering arrival.")
            postToMainThread {
                logDebug("Firing arrival event for ${prompt.client.name}")
                trackingNotifier.postArrivalNotification(prompt.client, prompt.location, prompt.arrivedAtMillis)
                emitEvent(TrackingEvent.ClientArrival(prompt.client, prompt.arrivedAtMillis, prompt.location))
            }
        }
    }

    private fun checkForDepartures(location: Location, trackedClients: List<Client>) {
        val now = nowProvider()
        val evaluation = arrivalDepartureEngine.evaluateDepartures(
            location = location,
            trackedClients = trackedClients,
            onSiteRadiusMeters = onSiteRadiusMeters,
            clusterRadiusMeters = clusterRadiusMeters,
            jobMinDurationMs = jobMinDurationMs,
            nowMillis = now
        )

        for (clientId in evaluation.departedClientIds) {
            cancelNotification(arrivalNotifBase + clientId.hashCode())
        }

        val completable = evaluation.completionCandidates
        if (completable.size >= 2) {
            val members = completable.map { summary ->
                ClusterMember(
                    client = summary.client,
                    timeOnSiteMillis = summary.timeOnSiteMillis,
                    arrivedAtMillis = summary.arrivedAtMillis,
                    location = summary.location
                )
            }
            val names = members.joinToString(", ") { it.client.name }
            logDebug("Cluster departure: ${members.size} clients ($names)")

            postToMainThread {
                val notifId = trackingNotifier.postClusterCompletionNotification(members)
                logDebug(
                    "Posted cluster completion notification for ${members.size} clients ($names, notifId=$notifId)"
                )
                emitEvent(TrackingEvent.ClusterComplete(members))
            }
        } else if (completable.size == 1) {
            val summary = completable[0]
            val timeOnSite = summary.timeOnSiteMillis
            val minutesOnSite = (timeOnSite / 60_000).toInt()

            postToMainThread {
                logDebug("Firing job complete for ${summary.client.name} (${minutesOnSite}min)")
                val notifId = completeNotifBase + summary.client.id.hashCode()
                trackingNotifier.postCompletionNotification(
                    summary.client,
                    minutesOnSite,
                    summary.location,
                    summary.arrivedAtMillis
                )
                logDebug(
                    "Posted completion notification for ${summary.client.name} (${minutesOnSite}min, notifId=$notifId)"
                )
                emitEvent(
                    TrackingEvent.JobComplete(
                        summary.client,
                        timeOnSite,
                        summary.arrivedAtMillis,
                        summary.location
                    )
                )
            }
        }
    }
}