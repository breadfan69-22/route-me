package com.routeme.app.model

import com.routeme.app.Client
import com.routeme.app.ServiceType
import org.json.JSONArray
import org.json.JSONObject

data class WeekPlan(
    val days: List<PlannedDay>,
    val generatedAtMillis: Long,
    val totalClients: Int,
    val unassignedCount: Int,
    val noteOnlyClients: List<Client> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("generatedAtMillis", generatedAtMillis)
        put("totalClients", totalClients)
        put("unassignedCount", unassignedCount)
        put("days", JSONArray().apply { days.forEach { put(it.toJson()) } })
        put("noteOnlyClients", JSONArray().apply { noteOnlyClients.forEach { put(clientToJson(it)) } })
    }

    companion object {
        fun fromJson(json: JSONObject): WeekPlan {
            val daysArr = json.getJSONArray("days")
            val days = (0 until daysArr.length()).map { PlannedDay.fromJson(daysArr.getJSONObject(it)) }
            val noteArr = json.optJSONArray("noteOnlyClients") ?: JSONArray()
            val noteClients = (0 until noteArr.length()).map { clientFromJson(noteArr.getJSONObject(it)) }
            return WeekPlan(
                days = days,
                generatedAtMillis = json.getLong("generatedAtMillis"),
                totalClients = json.getInt("totalClients"),
                unassignedCount = json.getInt("unassignedCount"),
                noteOnlyClients = noteClients
            )
        }
    }
}

data class PlannedDay(
    val dateMillis: Long,
    val dayOfWeek: Int,
    val dayName: String,
    val forecast: ForecastDay?,
    val dayScore: Int,
    val dayScoreLabel: String,
    val clients: List<PlannedClient>,
    val isWorkDay: Boolean,
    val anchorLat: Double? = null,
    val anchorLng: Double? = null,
    val anchorLabel: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("dateMillis", dateMillis)
        put("dayOfWeek", dayOfWeek)
        put("dayName", dayName)
        put("dayScore", dayScore)
        put("dayScoreLabel", dayScoreLabel)
        put("isWorkDay", isWorkDay)
        if (forecast != null) put("forecast", forecastToJson(forecast))
        put("clients", JSONArray().apply { clients.forEach { put(it.toJson()) } })
        if (anchorLat != null) put("anchorLat", anchorLat)
        if (anchorLng != null) put("anchorLng", anchorLng)
        if (anchorLabel != null) put("anchorLabel", anchorLabel)
    }

    companion object {
        fun fromJson(json: JSONObject): PlannedDay = PlannedDay(
            dateMillis = json.getLong("dateMillis"),
            dayOfWeek = json.getInt("dayOfWeek"),
            dayName = json.getString("dayName"),
            forecast = if (json.has("forecast")) forecastFromJson(json.getJSONObject("forecast")) else null,
            dayScore = json.getInt("dayScore"),
            dayScoreLabel = json.getString("dayScoreLabel"),
            clients = json.getJSONArray("clients").let { arr ->
                (0 until arr.length()).map { PlannedClient.fromJson(arr.getJSONObject(it)) }
            },
            isWorkDay = json.getBoolean("isWorkDay"),
            anchorLat = if (json.has("anchorLat")) json.getDouble("anchorLat") else null,
            anchorLng = if (json.has("anchorLng")) json.getDouble("anchorLng") else null,
            anchorLabel = if (json.has("anchorLabel")) json.getString("anchorLabel") else null
        )
    }
}

