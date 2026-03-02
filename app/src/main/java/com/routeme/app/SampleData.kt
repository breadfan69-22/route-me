package com.routeme.app

import java.util.Calendar

object SampleData {
    fun clients(): MutableList<Client> {
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L

        return mutableListOf(
            Client(
                id = "C001",
                name = "Anderson Family",
                address = "101 Oak Meadow Ln, Kalamazoo, MI",
                zone = "KAL",
                notes = "",
                subscribedSteps = setOf(1, 2, 3, 4, 5, 6),
                hasGrub = false,
                mowDayOfWeek = Calendar.WEDNESDAY,
                lawnSizeSqFt = 0,
                sunShade = "",
                terrain = "",
                windExposure = "",
                latitude = 42.2917,
                longitude = -85.5872,
                records = mutableListOf(
                    ServiceRecord(serviceType = ServiceType.ROUND_1, completedAtMillis = now - 26 * day, durationMinutes = 24, lat = 42.2917, lng = -85.5872)
                )
            ),
            Client(
                id = "C002",
                name = "Barker, Dave",
                address = "255 Cedar Point Rd, Mattawan, MI",
                zone = "N09",
                notes = "",
                subscribedSteps = setOf(1, 3, 5),
                hasGrub = true,
                mowDayOfWeek = Calendar.FRIDAY,
                lawnSizeSqFt = 0,
                sunShade = "",
                terrain = "",
                windExposure = "",
                latitude = 42.2103,
                longitude = -85.7842,
                records = mutableListOf(
                    ServiceRecord(serviceType = ServiceType.ROUND_1, completedAtMillis = now - 33 * day, durationMinutes = 30, lat = 42.2103, lng = -85.7842),
                    ServiceRecord(serviceType = ServiceType.ROUND_3, completedAtMillis = now - 92 * day, durationMinutes = 32, lat = 42.2103, lng = -85.7842)
                )
            ),
            Client(
                id = "C003",
                name = "Carpenter, Beatrice",
                address = "9 Pine Hollow Dr, Richland, MI",
                zone = "RIC",
                notes = "*Organic fert",
                subscribedSteps = setOf(2, 4, 6),
                hasGrub = false,
                mowDayOfWeek = Calendar.THURSDAY,
                lawnSizeSqFt = 0,
                sunShade = "",
                terrain = "",
                windExposure = "",
                latitude = 42.3742,
                longitude = -85.4568,
                records = mutableListOf(
                    ServiceRecord(serviceType = ServiceType.ROUND_2, completedAtMillis = now - 18 * day, durationMinutes = 20, lat = 42.3742, lng = -85.4568)
                )
            )
        )
    }
}
