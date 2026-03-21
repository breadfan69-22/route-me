package com.routeme.app

fun Client.toEntity(): ClientEntity = ClientEntity(
    id = id,
    name = name,
    address = address,
    zone = zone,
    notes = notes,
    subscribedSteps = subscribedSteps.sorted().joinToString(","),
    hasGrub = hasGrub,
    mowDayOfWeek = mowDayOfWeek,
    lawnSizeSqFt = lawnSizeSqFt,
    sunShade = sunShade,
    terrain = terrain,
    windExposure = windExposure,
    cuSpringPending = cuSpringPending,
    cuFallPending = cuFallPending,
    latitude = latitude,
    longitude = longitude
)

fun ServiceRecord.toEntity(clientId: String): ServiceRecordEntity = ServiceRecordEntity(
    clientId = clientId,
    serviceType = serviceType.name,
    arrivedAtMillis = arrivedAtMillis,
    completedAtMillis = completedAtMillis,
    durationMinutes = durationMinutes,
    lat = lat,
    lng = lng,
    notes = notes,
    amountUsed = amountUsed,
    amountUsed2 = amountUsed2
)

fun NonClientStopEntity.toDomain(): NonClientStop = NonClientStop(
    id = stopId,
    lat = lat,
    lng = lng,
    address = address,
    arrivedAtMillis = arrivedAtMillis,
    departedAtMillis = departedAtMillis,
    durationMinutes = durationMinutes,
    label = label
)

fun NonClientStop.toEntity(): NonClientStopEntity = NonClientStopEntity(
    stopId = id,
    lat = lat,
    lng = lng,
    address = address,
    arrivedAtMillis = arrivedAtMillis,
    departedAtMillis = departedAtMillis,
    durationMinutes = durationMinutes,
    label = label
)

fun ClientWithRecords.toDomain(): Client = Client(
    id = client.id,
    name = client.name,
    address = client.address,
    zone = client.zone,
    notes = client.notes,
    subscribedSteps = client.subscribedSteps
        .split(",")
        .filter { it.isNotBlank() }
        .mapNotNull { it.toIntOrNull() }
        .toSet(),
    hasGrub = client.hasGrub,
    mowDayOfWeek = client.mowDayOfWeek,
    lawnSizeSqFt = client.lawnSizeSqFt,
    sunShade = client.sunShade,
    terrain = client.terrain,
    windExposure = client.windExposure,
    cuSpringPending = client.cuSpringPending,
    cuFallPending = client.cuFallPending,
    latitude = client.latitude,
    longitude = client.longitude,
    records = records.map { rec ->
        ServiceRecord(
            serviceType = ServiceType.valueOf(rec.serviceType),
            arrivedAtMillis = rec.arrivedAtMillis,
            completedAtMillis = rec.completedAtMillis,
            durationMinutes = rec.durationMinutes,
            lat = rec.lat,
            lng = rec.lng,
            notes = rec.notes,
            amountUsed = rec.amountUsed,
            amountUsed2 = rec.amountUsed2
        )
    }
)