data class PlannedClient(
    val client: Client,
    val fitnessScore: Int,
    val fitnessLabel: String,
    val primaryReason: String,
    val eligibleSteps: Set<ServiceType>,
    val daysOverdue: Int?,
    val manuallyPlaced: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("client", clientToJson(client))
        put("fitnessScore", fitnessScore)
        put("fitnessLabel", fitnessLabel)
        put("primaryReason", primaryReason)
        put("eligibleSteps", JSONArray().apply { eligibleSteps.forEach { put(it.name) } })
        if (daysOverdue != null) put("daysOverdue", daysOverdue)
        put("manuallyPlaced", manuallyPlaced)
    }

    companion object {
        fun fromJson(json: JSONObject): PlannedClient = PlannedClient(
            client = clientFromJson(json.getJSONObject("client")),
            fitnessScore = json.getInt("fitnessScore"),
            fitnessLabel = json.getString("fitnessLabel"),
            primaryReason = json.getString("primaryReason"),
            eligibleSteps = json.getJSONArray("eligibleSteps").let { arr ->
                (0 until arr.length()).mapNotNull { runCatching { ServiceType.valueOf(arr.getString(it)) }.getOrNull() }.toSet()
            },
            daysOverdue = if (json.has("daysOverdue")) json.getInt("daysOverdue") else null,
            manuallyPlaced = json.optBoolean("manuallyPlaced", false)
        )
    }
}

enum class FitnessLabel(val displayText: String) {
    GREAT("Great"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
    NEUTRAL("—")
}

// ── JSON helpers for nested types ──

internal fun clientToJson(c: Client): JSONObject = JSONObject().apply {
    put("id", c.id)
    put("name", c.name)
    put("address", c.address)
    put("zone", c.zone)
    put("notes", c.notes)
    put("subscribedSteps", JSONArray().apply { c.subscribedSteps.forEach { put(it) } })
    put("hasGrub", c.hasGrub)
    put("mowDayOfWeek", c.mowDayOfWeek)
    put("lawnSizeSqFt", c.lawnSizeSqFt)
    put("sunShade", c.sunShade)
    put("terrain", c.terrain)
    put("windExposure", c.windExposure)
    put("cuSpringPending", c.cuSpringPending)
    put("cuFallPending", c.cuFallPending)
    if (c.latitude != null) put("latitude", c.latitude)
    if (c.longitude != null) put("longitude", c.longitude)
}

internal fun clientFromJson(j: JSONObject): Client {
    val steps = j.optJSONArray("subscribedSteps")
    val stepSet = if (steps != null) (0 until steps.length()).map { steps.getInt(it) }.toSet() else emptySet()
    return Client(
        id = j.getString("id"),
        name = j.getString("name"),
        address = j.optString("address", ""),
        zone = j.optString("zone", ""),
        notes = j.optString("notes", ""),
        subscribedSteps = stepSet,
        hasGrub = j.optBoolean("hasGrub", false),
        mowDayOfWeek = j.optInt("mowDayOfWeek", 0),
        lawnSizeSqFt = j.optInt("lawnSizeSqFt", 0),
        sunShade = j.optString("sunShade", ""),
        terrain = j.optString("terrain", ""),
        windExposure = j.optString("windExposure", ""),
        cuSpringPending = j.optBoolean("cuSpringPending", false),
        cuFallPending = j.optBoolean("cuFallPending", false),
        latitude = if (j.has("latitude")) j.getDouble("latitude") else null,
        longitude = if (j.has("longitude")) j.getDouble("longitude") else null
    )
}

internal fun forecastToJson(f: ForecastDay): JSONObject = JSONObject().apply {
    put("dateMillis", f.dateMillis)
    put("highTempF", f.highTempF)
    put("lowTempF", f.lowTempF)
    put("windSpeedMph", f.windSpeedMph)
    if (f.windGustMph != null) put("windGustMph", f.windGustMph)
    put("precipProbabilityPct", f.precipProbabilityPct)
    put("shortForecast", f.shortForecast)
    put("detailedForecast", f.detailedForecast)
}

internal fun forecastFromJson(j: JSONObject): ForecastDay = ForecastDay(
    dateMillis = j.getLong("dateMillis"),
    highTempF = j.getInt("highTempF"),
    lowTempF = j.getInt("lowTempF"),
    windSpeedMph = j.getInt("windSpeedMph"),
    windGustMph = if (j.has("windGustMph")) j.getInt("windGustMph") else null,
    precipProbabilityPct = j.getInt("precipProbabilityPct"),
    shortForecast = j.getString("shortForecast"),
    detailedForecast = j.optString("detailedForecast", "")
)
