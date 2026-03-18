package com.routeme.app

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "client_stop_events",
    indices = [
        Index("endedAtMillis"),
        Index("clientId")
    ]
)
data class ClientStopEventEntity(
    @PrimaryKey(autoGenerate = true) val stopEventId: Long = 0,
    val clientId: String,
    val clientName: String,
    val arrivedAtMillis: Long?,
    val endedAtMillis: Long,
    val durationMinutes: Long,
    val status: String,
    val serviceTypes: String = "",
    val cancelReason: String? = null,
    val notes: String = "",
    val lat: Double? = null,
    val lng: Double? = null
)
