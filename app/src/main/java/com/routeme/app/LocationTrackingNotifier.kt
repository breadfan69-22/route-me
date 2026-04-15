package com.routeme.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.app.NotificationCompat

class LocationTrackingNotifier(
    private val context: Context,
    private val channelId: String,
    private val eventChannelId: String,
    private val arrivalNotifBase: Int,
    private val completeNotifBase: Int,
    private val clusterNotifBase: Int
) {

    fun buildTrackingNotification(): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.notif_tracking_title))
            .setContentText(context.getString(R.string.notif_tracking_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun postArrivalNotification(client: Client, location: Location, arrivedAtMillis: Long) {
        val notifId = arrivalNotifBase + client.id.hashCode()
        val requestCode = notifId

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(LocationTrackingService.EXTRA_ARRIVAL_CLIENT_ID, client.id)
            putExtra(LocationTrackingService.EXTRA_ARRIVAL_LAT, location.latitude)
            putExtra(LocationTrackingService.EXTRA_ARRIVAL_LNG, location.longitude)
            putExtra(LocationTrackingService.EXTRA_ARRIVAL_TIME, location.time)
            putExtra(LocationTrackingService.EXTRA_ARRIVAL_ARRIVED_AT, arrivedAtMillis)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, requestCode, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.notif_arrival_title, client.name))
            .setContentText(context.getString(R.string.notif_arrival_text, client.address))
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(notifId, notification)
    }

    fun postCompletionNotification(
        client: Client,
        minutesOnSite: Int,
        location: Location,
        arrivedAtMillis: Long,
        completedAtMillis: Long
    ) {
        val notifId = completeNotifBase + client.id.hashCode()
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.cancel(arrivalNotifBase + client.id.hashCode())

        val openIntent = buildCompletionIntent(
            client = client,
            minutesOnSite = minutesOnSite,
            location = location,
            arrivedAtMillis = arrivedAtMillis,
            completedAtMillis = completedAtMillis,
            completionAction = LocationTrackingService.COMPLETE_ACTION_PROMPT
        )
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneIntent = buildCompletionIntent(
            client = client,
            minutesOnSite = minutesOnSite,
            location = location,
            arrivedAtMillis = arrivedAtMillis,
            completedAtMillis = completedAtMillis,
            completionAction = LocationTrackingService.COMPLETE_ACTION_DONE
        )
        val donePendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notYetIntent = buildCompletionIntent(
            client = client,
            minutesOnSite = minutesOnSite,
            location = location,
            arrivedAtMillis = arrivedAtMillis,
            completedAtMillis = completedAtMillis,
            completionAction = LocationTrackingService.COMPLETE_ACTION_NOT_YET
        )
        val notYetPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            notYetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val propertyIntent = buildCompletionIntent(
            client = client,
            minutesOnSite = minutesOnSite,
            location = location,
            arrivedAtMillis = arrivedAtMillis,
            completedAtMillis = completedAtMillis,
            completionAction = LocationTrackingService.COMPLETE_ACTION_PROPERTY
        )
        val propertyPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 10_000,
            propertyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, eventChannelId)
            .setContentTitle(context.getString(R.string.notif_complete_title, client.name))
            .setContentText(context.getString(R.string.notif_complete_text, client.address, minutesOnSite))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.checkbox_on_background,
                context.getString(R.string.notif_action_done),
                donePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.notif_action_not_yet),
                notYetPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_edit,
                context.getString(R.string.notif_action_property),
                propertyPendingIntent
            )
            .build()

        manager.notify(notifId, notification)
    }

    private fun buildCompletionIntent(
        client: Client,
        minutesOnSite: Int,
        location: Location,
        arrivedAtMillis: Long,
        completedAtMillis: Long,
        completionAction: String
    ): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "com.routeme.app.complete.$completionAction.${client.id}"
            putExtra(LocationTrackingService.EXTRA_COMPLETE_CLIENT_ID, client.id)
            putExtra(LocationTrackingService.EXTRA_COMPLETE_MINUTES, minutesOnSite)
            putExtra(LocationTrackingService.EXTRA_COMPLETE_LAT, location.latitude)
            putExtra(LocationTrackingService.EXTRA_COMPLETE_LNG, location.longitude)
            putExtra(LocationTrackingService.EXTRA_COMPLETE_TIME, location.time)
            putExtra(LocationTrackingService.EXTRA_COMPLETE_ARRIVED_AT, arrivedAtMillis)
            putExtra(LocationTrackingService.EXTRA_COMPLETE_COMPLETED_AT, completedAtMillis)
            putExtra(LocationTrackingService.EXTRA_COMPLETE_ACTION, completionAction)
        }
    }

    fun postClusterCompletionNotification(members: List<ClusterMember>): Int {
        val notifId = clusterNotifBase + members.hashCode()
        val requestCode = notifId
        val manager = context.getSystemService(NotificationManager::class.java)

        val clientIds = members.map { it.client.id }.toTypedArray()
        val minutesArray = members.map { (it.timeOnSiteMillis / 60_000).toInt() }.toIntArray()
        val arrivedAtArray = members.map { it.arrivedAtMillis }.toLongArray()
        val completedAtArray = members.map { it.completedAtMillis }.toLongArray()
        val weatherTempArray = members.map { it.weatherTempF ?: Int.MIN_VALUE }.toIntArray()
        val weatherWindArray = members.map { it.weatherWindMph ?: Int.MIN_VALUE }.toIntArray()
        val weatherDescArray = members.map { it.weatherDesc.orEmpty() }.toTypedArray()
        val names = members.joinToString(", ") { it.client.name }

        members.forEach { member ->
            manager.cancel(arrivalNotifBase + member.client.id.hashCode())
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(LocationTrackingService.EXTRA_CLUSTER_CLIENT_IDS, clientIds)
            putExtra(LocationTrackingService.EXTRA_CLUSTER_MINUTES, minutesArray)
            putExtra(LocationTrackingService.EXTRA_CLUSTER_ARRIVED_AT, arrivedAtArray)
            putExtra(LocationTrackingService.EXTRA_CLUSTER_COMPLETED_AT, completedAtArray)
            putExtra(LocationTrackingService.EXTRA_CLUSTER_WEATHER_TEMP_F, weatherTempArray)
            putExtra(LocationTrackingService.EXTRA_CLUSTER_WEATHER_WIND_MPH, weatherWindArray)
            putExtra(LocationTrackingService.EXTRA_CLUSTER_WEATHER_DESC, weatherDescArray)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, requestCode, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, eventChannelId)
            .setContentTitle(context.getString(R.string.notif_cluster_title, members.size))
            .setContentText(context.getString(R.string.notif_cluster_text, names))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(notifId, notification)
        return notifId
    }
}
