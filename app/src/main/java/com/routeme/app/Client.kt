package com.routeme.app

data class Client(
    val id: String,
    val name: String,
    val address: String,
    val zone: String,
    val notes: String,
    val subscribedSteps: Set<Int>,
    val hasGrub: Boolean,
    val mowDayOfWeek: Int,
    val lawnSizeSqFt: Int,
    val sunShade: String,
    val terrain: String,
    val windExposure: String,
    val irrigation: String = "",
    val cuSpringPending: Boolean = false,
    val cuFallPending: Boolean = false,
    val latitude: Double?,
    val longitude: Double?,
    val records: List<ServiceRecord> = emptyList(),
    val property: ClientProperty? = null
)
