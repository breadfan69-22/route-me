package com.routeme.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "non_client_stops")
data class NonClientStopEntity(
    @PrimaryKey(autoGenerate = true) val stopId: Long = 0,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val arrivedAtMillis: Long,
    val departedAtMillis: Long? = null,
    val durationMinutes: Long = 0,
    val label: String? = null
)
