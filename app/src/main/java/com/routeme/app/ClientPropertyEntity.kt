package com.routeme.app

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "client_properties",
    foreignKeys = [
        ForeignKey(
            entity = ClientEntity::class,
            parentColumns = ["id"],
            childColumns = ["clientId"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION
        )
    ]
)
data class ClientPropertyEntity(
    @PrimaryKey val clientId: String,
    val lawnSizeSqFt: Int,
    val sunShade: String,
    val windExposure: String,
    val hasSteepSlopes: Boolean,
    val hasIrrigation: Boolean,
    val propertyNotes: String,
    val updatedAtMillis: Long
)
