package com.routeme.app.tracking

import android.location.Location
import android.util.Log
import com.routeme.app.ArrivalDepartureEngine
import com.routeme.app.Client
import com.routeme.app.ClusterMember
import com.routeme.app.LocationTrackingNotifier
import com.routeme.app.TrackingEvent
import java.util.concurrent.ConcurrentHashMap

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
    private data class ArrivalWeatherSnapshot(
        val arrivedAtMillis: Long,
        val tempF: Int?,
        val windMph: Int?,
        val desc: String?
    )

    private val arrivalWeatherByClientId = ConcurrentHashMap<String, ArrivalWeatherSnapshot>()


    fun onLocationTick(location: Location, trackedClients: List<Client>) {
        checkForClientArrival(location, trackedClients)
        checkForDepartures(location, trackedClients)
    }

    fun hasActiveArrivals(): Boolean {
        return arrivalDepartureEngine.hasActiveArrivals()
    }

    fun recordArrivalWeather(
        clientId: String,
        arrivedAtMillis: Long,
        tempF: Int?,
        windMph: Int?,
        desc: String?
    ) {
        arrivalWeatherByClientId[clientId] = ArrivalWeatherSnapshot(
            arrivedAtMillis = arrivedAtMillis,
            tempF = tempF,
            windMph = windMph,
            desc = desc
        )
    }

    fun reset() {
        arrivalDepartureEngine.reset()
        arrivalWeatherByClientId.clear()
    }

    private fun checkForClientArrival(location: Location, trackedClients: List<Client>) {
        val now = nowProvider()
        val prompt = arrivalDepartureEngine.evaluateArrival(
            location = location,
            trackedClients = trackedClients,
            arrivalRadiusMeters = arrivalRadiusMeters,
            clusterRadiusMeters = clusterRadiusMeters,
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

        val completable = evaluation.completionCandidates
        val clientIdsToClearArrivalNotif = (evaluation.departedClientIds + completable.map { it.client.id }).distinct()

        for (clientId in clientIdsToClearArrivalNotif) {
            cancelNotification(arrivalNotifBase + clientId.hashCode())
            arrivalWeatherByClientId.remove(clientId)
        }

        if (completable.size >= 2) {
            val members = completable.map { summary ->
                val weather = arrivalWeatherByClientId[summary.client.id]
                    ?.takeIf { it.arrivedAtMillis == summary.arrivedAtMillis }
                ClusterMember(
                    client = summary.client,
                    timeOnSiteMillis = summary.timeOnSiteMillis,
                    arrivedAtMillis = summary.arrivedAtMillis,
                    location = summary.location,
                    weatherTempF = weather?.tempF,
                    weatherWindMph = weather?.windMph,
                    weatherDesc = weather?.desc
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
