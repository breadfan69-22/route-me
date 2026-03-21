package com.routeme.app.domain

import com.routeme.app.Client
import com.routeme.app.ClientProperty
import com.routeme.app.RouteDirection
import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.ServiceType
import com.routeme.app.SunShade
import com.routeme.app.WindExposure
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WeatherRepository
import com.routeme.app.model.FitnessLabel
import com.routeme.app.model.ForecastDay
import com.routeme.app.model.PlannedClient
import com.routeme.app.model.PlannedDay
import com.routeme.app.model.WeekPlan
import com.routeme.app.util.AppConfig
import com.routeme.app.util.DateUtils
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

class WeeklyPlannerUseCase(
    private val weatherRepository: WeatherRepository,
    private val clientRepository: ClientRepository,
    private val routingEngine: RoutingEngine,
    private val preferencesRepository: PreferencesRepository,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    suspend fun generateWeekPlan(
        lat: Double = SHOP_LAT,
        lng: Double = SHOP_LNG,
        serviceTypes: Set<ServiceType> = ServiceType.entries.toSet(),
        minDays: Int = 7
    ): WeekPlan = withContext(Dispatchers.Default) {
        val forecastDays = weatherRepository.getForecastDays(dayCount = 7, lat = lat, lng = lng)
        val allClients = clientRepository.loadAllClients()
        val propertyMap = allClients.mapNotNull { client -> client.property?.let { client.id to it } }.toMap()

        val eligibleSuggestions = routingEngine.rankClients(
            clients = allClients,
            serviceTypes = serviceTypes,
            minDays = minDays,
            lastLocation = null,
            cuOverrideEnabled = false,
            routeDirection = RouteDirection.OUTWARD,
            weather = null,
            recentPrecipInches = null,
            propertyMap = propertyMap
        )

        val dayBuilders = forecastDays.map { forecast ->
            val dayOfWeek = dayOfWeekForDate(forecast.dateMillis)
            val score = scoreDayWeather(forecast)
            PlannedDayBuilder(
                dateMillis = forecast.dateMillis,
                dayOfWeek = dayOfWeek,
                dayName = DateUtils.dayName(dayOfWeek),
                forecast = forecast,
                dayScore = score,
                dayScoreLabel = dayScoreToLabel(score)
            )
        }.toMutableList()

        val workDays = dayBuilders.filter { shouldIncludeAsWorkDay(it) }
        workDays.forEach { it.isWorkDay = true }

        if (workDays.isEmpty()) {
            return@withContext WeekPlan(
                days = dayBuilders.map { it.build() },
                generatedAtMillis = nowProvider(),
                totalClients = eligibleSuggestions.size,
                unassignedCount = eligibleSuggestions.size
            )
        }

        val targetPerDay = ceil(eligibleSuggestions.size.toDouble() / workDays.size.toDouble()).toInt().coerceAtLeast(1)
        val softCap = targetPerDay + 2

        val sortedClients = eligibleSuggestions.sortedWith(
            compareByDescending<com.routeme.app.ClientSuggestion> { it.daysSinceLast ?: Int.MAX_VALUE }
                .thenByDescending { it.daysSinceLast == null }
        )

        var assignedCount = 0

        for (suggestion in sortedClients) {
            val dayFitness = workDays.map { day ->
                val (fitnessScore, reason) = scoreClientDayFitness(
                    client = suggestion.client,
                    property = suggestion.client.property,
                    forecast = day.forecast,
                    dayOfWeek = day.dayOfWeek
                )
                DayFitness(day, fitnessScore, reason)
            }

            val best = dayFitness
                .filter { it.day.clients.size < softCap }
                .maxByOrNull { it.score }
                ?: dayFitness.minByOrNull { it.day.clients.size }

            if (best != null) {
                best.day.clients += PlannedClient(
                    client = suggestion.client,
                    fitnessScore = best.score,
                    fitnessLabel = fitnessScoreToLabel(best.score).displayText,
                    primaryReason = best.reason,
                    eligibleSteps = suggestion.eligibleSteps,
                    daysOverdue = suggestion.daysSinceLast
                )
                assignedCount++
            }
        }

        dayBuilders.forEach { day ->
            day.clients.sortByDescending { client -> client.fitnessScore }
        }

        WeekPlan(
            days = dayBuilders.map { it.build() },
            generatedAtMillis = nowProvider(),
            totalClients = eligibleSuggestions.size,
            unassignedCount = (eligibleSuggestions.size - assignedCount).coerceAtLeast(0)
        )
    }

    private data class PlannedDayBuilder(
        val dateMillis: Long,
        val dayOfWeek: Int,
        val dayName: String,
        val forecast: ForecastDay,
        val dayScore: Int,
        val dayScoreLabel: String,
        var isWorkDay: Boolean = false,
        val clients: MutableList<PlannedClient> = mutableListOf()
    ) {
        fun build(): PlannedDay {
            return PlannedDay(
                dateMillis = dateMillis,
                dayOfWeek = dayOfWeek,
                dayName = dayName,
                forecast = forecast,
                dayScore = dayScore,
                dayScoreLabel = dayScoreLabel,
                clients = clients.toList(),
                isWorkDay = isWorkDay
            )
        }
    }

    private data class DayFitness(
        val day: PlannedDayBuilder,
        val score: Int,
        val reason: String
    )

    private fun shouldIncludeAsWorkDay(day: PlannedDayBuilder): Boolean {
        return when (day.dayOfWeek) {
            Calendar.SUNDAY -> !AppConfig.WeeklyPlanner.SUNDAY_EXCLUDED
            Calendar.SATURDAY -> day.dayScore >= AppConfig.WeeklyPlanner.SATURDAY_SCORE_THRESHOLD
            else -> day.dayScore >= AppConfig.WeeklyPlanner.WORKDAY_SEVERE_WEATHER_MIN_SCORE
        }
    }

    private fun scoreDayWeather(forecast: ForecastDay): Int {
        var score = 100
        val avgTemp = (forecast.highTempF + forecast.lowTempF) / 2

        when {
            avgTemp < AppConfig.WeeklyPlanner.IDEAL_TEMP_LOW_F -> {
                score -= (AppConfig.WeeklyPlanner.IDEAL_TEMP_LOW_F - avgTemp) * AppConfig.WeeklyPlanner.TEMP_PENALTY_PER_DEGREE
            }
            avgTemp > AppConfig.WeeklyPlanner.IDEAL_TEMP_HIGH_F -> {
                score -= (avgTemp - AppConfig.WeeklyPlanner.IDEAL_TEMP_HIGH_F) * AppConfig.WeeklyPlanner.TEMP_PENALTY_PER_DEGREE
            }
        }

        val wind = forecast.windGustMph ?: forecast.windSpeedMph
        score += when {
            wind <= AppConfig.WeeklyPlanner.WIND_CALM_MPH -> AppConfig.WeeklyPlanner.WIND_CALM_SCORE
            wind <= AppConfig.WeeklyPlanner.WIND_MODERATE_MPH -> AppConfig.WeeklyPlanner.WIND_MODERATE_PENALTY
            wind <= AppConfig.WeeklyPlanner.WIND_HIGH_MPH -> AppConfig.WeeklyPlanner.WIND_HIGH_PENALTY
            else -> AppConfig.WeeklyPlanner.WIND_SEVERE_PENALTY
        }

        score += when {
            forecast.precipProbabilityPct < AppConfig.WeeklyPlanner.PRECIP_LOW_PCT -> AppConfig.WeeklyPlanner.PRECIP_LOW_SCORE
            forecast.precipProbabilityPct < AppConfig.WeeklyPlanner.PRECIP_MODERATE_PCT -> AppConfig.WeeklyPlanner.PRECIP_MODERATE_PENALTY
            forecast.precipProbabilityPct < AppConfig.WeeklyPlanner.PRECIP_HIGH_PCT -> AppConfig.WeeklyPlanner.PRECIP_HIGH_PENALTY
            else -> AppConfig.WeeklyPlanner.PRECIP_SEVERE_PENALTY
        }

        val severeKeywords = listOf("thunder", "ice", "snow", "blizzard", "tornado", "hurricane")
        if (severeKeywords.any {
                forecast.shortForecast.contains(it, ignoreCase = true) ||
                    forecast.detailedForecast.contains(it, ignoreCase = true)
            }) {
            score = minOf(score, AppConfig.WeeklyPlanner.SEVERE_WEATHER_FLOOR)
        }

        return score.coerceIn(0, 100)
    }

    private fun dayScoreToLabel(score: Int): String = when {
        score >= AppConfig.WeeklyPlanner.FITNESS_GREAT_THRESHOLD -> "Great"
        score >= AppConfig.WeeklyPlanner.FITNESS_GOOD_THRESHOLD -> "Good"
        score >= AppConfig.WeeklyPlanner.FITNESS_FAIR_THRESHOLD -> "Fair"
        else -> "Poor"
    }

    private fun scoreClientDayFitness(
        client: Client,
        property: ClientProperty?,
        forecast: ForecastDay,
        dayOfWeek: Int
    ): Pair<Int, String> {
        if (property == null) {
            return 50 to "No property data — neutral fit"
        }

        var score = 50
        val reasons = mutableListOf<String>()

        val wind = forecast.windGustMph ?: forecast.windSpeedMph
        val isWindy = wind >= AppConfig.Routing.WEATHER_WIND_THRESHOLD_MPH
        val isCalm = wind <= AppConfig.Routing.WEATHER_CALM_THRESHOLD_MPH
        val isHot = forecast.highTempF >= AppConfig.Routing.WEATHER_HOT_THRESHOLD_F
        val estimatedPrecip = if (forecast.precipProbabilityPct >= AppConfig.WeeklyPlanner.PRECIP_PROB_TO_INCHES_THRESHOLD_PCT) {
            AppConfig.WeeklyPlanner.PRECIP_PROB_ESTIMATED_INCHES
        } else {
            0.0
        }

        when (property.windExposure) {
            WindExposure.EXPOSED -> {
                if (isWindy) {
                    score -= 40
                    reasons += "Windy — exposed yard risky"
                } else if (isCalm && property.lawnSizeSqFt >= AppConfig.Routing.WEATHER_CALM_LARGE_LAWN_SQFT) {
                    score += 30
                    reasons += "Calm day for large exposed yard"
                } else if (isCalm) {
                    score += 20
                    reasons += "Calm day for exposed yard"
                }
            }
            WindExposure.SHELTERED -> {
                if (isWindy) {
                    score += 10
                    reasons += "Sheltered — protected from wind"
                }
            }
            else -> Unit
        }

        when (property.sunShade) {
            SunShade.FULL_SHADE,
            SunShade.PARTIAL_SHADE -> {
                if (isHot) {
                    score += 25
                    reasons += "Shaded — cooler in heat"
                }
            }
            SunShade.FULL_SUN -> {
                if (isHot) {
                    score -= 15
                    reasons += "Full sun — hot work"
                }
            }
            else -> Unit
        }

        if (property.hasSteepSlopes && estimatedPrecip >= AppConfig.Routing.WEATHER_SLOPE_RAIN_THRESHOLD_INCHES) {
            score -= 35
            reasons += "Slopes slippery after rain"
        }

        if (property.hasIrrigation &&
            forecast.highTempF >= AppConfig.Routing.WEATHER_DRY_HOT_THRESHOLD_F &&
            forecast.precipProbabilityPct < AppConfig.WeeklyPlanner.PRECIP_LOW_PCT
        ) {
            score += 10
            reasons += "Irrigated lawn — healthy in heat"
        }

        if (client.mowDayOfWeek in Calendar.SUNDAY..Calendar.SATURDAY) {
            val dayDist = circularDayDistance(dayOfWeek, client.mowDayOfWeek)
            when (dayDist) {
                0 -> {
                    score -= 20
                    reasons += "Mow day — may be freshly cut"
                }
                1 -> score -= 5
                2, 3 -> score += 5
            }
        }

        return score.coerceIn(0, 100) to (reasons.firstOrNull() ?: "Standard fit")
    }

    private fun circularDayDistance(dayA: Int, dayB: Int): Int {
        val direct = abs(dayA - dayB)
        return minOf(direct, 7 - direct)
    }

    private fun fitnessScoreToLabel(score: Int): FitnessLabel = when {
        score >= AppConfig.WeeklyPlanner.FITNESS_GREAT_THRESHOLD -> FitnessLabel.GREAT
        score >= AppConfig.WeeklyPlanner.FITNESS_GOOD_THRESHOLD -> FitnessLabel.GOOD
        score >= AppConfig.WeeklyPlanner.FITNESS_FAIR_THRESHOLD -> FitnessLabel.FAIR
        score > 0 -> FitnessLabel.POOR
        else -> FitnessLabel.NEUTRAL
    }

    private fun dayOfWeekForDate(dateMillis: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = dateMillis
        return calendar.get(Calendar.DAY_OF_WEEK)
    }
}
