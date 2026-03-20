package com.routeme.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geocode_cache")
data class GeocodeCacheEntity(
    @PrimaryKey val addressKey: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAtMillis: Long
)
