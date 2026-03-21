package com.routeme.app.model

import com.routeme.app.Client
import com.routeme.app.ServiceType

data class WeekPlan(
    val days: List<PlannedDay>,
    val generatedAtMillis: Long,
    val totalClients: Int,
    val unassignedCount: Int
)

data class PlannedDay(
    val dateMillis: Long,
    val dayOfWeek: Int,
    val dayName: String,
    val forecast: ForecastDay?,
    val dayScore: Int,
    val dayScoreLabel: String,
    val clients: List<PlannedClient>,
    val isWorkDay: Boolean
)

data class PlannedClient(
    val client: Client,
    val fitnessScore: Int,
    val fitnessLabel: String,
    val primaryReason: String,
    val eligibleSteps: Set<ServiceType>,
    val daysOverdue: Int?
)

enum class FitnessLabel(val displayText: String) {
    GREAT("Great"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
    NEUTRAL("—")
}
