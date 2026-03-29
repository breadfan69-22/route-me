package com.routeme.app.domain

import com.routeme.app.Client
import com.routeme.app.ClientProperty
import com.routeme.app.ClientSuggestion
import com.routeme.app.DEFAULT_SQFT_ESTIMATE
import com.routeme.app.ProductType
import com.routeme.app.RouteDirection
import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.ServiceType
import com.routeme.app.SunShade
import com.routeme.app.WindExposure
import com.routeme.app.estimateGranularConsumptionBags
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WeatherRepository
import com.routeme.app.model.FitnessLabel
import com.routeme.app.model.ForecastDay
import com.routeme.app.model.PlannedClient
import com.routeme.app.model.PlannedDay
import com.routeme.app.model.RecentWeatherSignal
import com.routeme.app.model.WeekPlan
import com.routeme.app.util.AppConfig
import com.routeme.app.util.DateUtils
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

class WeeklyPlannerUseCase(
    private val weatherRepository: WeatherRepository,
    private val clientRepository: ClientRepository,
    private val routingEngine: RoutingEngine,
    private val preferencesRepository: PreferencesRepository,
    private val truckInventoryUseCase: TruckInventoryUseCase? = null,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    private data class GranularInventoryContext(
        val startingGranularBags: Double,
        val granularCapacity: Double
    )

    suspend fun generateWeekPlan(
        lat: Double = SHOP_LAT,
        lng: Double = SHOP_LNG,
        serviceTypes: Set<ServiceType> = ServiceType.entries.toSet(),
        minDays: Int = 7,
        dayAnchors: Map<Int, DayAnchor> = emptyMap()
    ): WeekPlan {
        val inventoryContext = loadGranularInventoryContext()

        return withContext(Dispatchers.Default) {
        val forecastDays = weatherRepository.getForecastDays(dayCount = 7, lat = lat, lng = lng)
        val allClients = clientRepository.loadAllClients()
        val propertyMap = allClients.mapNotNull { client -> client.property?.let { client.id to it } }.toMap()
        val shopWeatherSignal = runCatching {
            weatherRepository.getRecentWeatherSignal(SHOP_LAT, SHOP_LNG)
        }.getOrNull()

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
            propertyMap = propertyMap,
            recentWeatherByClientId = emptyMap()
        )

        // Sort by urgency (most overdue first) — tiebreaker only, not primary driver.
        val sortedClients = eligibleSuggestions.sortedWith(
            compareByDescending<com.routeme.app.ClientSuggestion> { it.daysSinceLast ?: Int.MAX_VALUE }
                .thenByDescending { it.daysSinceLast == null }
        )

        val initialAssignment = assignClientsToDays(
            forecastDays = forecastDays,
            dayAnchors = dayAnchors,
            sortedClients = sortedClients,
            shopWeatherSignal = shopWeatherSignal,
            lat = lat,
            lng = lng,
            refillDayOfWeek = emptySet()
        )

        val predictedRefillDayOfWeek = predictRefillDayOfWeek(
            dayBuilders = initialAssignment.dayBuilders,
            startingGranularBags = inventoryContext.startingGranularBags,
            capacityBags = inventoryContext.granularCapacity
        )

        val finalAssignment = assignClientsToDays(
            forecastDays = forecastDays,
            dayAnchors = dayAnchors,
            sortedClients = sortedClients,
            shopWeatherSignal = shopWeatherSignal,
            lat = lat,
            lng = lng,
            refillDayOfWeek = predictedRefillDayOfWeek
        )

        applyInventoryProjection(
            dayBuilders = finalAssignment.dayBuilders,
            startingGranularBags = inventoryContext.startingGranularBags,
            capacityBags = inventoryContext.granularCapacity
        )

        WeekPlan(
            days = finalAssignment.dayBuilders.map { it.build() },
            generatedAtMillis = nowProvider(),
            totalClients = eligibleSuggestions.size,
            unassignedCount = (eligibleSuggestions.size - finalAssignment.assignedCount).coerceAtLeast(0),
            noteOnlyClients = noteOnlyClients
        )
        }
    }

    suspend fun refreshInventoryProjection(plan: WeekPlan): WeekPlan {
        val inventoryContext = loadGranularInventoryContext()
        return withContext(Dispatchers.Default) {
            plan.copy(
                days = applyInventoryProjection(
                    days = plan.days,
                    startingGranularBags = inventoryContext.startingGranularBags,
                    capacityBags = inventoryContext.granularCapacity
                )
            )
        }
    }

    private suspend fun loadGranularInventoryContext(): GranularInventoryContext {
        val inventorySnapshot = runCatching { truckInventoryUseCase?.loadInventory() }.getOrNull()
        val granularCapacity = inventorySnapshot
            ?.get(ProductType.GRANULAR)
            ?.capacity
            ?.takeIf { it > 0.0 }
            ?: TruckInventoryUseCase.GRANULAR_CAPACITY_BAGS
        val startingGranularBags = inventorySnapshot
            ?.get(ProductType.GRANULAR)
            ?.currentStock
            ?.coerceIn(0.0, granularCapacity)
            ?: granularCapacity

        return GranularInventoryContext(
            startingGranularBags = startingGranularBags,
            granularCapacity = granularCapacity
        )
    }

    private data class AssignmentResult(
        val dayBuilders: MutableList<PlannedDayBuilder>,
        val assignedCount: Int
    )

    private fun assignClientsToDays(
        forecastDays: List<ForecastDay>,
        dayAnchors: Map<Int, DayAnchor>,
        sortedClients: List<ClientSuggestion>,
        shopWeatherSignal: RecentWeatherSignal?,
        lat: Double,
        lng: Double,
        refillDayOfWeek: Set<Int>
    ): AssignmentResult {
        val dayBuilders = forecastDays.map { forecast ->
            val dayOfWeek = dayOfWeekForDate(forecast.dateMillis)
            val score = scoreDayWeather(forecast)
            PlannedDayBuilder(
                dateMillis = forecast.dateMillis,
                dayOfWeek = dayOfWeek,
                dayName = DateUtils.dayName(dayOfWeek),
                forecast = forecast,
                dayScore = score,
                dayScoreLabel = dayScoreToLabel(score),
                anchorLat = dayAnchors[dayOfWeek]?.lat,
                anchorLng = dayAnchors[dayOfWeek]?.lng,
                anchorLabel = dayAnchors[dayOfWeek]?.label
            )
        }.toMutableList()

        val workDays = dayBuilders.filter { shouldIncludeAsWorkDay(it) }
        workDays.forEach { it.isWorkDay = true }
        if (workDays.isEmpty()) return AssignmentResult(dayBuilders, 0)

        val targetPerDay = ceil(sortedClients.size.toDouble() / workDays.size.toDouble()).toInt().coerceAtLeast(1)
        val softCap = minOf(targetPerDay + 2, AppConfig.WeeklyPlanner.MAX_CLIENTS_PER_DAY)
        val sqFtPerDay = mutableMapOf<PlannedDayBuilder, Int>()

        var assignedCount = 0
        for (suggestion in sortedClients) {
            val clientSqFt = suggestion.client.lawnSizeSqFt.takeIf { it > 0 }
            val clientMowDay = suggestion.client.mowDayOfWeek.takeIf { it in Calendar.SUNDAY..Calendar.SATURDAY }

            val dayFitness = workDays.map { day ->
                val (fitnessScore, reason) = scoreClientDayFitness(
                    client = suggestion.client,
                    property = suggestion.client.property,
                    forecast = day.forecast,
                    dayOfWeek = day.dayOfWeek,
                    eligibleSteps = suggestion.eligibleSteps,
                    recentWeatherSignal = shopWeatherSignal,
                    dayIsRefillDay = day.dayOfWeek in refillDayOfWeek
                )

                val daysSinceMow = if (clientMowDay != null) {
                    ((day.dayOfWeek - clientMowDay + 7) % 7)
                } else null
                val blockedByMow = daysSinceMow != null && (daysSinceMow in 0..1 || daysSinceMow == 6)

                val zoneDensityBonus = if (blockedByMow || suggestion.client.zone.isBlank()) {
                    0
                } else {
                    val sameZoneCount = day.clients.count { it.client.zone == suggestion.client.zone }
                    minOf(
                        sameZoneCount * AppConfig.WeeklyPlanner.ZONE_DENSITY_BONUS_PER_CLIENT,
                        AppConfig.WeeklyPlanner.ZONE_DENSITY_BONUS_MAX
                    )
                }

                val corridorBonus = if (blockedByMow || day.anchorLat == null || day.anchorLng == null) {
                    0
                } else {
                    corridorScore(suggestion.client, lat, lng, day.anchorLat, day.anchorLng)
                }

                val proximityBonus = if (blockedByMow) 0 else proximityScore(suggestion.client, day.clients)
                val geoBonus = maxOf(zoneDensityBonus, corridorBonus) + proximityBonus
                val finalScore = if (blockedByMow) Int.MIN_VALUE else fitnessScore + geoBonus
                DayFitness(day, finalScore, reason)
            }

            val eligible = dayFitness.filter { it.score != Int.MIN_VALUE }
            val best = eligible
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
                ?: eligible
                    .filter { it.day.clients.size < AppConfig.WeeklyPlanner.MAX_CLIENTS_PER_DAY }
                    .maxByOrNull { it.score }

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

        dayBuilders.forEach { day ->
            if (day.clients.size > 1) {
                day.clients.replaceAll { it }
                // Always use angular sweep + 2-opt for proper loop routing
                val ordered = nearestNeighbourOrder(day.clients, lat, lng)
                day.clients.clear()
                day.clients.addAll(ordered)
            }
        }

        return AssignmentResult(dayBuilders, assignedCount)
    }

    private fun predictRefillDayOfWeek(
        dayBuilders: List<PlannedDayBuilder>,
        startingGranularBags: Double,
        capacityBags: Double
    ): Set<Int> {
        if (capacityBags <= 0.0) return emptySet()

        val thresholdBags = capacityBags * AppConfig.WeeklyPlanner.REFILL_THRESHOLD_PCT
        var runningBags = startingGranularBags.coerceIn(0.0, capacityBags)
        val refillDays = mutableSetOf<Int>()

        dayBuilders.forEach { day ->
            if (!day.isWorkDay) return@forEach
            runningBags = (runningBags - estimateDayGranularConsumptionBags(day)).coerceAtLeast(0.0)
            if (runningBags < thresholdBags) {
                refillDays += day.dayOfWeek
                runningBags = capacityBags
            }
        }
        return refillDays
    }

    private fun applyInventoryProjection(
        dayBuilders: List<PlannedDayBuilder>,
        startingGranularBags: Double,
        capacityBags: Double
    ) {
        if (capacityBags <= 0.0) {
            dayBuilders.forEach {
                it.supplyStopNeeded = false
                it.projectedInventoryPct = 0
                it.supplyStopAfterIndex = null
            }
            return
        }

        val thresholdBags = capacityBags * AppConfig.WeeklyPlanner.REFILL_THRESHOLD_PCT
        var runningBags = startingGranularBags.coerceIn(0.0, capacityBags)

        dayBuilders.forEach { day ->
            day.supplyStopAfterIndex = null
            if (day.isWorkDay && day.clients.isNotEmpty()) {
                // Walk through clients in route order to find exact insertion point
                for ((index, plannedClient) in day.clients.withIndex()) {
                    val consumption = estimateClientGranularConsumptionBags(plannedClient)
                    runningBags = (runningBags - consumption).coerceAtLeast(0.0)

                    if (runningBags < thresholdBags && day.supplyStopAfterIndex == null) {
                        // Insert supply stop BEFORE this client runs us empty
                        // Use previous index so we refill before serving this client
                        day.supplyStopAfterIndex = (index - 1).coerceAtLeast(0)
                        day.supplyStopNeeded = true
                        runningBags = capacityBags  // Assume refill
                    }
                }
                // If no insertion point was set, day doesn't need a supply stop
                if (day.supplyStopAfterIndex == null) {
                    day.supplyStopNeeded = false
                }
            } else {
                day.supplyStopNeeded = false
            }

            day.projectedInventoryPct = ((runningBags / capacityBags) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        }
    }

    private fun applyInventoryProjection(
        days: List<PlannedDay>,
        startingGranularBags: Double,
        capacityBags: Double
    ): List<PlannedDay> {
        if (capacityBags <= 0.0) {
            return days.map {
                it.copy(
                    supplyStopNeeded = false,
                    projectedInventoryPct = 0,
                    supplyStopAfterIndex = null
                )
            }
        }

        val thresholdBags = capacityBags * AppConfig.WeeklyPlanner.REFILL_THRESHOLD_PCT
        var runningBags = startingGranularBags.coerceIn(0.0, capacityBags)

        return days.map { day ->
            var supplyStopAfterIndex: Int? = null
            var supplyStopNeeded = false

            if (day.isWorkDay && day.clients.isNotEmpty()) {
                for ((index, plannedClient) in day.clients.withIndex()) {
                    val consumption = estimateClientGranularConsumptionBags(plannedClient)
                    runningBags = (runningBags - consumption).coerceAtLeast(0.0)

                    if (runningBags < thresholdBags && supplyStopAfterIndex == null) {
                        supplyStopAfterIndex = (index - 1).coerceAtLeast(0)
                        supplyStopNeeded = true
                        runningBags = capacityBags
                    }
                }
            }

            day.copy(
                supplyStopNeeded = supplyStopNeeded,
                projectedInventoryPct = ((runningBags / capacityBags) * 100.0)
                    .roundToInt()
                    .coerceIn(0, 100),
                supplyStopAfterIndex = supplyStopAfterIndex
            )
        }
    }

    /** Estimate granular consumption in bags for a single client stop. */
    private fun estimateClientGranularConsumptionBags(plannedClient: PlannedClient): Double {
        val primaryGranularStep = plannedClient.eligibleSteps
            .asSequence()
            .filter { it.stepNumber in AppConfig.WeeklyPlanner.GRANULAR_STEPS }
            .sortedBy { it.stepNumber }
            .firstOrNull()
            ?: return 0.0
        val sqFt = plannedClient.client.lawnSizeSqFt.takeIf { it > 0 } ?: DEFAULT_SQFT_ESTIMATE
        val configuredRate = preferencesRepository.getGranularRate(primaryGranularStep).takeIf { it > 0.0 }
        return if (configuredRate != null) {
            estimateGranularConsumptionBags(sqFt, configuredRate)
        } else {
            estimateGranularConsumptionBags(sqFt)
        }
    }

    private fun estimateDayGranularConsumptionBags(day: PlannedDayBuilder): Double {
        return day.clients.sumOf { estimateClientGranularConsumptionBags(it) }
    }

    private data class PlannedDayBuilder(
        val dateMillis: Long,
        val dayOfWeek: Int,
        val dayName: String,
        val forecast: ForecastDay,
        val dayScore: Int,
        val dayScoreLabel: String,
        var isWorkDay: Boolean = false,
        val clients: MutableList<PlannedClient> = mutableListOf(),
        val anchorLat: Double? = null,
        val anchorLng: Double? = null,
        val anchorLabel: String? = null,
        var supplyStopNeeded: Boolean = false,
        var projectedInventoryPct: Int = 100,
        var supplyStopAfterIndex: Int? = null
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
                isWorkDay = isWorkDay,
                anchorLat = anchorLat,
                anchorLng = anchorLng,
                anchorLabel = anchorLabel,
                supplyStopNeeded = supplyStopNeeded,
                projectedInventoryPct = projectedInventoryPct,
                supplyStopAfterIndex = supplyStopAfterIndex
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
            Calendar.SUNDAY, Calendar.SATURDAY -> false  // Weekend: not auto-scheduled; Saturday is manual-add only
            else -> true  // Mon-Fri always scheduled; weather shown as label only
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
        eligibleSteps: Set<ServiceType> = emptySet(),
        recentWeatherSignal: RecentWeatherSignal? = null,
        dayIsRefillDay: Boolean = false
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

        val recentWeatherImpact = recentWeatherAdjustmentForPlanner(
            property = property,
            forecast = forecast,
            recentWeatherSignal = recentWeatherSignal,
            eligibleSteps = eligibleSteps
        )
        score += recentWeatherImpact.first
        recentWeatherImpact.second?.let { reasons += it }

        if (property.hasIrrigation &&
            forecast.highTempF >= AppConfig.Routing.WEATHER_DRY_HOT_THRESHOLD_F &&
            forecast.precipProbabilityPct < AppConfig.WeeklyPlanner.PRECIP_LOW_PCT
        ) {
            score += 10
            reasons += "Irrigated lawn — healthy in heat"
        }

        if (client.mowDayOfWeek in Calendar.SUNDAY..Calendar.SATURDAY) {
            // Days since last mow (0 = mow day, 1 = day after, ... 6 = day before next mow).
            // Prefer mid-cycle (2-5 days after mow) when grass has grown but treatment won't be mowed off.
            val daysSinceMow = ((dayOfWeek - client.mowDayOfWeek + 7) % 7)
            when (daysSinceMow) {
                0 -> {
                    score -= 100  // Mow day — freshly cut or about to be cut
                    reasons += "Mow day — avoid"
                }
                1 -> {
                    score -= 60  // Day after mow — grass too short
                    reasons += "Day after mow — grass too short"
                }
                2 -> {
                    score += 10  // Grass starting to grow
                    reasons += "2 days after mow — OK"
                }
                3, 4 -> {
                    score += 30  // Ideal window — grass grown, time to absorb
                    reasons += "$daysSinceMow days after mow — ideal"
                }
                5 -> {
                    score += 15  // Still good, grass taller
                    reasons += "5 days after mow — good"
                }
                6 -> {
                    score -= 40  // Day before mow — treatment may be mowed off
                    reasons += "Day before mow — may be mowed off"
                }
            }
        } else {
            // No mow day set — flexible schedule, treat every day as ideal.
            score += 30
            reasons += "No mow day — flexible schedule"
        }

        if (dayIsRefillDay) {
            val zone = client.zone.trim().uppercase()
            if (zone in AppConfig.SupplyHouse.SUPPLY_HOUSE_ZONES) {
                score += AppConfig.WeeklyPlanner.REFILL_DAY_ZONE_BONUS
                reasons += "Refill day near supply house"
            } else {
                score += AppConfig.WeeklyPlanner.REFILL_DAY_ZONE_PENALTY
                reasons += "Refill day detour"
            }
        }

        return score.coerceAtMost(100) to (reasons.firstOrNull() ?: "Standard fit")
    }

    private fun recentWeatherAdjustmentForPlanner(
        property: ClientProperty,
        forecast: ForecastDay,
        recentWeatherSignal: RecentWeatherSignal?,
        eligibleSteps: Set<ServiceType>
    ): Pair<Int, String?> {
        if (recentWeatherSignal == null) return 0 to null

        var adjustment = 0.0
        var reason: String? = null

        val rain24 = recentWeatherSignal.rainLast24hInches
        val rain48 = recentWeatherSignal.rainLast48hInches
        val soil = recentWeatherSignal.soilMoistureSurface
        val hasLiquidStep = eligibleSteps.any { it.stepNumber in AppConfig.WeeklyPlanner.LIQUID_STEPS }
        val hasGranularStep = eligibleSteps.any { it.stepNumber in AppConfig.WeeklyPlanner.GRANULAR_STEPS }

        if (property.hasSteepSlopes && rain48 != null && rain48 >= AppConfig.Routing.WEATHER_SLOPE_RAIN_THRESHOLD_INCHES) {
            adjustment += AppConfig.Routing.WEATHER_RECENT_SLOPE_RAIN_PENALTY
            reason = "Recent rain on steep slopes"
        }

        if (property.hasSteepSlopes && soil != null) {
            when {
                soil >= AppConfig.Routing.WEATHER_SOIL_MOISTURE_HIGH_THRESHOLD -> {
                    adjustment += AppConfig.Routing.WEATHER_SLOPE_SOIL_HIGH_PENALTY
                    reason = reason ?: "Soil saturated on slopes"
                }
                soil >= AppConfig.Routing.WEATHER_SOIL_MOISTURE_MODERATE_THRESHOLD -> {
                    adjustment += AppConfig.Routing.WEATHER_SLOPE_SOIL_MODERATE_PENALTY
                    reason = reason ?: "Soil still damp on slopes"
                }
            }
        }

        if (
            hasLiquidStep && !hasGranularStep &&
            rain24 != null && rain24 >= AppConfig.Routing.WEATHER_RECENT_RAIN_24H_WET_THRESHOLD_INCHES
        ) {
            adjustment += AppConfig.Routing.WEATHER_RECENT_LIQUID_RAIN_PENALTY
            reason = reason ?: "Liquid step after recent rain"
        }

        if (
            !property.hasIrrigation &&
            forecast.highTempF >= AppConfig.Routing.WEATHER_DRY_HOT_THRESHOLD_F &&
            rain24 != null && rain24 <= AppConfig.Routing.WEATHER_DRY_WINDOW_RAIN24_MAX_INCHES &&
            soil != null && soil <= AppConfig.Routing.WEATHER_SOIL_DRY_THRESHOLD
        ) {
            adjustment += AppConfig.Routing.WEATHER_DRY_WINDOW_NON_IRRIGATED_BONUS
            reason = reason ?: "Drying window on non-irrigated lawn"
        }

        adjustment = adjustment.coerceIn(
            AppConfig.Routing.WEATHER_ADJUSTMENT_MIN,
            AppConfig.Routing.WEATHER_ADJUSTMENT_MAX
        )

        return adjustment.roundToInt() to reason
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
     * Orders clients into a loop from the shop and back using angular sweep + 2-opt.
     * Clients without coordinates are appended at the end in their original order.
     */
    private fun nearestNeighbourOrder(
        clients: List<PlannedClient>,
        startLat: Double,
        startLng: Double
    ): List<PlannedClient> {
        val geocoded = clients.filter { it.client.latitude != null && it.client.longitude != null }
        val ungeocoded = clients.filter { it.client.latitude == null || it.client.longitude == null }

        if (geocoded.size <= 2) return geocoded + ungeocoded

        // Angular sweep: sort by bearing from shop to create a natural loop.
        val sorted = geocoded.sortedBy { pc ->
            atan2(
                pc.client.longitude!! - startLng,
                pc.client.latitude!! - startLat
            )
        }.toMutableList()

        // 2-opt improvement: iteratively uncross edges to shorten the loop.
        // The "route" is shop → sorted[0] → sorted[1] → … → sorted[n-1] → shop.
        fun routeDistance(route: MutableList<PlannedClient>): Double {
            var total = routingEngine.distanceMilesBetween(
                startLat, startLng, route.first().client.latitude!!, route.first().client.longitude!!
            )
            for (k in 0 until route.size - 1) {
                total += routingEngine.distanceMilesBetween(
                    route[k].client.latitude!!, route[k].client.longitude!!,
                    route[k + 1].client.latitude!!, route[k + 1].client.longitude!!
                )
            }
            total += routingEngine.distanceMilesBetween(
                route.last().client.latitude!!, route.last().client.longitude!!,
                startLat, startLng
            )
            return total
        }

        var improved = true
        while (improved) {
            improved = false
            for (i in 0 until sorted.size - 1) {
                for (j in i + 1 until sorted.size) {
                    val before = routeDistance(sorted)
                    sorted.subList(i, j + 1).reverse()
                    val after = routeDistance(sorted)
                    if (after < before) {
                        improved = true
                    } else {
                        // Revert
                        sorted.subList(i, j + 1).reverse()
                    }
                }
            }
        }

        // Rotate the loop to find the best start/end cut point.
        // The 2-opt optimised a closed loop; now find which rotation minimises
        // the open-path distance (shop → first → … → last → shop).
        if (sorted.size > 2) {
            val distances = (0 until sorted.size).map { start ->
                val rotated = (sorted.subList(start, sorted.size) + sorted.subList(0, start)).toMutableList()
                routeDistance(rotated)
            }
            val bestStart = distances.indices.minByOrNull { distances[it] } ?: 0
            if (bestStart > 0) {
                val rotated = (sorted.subList(bestStart, sorted.size) + sorted.subList(0, bestStart))
                sorted.clear()
                sorted.addAll(rotated)
            }
        }

        return sorted + ungeocoded
    }

    /** Re-optimise the route order for a day's existing clients without changing who is in the list. */
    suspend fun reoptimiseRoute(
        currentPlan: WeekPlan,
        dayIndex: Int,
        lat: Double = SHOP_LAT,
        lng: Double = SHOP_LNG
    ): PlannedDay? = withContext(Dispatchers.Default) {
        val targetDay = currentPlan.days.getOrNull(dayIndex) ?: return@withContext null
        if (targetDay.clients.isEmpty()) return@withContext null

        // Always use proper angular sweep + 2-opt routing
        val reordered = nearestNeighbourOrder(targetDay.clients, lat, lng)
        targetDay.copy(clients = reordered)
    }

    /**
     * Rebuild a single day's client list, blacklisting the previous picks so fresh names appear.
     * Clients assigned to other days are excluded — only freely-available clients are considered.
     */
    suspend fun rebuildDay(
        currentPlan: WeekPlan,
        dayIndex: Int,
        serviceTypes: Set<ServiceType> = ServiceType.entries.toSet(),
        minDays: Int = 7,
        lat: Double = SHOP_LAT,
        lng: Double = SHOP_LNG,
        lockedClientIds: Set<String> = emptySet()
    ): PlannedDay? = withContext(Dispatchers.Default) {
        val targetDay = currentPlan.days.getOrNull(dayIndex) ?: return@withContext null
        if (!targetDay.isWorkDay) return@withContext null

        // Locked clients stay exactly as they are (preserving order and lock state)
        val lockedClients = targetDay.clients.filter { it.client.id in lockedClientIds }
        val lockedIds = lockedClients.map { it.client.id }.toSet()

        // IDs on OTHER days — these clients stay put
        val otherDayClientIds = currentPlan.days
            .filterIndexed { i, _ -> i != dayIndex }
            .flatMap { it.clients }
            .map { it.client.id }
            .toSet()

        // Blacklist = non-locked current day clients (try different picks)
        val blacklist = targetDay.clients.filter { it.client.id !in lockedIds }.map { it.client.id }.toSet()

        val allClients = clientRepository.loadAllClients()
        val propertyMap = allClients.mapNotNull { c -> c.property?.let { c.id to it } }.toMap()

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

        // Pool = eligible minus other days, locked IDs, and blacklist
        val pool = eligibleSuggestions.filter { s ->
            s.client.id !in otherDayClientIds && s.client.id !in blacklist && s.client.id !in lockedIds
        }.sortedWith(
            compareByDescending<ClientSuggestion> { it.daysSinceLast ?: Int.MAX_VALUE }
                .thenByDescending { it.daysSinceLast == null }
        )

        // Target size: same as original count or soft cap
        val totalWorkDays = currentPlan.days.count { it.isWorkDay }
        val targetPerDay = ceil(eligibleSuggestions.size.toDouble() / totalWorkDays.coerceAtLeast(1).toDouble())
            .toInt().coerceAtLeast(1)
        val softCap = minOf(targetPerDay + 2, AppConfig.WeeklyPlanner.MAX_CLIENTS_PER_DAY)

        val forecast = targetDay.forecast ?: return@withContext null
        val dayOfWeek = targetDay.dayOfWeek
        val anchor = if (targetDay.anchorLat != null && targetDay.anchorLng != null)
            DayAnchor(targetDay.anchorLat, targetDay.anchorLng, targetDay.anchorLabel ?: "") else null

        // Start with locked clients; fill remaining slots from the pool
        val picked = lockedClients.toMutableList()
        var sqFtTotal = picked.sumOf { it.client.lawnSizeSqFt.takeIf { s -> s > 0 } ?: 0 }

        for (suggestion in pool) {
            if (picked.size >= softCap) break

            val clientMowDay = suggestion.client.mowDayOfWeek.takeIf { it in Calendar.SUNDAY..Calendar.SATURDAY }
            val daysSinceMow = if (clientMowDay != null) ((dayOfWeek - clientMowDay + 7) % 7) else null
            val blockedByMow = daysSinceMow != null && (daysSinceMow in 0..1 || daysSinceMow == 6)
            if (blockedByMow) continue

            val (fitnessScore, reason) = scoreClientDayFitness(
                client = suggestion.client,
                property = suggestion.client.property,
                forecast = forecast,
                dayOfWeek = dayOfWeek,
                eligibleSteps = suggestion.eligibleSteps
            )

            val zoneDensityBonus = if (suggestion.client.zone.isBlank()) 0 else {
                val sameZoneCount = picked.count { it.client.zone == suggestion.client.zone }
                minOf(
                    sameZoneCount * AppConfig.WeeklyPlanner.ZONE_DENSITY_BONUS_PER_CLIENT,
                    AppConfig.WeeklyPlanner.ZONE_DENSITY_BONUS_MAX
                )
            }

            val corridorBonus = if (anchor != null) {
                corridorScore(suggestion.client, lat, lng, anchor.lat, anchor.lng)
            } else 0

            val proximityBonus = proximityScore(suggestion.client, picked)

            val geoBonus = maxOf(zoneDensityBonus, corridorBonus) + proximityBonus
            val finalScore = fitnessScore + geoBonus

            val clientSqFt = suggestion.client.lawnSizeSqFt.takeIf { it > 0 }
            if (clientSqFt != null && sqFtTotal + clientSqFt > AppConfig.WeeklyPlanner.MAX_SQFT_PER_DAY) continue

            picked += PlannedClient(
                client = suggestion.client,
                fitnessScore = finalScore,
                fitnessLabel = fitnessScoreToLabel(finalScore).displayText,
                primaryReason = reason,
                eligibleSteps = suggestion.eligibleSteps,
                daysOverdue = suggestion.daysSinceLast
            )
            if (clientSqFt != null) sqFtTotal += clientSqFt
        }

        // Route order: if there are locked clients, preserve their positions and slot new picks around them
        val ordered = if (lockedClients.isEmpty()) {
            if (picked.size > 1) nearestNeighbourOrder(picked, lat, lng) else picked
        } else {
            // Route only the new (unlocked) picks, then interleave at the end after locked clients
            val newPicks = picked.filter { it.client.id !in lockedIds }
            val routedNew = if (newPicks.size > 1) nearestNeighbourOrder(newPicks, lat, lng) else newPicks
            lockedClients + routedNew
        }

        targetDay.copy(clients = ordered)
    }

    /**
     * Proximity bonus: how close a client is to any already-assigned client on the day.
     * Returns 0–GEO_AFFINITY_MAX_BONUS, scaling linearly from max at 0 mi to 0 at GEO_AFFINITY_RADIUS_MILES.
     */
    private fun proximityScore(client: Client, dayClients: List<PlannedClient>): Int {
        val cLat = client.latitude ?: return 0
        val cLng = client.longitude ?: return 0
        val cosLat = kotlin.math.cos(Math.toRadians(cLat))

        val closestMiles = dayClients.mapNotNull { existing ->
            val eLat = existing.client.latitude ?: return@mapNotNull null
            val eLng = existing.client.longitude ?: return@mapNotNull null
            val dLat = (cLat - eLat) * 69.0
            val dLng = (cLng - eLng) * 69.0 * cosLat
            sqrt(dLat * dLat + dLng * dLng)
        }.minOrNull() ?: return 0

        val radius = AppConfig.WeeklyPlanner.GEO_AFFINITY_RADIUS_MILES
        if (closestMiles >= radius) return 0
        val fraction = 1.0 - (closestMiles / radius)
        return (fraction * AppConfig.WeeklyPlanner.GEO_AFFINITY_MAX_BONUS).toInt()
    }

    /**
     * Perpendicular distance from a client to the shop→anchor travel corridor.
     * Returns a bonus (0 to CORRIDOR_BONUS_MAX) that scales linearly:
     * 0 miles off-line → full bonus, ≥CORRIDOR_MAX_OFFSET_MILES → zero.
     */
    private fun corridorScore(
        client: Client,
        shopLat: Double, shopLng: Double,
        anchorLat: Double, anchorLng: Double
    ): Int {
        val cLat = client.latitude ?: return 0
        val cLng = client.longitude ?: return 0

        // Work in approximate miles: 1° lat ≈ 69 mi, 1° lng ≈ 69 * cos(lat) mi
        val cosLat = kotlin.math.cos(Math.toRadians(shopLat))
        val ax = 0.0
        val ay = 0.0
        val bx = (anchorLng - shopLng) * 69.0 * cosLat
        val by = (anchorLat - shopLat) * 69.0
        val px = (cLng - shopLng) * 69.0 * cosLat
        val py = (cLat - shopLat) * 69.0

        // Project point P onto segment A→B, clamping to [0,1] so points beyond
        // the anchor or behind the shop don't get credit.
        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy
        if (segLenSq < 1e-9) return 0  // shop ≈ anchor — degenerate

        val t = ((px - ax) * dx + (py - ay) * dy) / segLenSq
        val tc = t.coerceIn(0.0, 1.0)
        val projX = ax + tc * dx
        val projY = ay + tc * dy
        val distMiles = sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))

        val maxOffset = AppConfig.WeeklyPlanner.CORRIDOR_MAX_OFFSET_MILES
        if (distMiles >= maxOffset) return 0
        val fraction = 1.0 - (distMiles / maxOffset)
        return (fraction * AppConfig.WeeklyPlanner.CORRIDOR_BONUS_MAX).toInt()
    }
}

/** Anchor point for a day — the furthest destination for that day's travel corridor. */
data class DayAnchor(
    val lat: Double,
    val lng: Double,
    val label: String
)
