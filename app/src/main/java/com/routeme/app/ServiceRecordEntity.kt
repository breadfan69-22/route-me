package com.routeme.app

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "service_records",
    foreignKeys = [ForeignKey(
        entity = ClientEntity::class,
        parentColumns = ["id"],
        childColumns = ["clientId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("clientId")]
)
data class ServiceRecordEntity(
    @PrimaryKey(autoGenerate = true) val recordId: Long = 0,
    val clientId: String,
    val serviceType: String,
    val arrivedAtMillis: Long? = null,
    val completedAtMillis: Long,
    val durationMinutes: Long,
    val lat: Double?,
    val lng: Double?,
    val notes: String = "",
    val amountUsed: Double? = null,
    val amountUsed2: Double? = null
)
