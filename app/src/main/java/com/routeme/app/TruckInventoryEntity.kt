package com.routeme.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "truck_inventory")
data class TruckInventoryEntity(
    @PrimaryKey val productType: String,
    val currentStock: Double,
    val capacity: Double,
    val unit: String,
    val lastUpdatedMillis: Long
)
