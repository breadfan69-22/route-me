package com.routeme.app

data class ClientProperty(
    val clientId: String,
    val lawnSizeSqFt: Int = 0,
    val sunShade: SunShade = SunShade.UNKNOWN,
    val windExposure: WindExposure = WindExposure.UNKNOWN,
    val hasSteepSlopes: Boolean = false,
    val hasIrrigation: Boolean = false,
    val propertyNotes: String = "",
    val updatedAtMillis: Long = 0L
)
