package com.routeme.app

import androidx.room.Embedded
import androidx.room.Relation

data class ClientWithRecords(
    @Embedded val client: ClientEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "clientId"
    )
    val records: List<ServiceRecordEntity>
)

data class DailyRecordRow(
    val clientId: String,
    val clientName: String,
    val serviceType: String,
    val arrivedAtMillis: Long?,
    val completedAtMillis: Long,
    val durationMinutes: Long,
    val notes: String
)
