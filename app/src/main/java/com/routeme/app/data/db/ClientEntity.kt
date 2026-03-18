package com.routeme.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val zone: String,
    val notes: String,
    val subscribedSteps: String,
    val hasGrub: Boolean,
    val mowDayOfWeek: Int,
    val lawnSizeSqFt: Int,
    val sunShade: String,
    val terrain: String,
    val windExposure: String,
    val cuSpringPending: Boolean,
    val cuFallPending: Boolean,
    val latitude: Double?,
    val longitude: Double?
)
