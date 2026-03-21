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

        val noteOnlyClients = allClients.filter {
            it.subscribedSteps.isEmpty() && !it.hasGrub && it.notes.isNotBlank()
        }

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
                unassignedCount = eligibleSuggestions.size,
                noteOnlyClients = noteOnlyClients
            )
        }

        val targetPerDay = ceil(eligibleSuggestions.size.toDouble() / workDays.size.toDouble()).toInt().coerceAtLeast(1)
        val softCap = minOf(targetPerDay + 2, AppConfig.WeeklyPlanner.MAX_CLIENTS_PER_DAY)

        // Track accumulated sq ft per day for the sq-ft hard cap.
        val sqFtPerDay = mutableMapOf<PlannedDayBuilder, Int>()

        val sortedClients = eligibleSuggestions.sortedWith(
            compareByDescending<com.routeme.app.ClientSuggestion> { it.daysSinceLast ?: Int.MAX_VALUE }
                .thenByDescending { it.daysSinceLast == null }
        )

        var assignedCount = 0

        for (suggestion in sortedClients) {
            val clientLat = suggestion.client.latitude
            val clientLng = suggestion.client.longitude
            val clientSqFt = suggestion.client.lawnSizeSqFt.takeIf { it > 0 }

            val dayFitness = workDays.map { day ->
                val (fitnessScore, reason) = scoreClientDayFitness(
                    client = suggestion.client,
                    property = suggestion.client.property,
                    forecast = day.forecast,
                    dayOfWeek = day.dayOfWeek,
                    eligibleSteps = suggestion.eligibleSteps
                )
                // Geographic affinity: bonus when this day already has nearby clients.
                // Score += up to GEO_AFFINITY_MAX_BONUS scaled by proximity of the closest
                // already-assigned geocoded client on this day.
                val geoBonus = if (clientLat != null && clientLng != null && day.clients.isNotEmpty()) {
                    val minDist = day.clients
                        .mapNotNull { pc ->
                            val pcLat = pc.client.latitude ?: return@mapNotNull null
                            val pcLng = pc.client.longitude ?: return@mapNotNull null
                            routingEngine.distanceMilesBetween(clientLat, clientLng, pcLat, pcLng)
                        }
                        .minOrNull()
                    if (minDist != null && minDist < AppConfig.WeeklyPlanner.GEO_AFFINITY_RADIUS_MILES) {
                        val fraction = 1.0 - (minDist / AppConfig.WeeklyPlanner.GEO_AFFINITY_RADIUS_MILES)
                        (fraction * AppConfig.WeeklyPlanner.GEO_AFFINITY_MAX_BONUS).toInt()
                    } else 0
                } else 0

                DayFitness(day, fitnessScore + geoBonus, reason)
            }

            val best = dayFitness
                .filter { fitness ->
                    val d = fitness.day
                    if (d.clients.size >= AppConfig.WeeklyPlanner.MAX_CLIENTS_PER_DAY) return@filter false
                    if (d.clients.size >= softCap) return@filter false
                    if (clientSqFt != null) {
                        val dayTotal = sqFtPerDay.getOrDefault(d, 0)
                        if (dayTotal + clientSqFt > AppConfig.WeeklyPlanner.MAX_SQFT_PER_DAY) return@filter false
                    }
                    true
                }
                .maxByOrNull { it.score }
                ?: dayFitness
                    .filter { it.day.clients.size < AppConfig.WeeklyPlanner.MAX_CLIENTS_PER_DAY }
                    .minByOrNull { it.day.clients.size }

            if (best != null) {
                best.day.clients += PlannedClient(
                    client = suggestion.client,
                    fitnessScore = best.score,
                    fitnessLabel = fitnessScoreToLabel(best.score).displayText,
                    primaryReason = best.reason,
                    eligibleSteps = suggestion.eligibleSteps,
                    daysOverdue = suggestion.daysSinceLast
                )
                if (clientSqFt != null) {
                    sqFtPerDay[best.day] = sqFtPerDay.getOrDefault(best.day, 0) + clientSqFt
                }
                assignedCount++
            }
        }

        // Sort each work day's clients into nearest-neighbour route order starting from the shop.
        dayBuilders.forEach { day ->
            if (day.clients.size > 1) {
                day.clients.replaceAll { it }  // no-op to keep mutable
                val ordered = nearestNeighbourOrder(day.clients, lat, lng)
                day.clients.clear()
                day.clients.addAll(ordered)
            }
        }

        WeekPlan(
            days = dayBuilders.map { it.build() },
            generatedAtMillis = nowProvider(),
            totalClients = eligibleSuggestions.size,
            unassignedCount = (eligibleSuggestions.size - assignedCount).coerceAtLeast(0),
            noteOnlyClients = noteOnlyClients
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
        dayOfWeek: Int,
        eligibleSteps: Set<ServiceType> = emptySet()
    ): Pair<Int, String> {
        if (property == null) {
            return 50 to "No property data — neutral fit"
        }

        var score = 50
        val reasons = mutableListOf<String>()

        // Liquid steps (2 & 5) must not be rained on — penalise if rain is likely.
        // Exception: when a granular step (1, 3, 4, 6) is selected alongside a liquid step,
        // granular rain logic takes precedence and the liquid penalty is suppressed.
        val hasLiquidStep = eligibleSteps.any { it.stepNumber in AppConfig.WeeklyPlanner.LIQUID_STEPS }
        val hasGranularStep = eligibleSteps.any { it.stepNumber in AppConfig.WeeklyPlanner.GRANULAR_STEPS }
        if (hasLiquidStep && !hasGranularStep &&
            forecast.precipProbabilityPct >= AppConfig.WeeklyPlanner.LIQUID_STEP_RAIN_PENALTY_PCT
        ) {
            score -= AppConfig.WeeklyPlanner.LIQUID_STEP_RAIN_PENALTY
            val stepLabels = eligibleSteps
                .filter { it.stepNumber in AppConfig.WeeklyPlanner.LIQUID_STEPS }
                .joinToString("/") { "Step ${it.stepNumber}" }
            reasons += "$stepLabels liquid — rain risk (${forecast.precipProbabilityPct}%)"
        }

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
            // daysSinceMow: how many days have passed since the last mow (0 = mow day, 1 = day after, …6 = day before next mow)
            val daysSinceMow = ((dayOfWeek - client.mowDayOfWeek + 7) % 7)
            val rainExpected = forecast.precipProbabilityPct >= AppConfig.WeeklyPlanner.PRECIP_MODERATE_PCT
            when (daysSinceMow) {
                0 -> {
                    score -= 20
                    reasons += "Mow day — freshly cut"
                }
                1 -> {
                    // Day after mow: grass still very short
                    score -= 10
                    reasons += "Day after mow — grass short"
                }
                2 -> {
                    score += 5
                    reasons += "2 days after mow — growing"
                }
                3 -> {
                    score += 10
                    reasons += "3 days after mow — good growth"
                }
                4 -> {
                    score += 15
                    reasons += "4 days after mow — ideal"
                }
                5 -> {
                    score += 20
                    reasons += "5 days after mow — ideal"
                }
                6 -> {
                    // Day before next mow: grass may be shaggy but treatment still worthwhile if rain is coming
                    if (!rainExpected) {
                        score -= 20
                        reasons += "Day before mow — treatment may be mown off soon"
                    } else {
                        reasons += "Day before mow — rain expected, treatment OK"
                    }
                }
            }
        }

        return score.coerceIn(0, 100) to (reasons.firstOrNull() ?: "Standard fit")
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

    /**
     * Greedy nearest-neighbour ordering starting from [startLat]/[startLng] (typically the shop).
     * Clients without coordinates are appended at the end in their original order.
     */
    private fun nearestNeighbourOrder(
        clients: List<PlannedClient>,
        startLat: Double,
        startLng: Double
    ): List<PlannedClient> {
        val geocoded = clients.filter { it.client.latitude != null && it.client.longitude != null }.toMutableList()
        val ungeocoded = clients.filter { it.client.latitude == null || it.client.longitude == null }

        val result = mutableListOf<PlannedClient>()
        var curLat = startLat
        var curLng = startLng

        while (geocoded.isNotEmpty()) {
            val nearest = geocoded.minByOrNull { pc ->
                routingEngine.distanceMilesBetween(curLat, curLng, pc.client.latitude!!, pc.client.longitude!!)
            }!!
            result += nearest
            geocoded -= nearest
            curLat = nearest.client.latitude!!
            curLng = nearest.client.longitude!!
        }

        result.addAll(ungeocoded)
        return result
    }
}
