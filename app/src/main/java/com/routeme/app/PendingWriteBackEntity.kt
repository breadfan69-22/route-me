package com.routeme.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_write_backs")
data class PendingWriteBackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientName: String,
    val column: String,
    val value: String,
    val createdAtMillis: Long,
    val retryCount: Int = 0
)
