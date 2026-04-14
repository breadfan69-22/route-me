package com.routeme.app.domain

import com.routeme.app.Client
import com.routeme.app.ClientProperty
import com.routeme.app.ClientSuggestion
import com.routeme.app.ProductType
import com.routeme.app.RouteDirection
import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.ServiceType
import com.routeme.app.SunShade
import com.routeme.app.TruckInventory
import com.routeme.app.WindExposure
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WeatherRepository
import com.routeme.app.model.ForecastDay
import com.routeme.app.model.PlannedClient
import com.routeme.app.model.PlannedDay
import com.routeme.app.model.WeekPlan
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.every
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WeeklyPlannerUseCaseTest {

    @Test
    fun `generateWeekPlan excludes sunday and saturday from auto-schedule`() = runTest {
        val weatherRepository = mockk<WeatherRepository>()
        val clientRepository = mockk<ClientRepository>()
        val routingEngine = mockk<RoutingEngine>()
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true)

        val saturday = nextDateMillis(Calendar.SATURDAY)
        val sunday = nextDateMillis(Calendar.SUNDAY)
        val forecast = listOf(
            ForecastDay(
                dateMillis = saturday,
                // Perfect weather — but Saturday should still be excluded from auto-schedule
                highTempF = 72,
                lowTempF = 55,
                windSpeedMph = 5,
                windGustMph = null,
                precipProbabilityPct = 5,
                shortForecast = "Sunny",
                detailedForecast = "Beautiful day"
            ),
            ForecastDay(
                dateMillis = sunday,
                highTempF = 70,
                lowTempF = 50,
                windSpeedMph = 6,
                windGustMph = null,
                precipProbabilityPct = 10,
                shortForecast = "Sunny",
                detailedForecast = "Sunny"
            )
        )

        val client = testClient("1")
        val suggestion = testSuggestion(client)

        coEvery { weatherRepository.getForecastDays(dayCount = 7, lat = SHOP_LAT, lng = SHOP_LNG) } returns forecast
        coEvery { weatherRepository.getRecentWeatherSignal(SHOP_LAT, SHOP_LNG) } returns null
        coEvery { clientRepository.loadAllClients() } returns listOf(client)
        coEvery {
            routingEngine.rankClients(
                clients = listOf(client),
                serviceTypes = ServiceType.entries.toSet(),
                minDays = 7,
                lastLocation = null,
                cuOverrideEnabled = false,
                routeDirection = com.routeme.app.RouteDirection.OUTWARD,
                weather = null,
                recentPrecipInches = null,
                propertyMap = mapOf(client.id to client.property!!),
                recentWeatherByClientId = emptyMap()
            )
        } returns listOf(suggestion)

        val useCase = WeeklyPlannerUseCase(
            weatherRepository = weatherRepository,
            clientRepository = clientRepository,
            routingEngine = routingEngine,
            preferencesRepository = preferencesRepository
        )

        val plan = useCase.generateWeekPlan()

        assertEquals(2, plan.days.size)
        assertFalse(plan.days.first { it.dayOfWeek == Calendar.SATURDAY }.isWorkDay)
        assertFalse(plan.days.first { it.dayOfWeek == Calendar.SUNDAY }.isWorkDay)
        assertEquals(1, plan.totalClients)
        assertEquals(1, plan.unassignedCount)
    }

    @Test
    fun `generateWeekPlan assigns wind-exposed client to calmer day`() = runTest {
        val weatherRepository = mockk<WeatherRepository>()
        val clientRepository = mockk<ClientRepository>()
        val routingEngine = mockk<RoutingEngine>()
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true)

        val monday = nextDateMillis(Calendar.MONDAY)
        val tuesday = nextDateMillis(Calendar.TUESDAY)
        val windyMonday = ForecastDay(
            dateMillis = monday,
            highTempF = 72,
            lowTempF = 55,
            windSpeedMph = 22,
            windGustMph = 30,
            precipProbabilityPct = 10,
            shortForecast = "Breezy",
            detailedForecast = "Windy"
        )
        val calmTuesday = ForecastDay(
            dateMillis = tuesday,
            highTempF = 74,
            lowTempF = 54,
            windSpeedMph = 5,
            windGustMph = null,
            precipProbabilityPct = 10,
            shortForecast = "Sunny",
            detailedForecast = "Sunny"
        )

        val client = testClient("2")
        val suggestion = testSuggestion(client)

        coEvery { weatherRepository.getForecastDays(dayCount = 7, lat = SHOP_LAT, lng = SHOP_LNG) } returns listOf(windyMonday, calmTuesday)
        coEvery { weatherRepository.getRecentWeatherSignal(SHOP_LAT, SHOP_LNG) } returns null
        coEvery { clientRepository.loadAllClients() } returns listOf(client)
        coEvery {
            routingEngine.rankClients(
                clients = listOf(client),
                serviceTypes = ServiceType.entries.toSet(),
                minDays = 7,
                lastLocation = null,
                cuOverrideEnabled = false,
                routeDirection = com.routeme.app.RouteDirection.OUTWARD,
                weather = null,
                recentPrecipInches = null,
                propertyMap = mapOf(client.id to client.property!!),
                recentWeatherByClientId = emptyMap()
            )
        } returns listOf(suggestion)

        val useCase = WeeklyPlannerUseCase(
            weatherRepository = weatherRepository,
            clientRepository = clientRepository,
            routingEngine = routingEngine,
            preferencesRepository = preferencesRepository
        )

        val plan = useCase.generateWeekPlan()

        val mondayPlan = plan.days.first { it.dayOfWeek == Calendar.MONDAY }
        val tuesdayPlan = plan.days.first { it.dayOfWeek == Calendar.TUESDAY }

        assertTrue(mondayPlan.isWorkDay)
        assertTrue(tuesdayPlan.isWorkDay)
        assertTrue(tuesdayPlan.clients.any { it.client.id == "2" })
        assertEquals(0, plan.unassignedCount)
    }

    @Test
    fun `generateWeekPlan clusters refill day toward supply-house zones`() = runTest {
        val weatherRepository = mockk<WeatherRepository>()
        val clientRepository = mockk<ClientRepository>()
        val routingEngine = spyk(RoutingEngine())
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true)
        val truckInventoryUseCase = mockk<TruckInventoryUseCase>()

        val monday = nextDateMillis(Calendar.MONDAY)
        val tuesday = nextDateMillis(Calendar.TUESDAY)
        val forecast = listOf(
            ForecastDay(
                dateMillis = monday,
                highTempF = 72,
                lowTempF = 52,
                windSpeedMph = 5,
                windGustMph = null,
                precipProbabilityPct = 10,
                shortForecast = "Sunny",
                detailedForecast = "Sunny"
            ),
            ForecastDay(
                dateMillis = tuesday,
                highTempF = 73,
                lowTempF = 53,
                windSpeedMph = 6,
                windGustMph = null,
                precipProbabilityPct = 10,
                shortForecast = "Sunny",
                detailedForecast = "Sunny"
            )
        )

        val supplyA = testClient("7A").copy(
            zone = "S09",
            lawnSizeSqFt = 20_000,
            latitude = 42.2480,
            longitude = -85.6760,
            property = testClient("7A").property?.copy(lawnSizeSqFt = 20_000)
        )
        val supplyB = testClient("7B").copy(
            zone = "S09",
            lawnSizeSqFt = 20_000,
            latitude = 42.2420,
            longitude = -85.6810,
            property = testClient("7B").property?.copy(lawnSizeSqFt = 20_000)
        )
        val farA = testClient("7C").copy(
            zone = "KAL",
            lawnSizeSqFt = 20_000,
            latitude = 42.4100,
            longitude = -85.5000,
            property = testClient("7C").property?.copy(lawnSizeSqFt = 20_000)
        )
        val farB = testClient("7D").copy(
            zone = "KAL",
            lawnSizeSqFt = 20_000,
            latitude = 42.3950,
            longitude = -85.4700,
            property = testClient("7D").property?.copy(lawnSizeSqFt = 20_000)
        )
        val allClients = listOf(supplyA, farA, supplyB, farB)
        val suggestions = allClients.map { client ->
            ClientSuggestion(
                client = client,
                daysSinceLast = 30,
                distanceMiles = 1.0,
                distanceToShopMiles = 1.0,
                mowWindowPreferred = true,
                eligibleSteps = setOf(ServiceType.ROUND_1)
            )
        }

        coEvery { weatherRepository.getForecastDays(dayCount = 7, lat = SHOP_LAT, lng = SHOP_LNG) } returns forecast
        coEvery { weatherRepository.getRecentWeatherSignal(SHOP_LAT, SHOP_LNG) } returns null
        coEvery { clientRepository.loadAllClients() } returns allClients
        every {
            routingEngine.rankClients(
                clients = allClients,
                serviceTypes = setOf(ServiceType.ROUND_1),
                minDays = 7,
                lastLocation = null,
                cuOverrideEnabled = false,
                routeDirection = com.routeme.app.RouteDirection.OUTWARD,
                weather = null,
                recentPrecipInches = null,
                propertyMap = allClients.associate { it.id to it.property!! },
                recentWeatherByClientId = emptyMap()
            )
        } returns suggestions
        coEvery { truckInventoryUseCase.loadInventory() } returns mapOf(
            ProductType.GRANULAR to TruckInventory(
                productType = ProductType.GRANULAR,
                currentStock = 3.0,
                capacity = 10.0,
                unit = "bags",
                pctRemaining = 30
            )
        )

        val useCase = WeeklyPlannerUseCase(
            weatherRepository = weatherRepository,
            clientRepository = clientRepository,
            routingEngine = routingEngine,
            preferencesRepository = preferencesRepository,
            truckInventoryUseCase = truckInventoryUseCase
        )

        val plan = useCase.generateWeekPlan(serviceTypes = setOf(ServiceType.ROUND_1))
        val refillDay = plan.days.first { it.supplyStopNeeded }

        assertTrue(refillDay.clients.isNotEmpty())
        assertTrue(refillDay.clients.all { it.client.zone == "S09" })
    }

    @Test
    fun `refreshInventoryProjection uses configured granular rate and preserves route order`() = runTest {
        val weatherRepository = mockk<WeatherRepository>()
        val clientRepository = mockk<ClientRepository>()
        val routingEngine = RoutingEngine()
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true)
        val truckInventoryUseCase = mockk<TruckInventoryUseCase>()

        val client = testClient("3").copy(
            subscribedSteps = setOf(3),
            lawnSizeSqFt = 10_000,
            property = testClient("3").property?.copy(lawnSizeSqFt = 10_000)
        )
        val plan = WeekPlan(
            days = listOf(
                PlannedDay(
                    dateMillis = nextDateMillis(Calendar.MONDAY),
                    dayOfWeek = Calendar.MONDAY,
                    dayName = "Monday",
                    forecast = null,
                    dayScore = 80,
                    dayScoreLabel = "Great",
                    clients = listOf(
                        PlannedClient(
                            client = client,
                            fitnessScore = 75,
                            fitnessLabel = "Great",
                            primaryReason = "Test",
                            eligibleSteps = setOf(ServiceType.ROUND_3),
                            daysOverdue = 21
                        )
                    ),
                    isWorkDay = true,
                    projectedInventoryPct = 100
                )
            ),
            generatedAtMillis = 0L,
            totalClients = 1,
            unassignedCount = 0
        )

        every { preferencesRepository.getGranularRate(ServiceType.ROUND_3) } returns 5.0
        coEvery { truckInventoryUseCase.loadInventory() } returns mapOf(
            ProductType.GRANULAR to TruckInventory(
                productType = ProductType.GRANULAR,
                currentStock = 50.0,
                capacity = 50.0,
                unit = "bags",
                pctRemaining = 100
            )
        )

        val useCase = WeeklyPlannerUseCase(
            weatherRepository = weatherRepository,
            clientRepository = clientRepository,
            routingEngine = routingEngine,
            preferencesRepository = preferencesRepository,
            truckInventoryUseCase = truckInventoryUseCase
        )

        val refreshed = useCase.refreshInventoryProjection(plan)

        assertEquals(listOf("3"), refreshed.days.first().clients.map { it.client.id })
        assertEquals(98, refreshed.days.first().projectedInventoryPct)
        assertFalse(refreshed.days.first().supplyStopNeeded)
    }

    @Test
    fun `refreshInventoryProjection normalizes stale workday flags`() = runTest {
        val weatherRepository = mockk<WeatherRepository>()
        val clientRepository = mockk<ClientRepository>()
        val routingEngine = RoutingEngine()
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true)

        val plan = WeekPlan(
            days = listOf(
                PlannedDay(
                    dateMillis = nextDateMillis(Calendar.MONDAY),
                    dayOfWeek = Calendar.MONDAY,
                    dayName = "Monday",
                    forecast = null,
                    dayScore = 80,
                    dayScoreLabel = "Great",
                    clients = emptyList(),
                    isWorkDay = false
                ),
                PlannedDay(
                    dateMillis = nextDateMillis(Calendar.SATURDAY),
                    dayOfWeek = Calendar.SATURDAY,
                    dayName = "Saturday",
                    forecast = null,
                    dayScore = 80,
                    dayScoreLabel = "Great",
                    clients = emptyList(),
                    isWorkDay = true
                )
            ),
            generatedAtMillis = System.currentTimeMillis(),
            totalClients = 0,
            unassignedCount = 0
        )

        val useCase = WeeklyPlannerUseCase(
            weatherRepository = weatherRepository,
            clientRepository = clientRepository,
            routingEngine = routingEngine,
            preferencesRepository = preferencesRepository
        )

        val refreshed = useCase.refreshInventoryProjection(plan)

        assertTrue(refreshed.days.first { it.dayOfWeek == Calendar.MONDAY }.isWorkDay)
        assertFalse(refreshed.days.first { it.dayOfWeek == Calendar.SATURDAY }.isWorkDay)
    }

    @Test
    fun `refreshInventoryProjection clears stale refill metadata after edits`() = runTest {
        val weatherRepository = mockk<WeatherRepository>()
        val clientRepository = mockk<ClientRepository>()
        val routingEngine = RoutingEngine()
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true)
        val truckInventoryUseCase = mockk<TruckInventoryUseCase>()

        val client = testClient("4").copy(lawnSizeSqFt = 10_000)
        val editedPlan = WeekPlan(
            days = listOf(
                PlannedDay(
                    dateMillis = nextDateMillis(Calendar.TUESDAY),
                    dayOfWeek = Calendar.TUESDAY,
                    dayName = "Tuesday",
                    forecast = null,
                    dayScore = 70,
                    dayScoreLabel = "Good",
                    clients = listOf(
                        PlannedClient(
                            client = client,
                            fitnessScore = 60,
                            fitnessLabel = "Good",
                            primaryReason = "Test",
                            eligibleSteps = setOf(ServiceType.ROUND_1),
                            daysOverdue = 14
                        )
                    ),
                    isWorkDay = true,
                    supplyStopNeeded = true,
                    projectedInventoryPct = 0,
                    supplyStopAfterIndex = 0
                )
            ),
            generatedAtMillis = 0L,
            totalClients = 1,
            unassignedCount = 0
        )

        coEvery { truckInventoryUseCase.loadInventory() } returns mapOf(
            ProductType.GRANULAR to TruckInventory(
                productType = ProductType.GRANULAR,
                currentStock = 1.8,
                capacity = 2.0,
                unit = "bags",
                pctRemaining = 90
            )
        )

        val useCase = WeeklyPlannerUseCase(
            weatherRepository = weatherRepository,
            clientRepository = clientRepository,
            routingEngine = routingEngine,
            preferencesRepository = preferencesRepository,
            truckInventoryUseCase = truckInventoryUseCase
        )

        val refreshed = useCase.refreshInventoryProjection(editedPlan)

        assertFalse(refreshed.days.first().supplyStopNeeded)
        assertEquals(55, refreshed.days.first().projectedInventoryPct)
        assertEquals(null, refreshed.days.first().supplyStopAfterIndex)
    }

    @Test
    fun `refreshInventoryProjection inserts refill before first stop when truck starts below floor`() = runTest {
        val weatherRepository = mockk<WeatherRepository>()
        val clientRepository = mockk<ClientRepository>()
        val routingEngine = RoutingEngine()
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true)
        val truckInventoryUseCase = mockk<TruckInventoryUseCase>()

        val client = testClient("5").copy(
            lawnSizeSqFt = 10_000,
            latitude = 42.30,
            longitude = -85.60,
            property = testClient("5").property?.copy(lawnSizeSqFt = 10_000)
        )
        val plan = WeekPlan(
            days = listOf(
                PlannedDay(
                    dateMillis = nextDateMillis(Calendar.WEDNESDAY),
                    dayOfWeek = Calendar.WEDNESDAY,
                    dayName = "Wednesday",
                    forecast = null,
                    dayScore = 75,
                    dayScoreLabel = "Good",
                    clients = listOf(
                        PlannedClient(
                            client = client,
                            fitnessScore = 60,
                            fitnessLabel = "Good",
                            primaryReason = "Test",
                            eligibleSteps = setOf(ServiceType.ROUND_3),
                            daysOverdue = 20
                        )
                    ),
                    isWorkDay = true,
                    projectedInventoryPct = 10
                )
            ),
            generatedAtMillis = 0L,
            totalClients = 1,
            unassignedCount = 0
        )

        every { preferencesRepository.getGranularRate(ServiceType.ROUND_3) } returns 5.0
        coEvery { truckInventoryUseCase.loadInventory() } returns mapOf(
            ProductType.GRANULAR to TruckInventory(
                productType = ProductType.GRANULAR,
                currentStock = 0.5,
                capacity = 10.0,
                unit = "bags",
                pctRemaining = 5
            )
        )

        val useCase = WeeklyPlannerUseCase(
            weatherRepository = weatherRepository,
            clientRepository = clientRepository,
            routingEngine = routingEngine,
            preferencesRepository = preferencesRepository,
            truckInventoryUseCase = truckInventoryUseCase
        )

        val refreshed = useCase.refreshInventoryProjection(plan)

        assertTrue(refreshed.days.first().supplyStopNeeded)
        assertEquals(-1, refreshed.days.first().supplyStopAfterIndex)
    }

    @Test
    fun `refreshInventoryProjection places refill within one to three bag window when possible`() = runTest {
        val weatherRepository = mockk<WeatherRepository>()
        val clientRepository = mockk<ClientRepository>()
        val routingEngine = RoutingEngine()
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true)
        val truckInventoryUseCase = mockk<TruckInventoryUseCase>()

        val clients = listOf(
            plannedRound3Client("6A", 42.31, -85.66),
            plannedRound3Client("6B", 42.295, -85.675),
            plannedRound3Client("6C", 42.285, -85.682),
            plannedRound3Client("6D", 42.265, -85.69),
            plannedRound3Client("6E", 42.255, -85.70)
        )
        val plan = WeekPlan(
            days = listOf(
                PlannedDay(
                    dateMillis = nextDateMillis(Calendar.THURSDAY),
                    dayOfWeek = Calendar.THURSDAY,
                    dayName = "Thursday",
                    forecast = null,
                    dayScore = 82,
                    dayScoreLabel = "Great",
                    clients = clients,
                    isWorkDay = true,
                    projectedInventoryPct = 100
                )
            ),
            generatedAtMillis = 0L,
            totalClients = clients.size,
            unassignedCount = 0
        )

        every { preferencesRepository.getGranularRate(ServiceType.ROUND_3) } returns 5.0
        coEvery { truckInventoryUseCase.loadInventory() } returns mapOf(
            ProductType.GRANULAR to TruckInventory(
                productType = ProductType.GRANULAR,
                currentStock = 5.0,
                capacity = 10.0,
                unit = "bags",
                pctRemaining = 50
            )
        )

        val useCase = WeeklyPlannerUseCase(
            weatherRepository = weatherRepository,
            clientRepository = clientRepository,
            routingEngine = routingEngine,
            preferencesRepository = preferencesRepository,
            truckInventoryUseCase = truckInventoryUseCase
        )

        val refreshed = useCase.refreshInventoryProjection(plan)
        val day = refreshed.days.first()
        val refillIndex = day.supplyStopAfterIndex

        assertTrue(day.supplyStopNeeded)
        assertNotNull(refillIndex)
        assertTrue(refillIndex!! >= 0)

        val bagsRemainingAtRefill = 5.0 - day.clients.take(refillIndex + 1).size.toDouble()
        assertTrue(bagsRemainingAtRefill in 1.0..3.0)
    }

    @Test
    fun `generateWeekPlan assigns non-grub granular clients to earlier days than grub clients`() = runTest {
        val weatherRepository = mockk<WeatherRepository>()
        val clientRepository = mockk<ClientRepository>()
        val routingEngine = mockk<RoutingEngine>()
        val preferencesRepository = mockk<PreferencesRepository>(relaxed = true)

        val monday = nextDateMillis(Calendar.MONDAY)
        val tuesday = nextDateMillis(Calendar.TUESDAY)
        // Make Monday clearly better so both clients prefer it
        val forecast = listOf(
            ForecastDay(
                dateMillis = monday,
                highTempF = 72, lowTempF = 52, windSpeedMph = 3,
                windGustMph = null, precipProbabilityPct = 5,
                shortForecast = "Sunny", detailedForecast = "Sunny"
            ),
            ForecastDay(
                dateMillis = tuesday,
                highTempF = 68, lowTempF = 48, windSpeedMph = 18,
                windGustMph = 25, precipProbabilityPct = 50,
                shortForecast = "Windy/Showers", detailedForecast = "Windy with showers"
            )
        )

        // Two clients with identical overdue days — only difference is grub flag.
        // Large lawns so only one fits per day (MAX_SQFT_PER_DAY = 500k).
        val nonGrubClient = testClient("NG").copy(
            hasGrub = false,
            subscribedSteps = setOf(1),
            zone = "WEST",
            lawnSizeSqFt = 300_000,
            latitude = 42.2480, longitude = -85.5650,
            property = testClient("NG").property?.copy(lawnSizeSqFt = 300_000)
        )
        val grubClient = testClient("G").copy(
            hasGrub = true,
            subscribedSteps = setOf(1),
            zone = "EAST",
            lawnSizeSqFt = 300_000,
            latitude = 42.3100, longitude = -85.5000,
            property = testClient("G").property?.copy(lawnSizeSqFt = 300_000)
        )
        val allClients = listOf(grubClient, nonGrubClient) // grub listed first to be fair

        val suggestions = allClients.map { client ->
            ClientSuggestion(
                client = client,
                daysSinceLast = 30,
                distanceMiles = 1.0,
                distanceToShopMiles = 1.0,
                mowWindowPreferred = true,
                eligibleSteps = setOf(ServiceType.ROUND_1)
            )
        }

        coEvery { weatherRepository.getForecastDays(dayCount = 7, lat = SHOP_LAT, lng = SHOP_LNG) } returns forecast
        coEvery { weatherRepository.getRecentWeatherSignal(SHOP_LAT, SHOP_LNG) } returns null
        coEvery { clientRepository.loadAllClients() } returns allClients
        coEvery {
            routingEngine.rankClients(
                clients = allClients,
                serviceTypes = ServiceType.entries.toSet(),
                minDays = 7,
                lastLocation = null,
                cuOverrideEnabled = false,
                routeDirection = RouteDirection.OUTWARD,
                weather = null,
                recentPrecipInches = null,
                propertyMap = allClients.associate { it.id to it.property!! },
                recentWeatherByClientId = emptyMap()
            )
        } returns suggestions

        val useCase = WeeklyPlannerUseCase(
            weatherRepository = weatherRepository,
            clientRepository = clientRepository,
            routingEngine = routingEngine,
            preferencesRepository = preferencesRepository
        )

        val plan = useCase.generateWeekPlan()

        // Non-grub client should be on the earliest day (Monday)
        val mondayClients = plan.days.first { it.dayOfWeek == Calendar.MONDAY }.clients
        val tuesdayClients = plan.days.first { it.dayOfWeek == Calendar.TUESDAY }.clients

        assertTrue(
            "Non-grub client should appear on Monday (earliest day)",
            mondayClients.any { it.client.id == "NG" }
        )
        assertTrue(
            "Grub client should appear on Tuesday (later day)",
            tuesdayClients.any { it.client.id == "G" }
        )
    }

    private fun testClient(id: String): Client {
        val property = ClientProperty(
            clientId = id,
            lawnSizeSqFt = 18000,
            sunShade = SunShade.PARTIAL_SHADE,
            windExposure = WindExposure.EXPOSED,
            hasSteepSlopes = false,
            hasIrrigation = false,
            propertyNotes = "",
            updatedAtMillis = 0L
        )

        return Client(
            id = id,
            name = "Client-$id",
            address = "123 Test St",
            zone = "KAL",
            notes = "",
            subscribedSteps = setOf(1),
            hasGrub = false,
            mowDayOfWeek = 0,
            lawnSizeSqFt = property.lawnSizeSqFt,
            sunShade = property.sunShade.name,
            terrain = "",
            windExposure = property.windExposure.name,
            latitude = 42.2478,
            longitude = -85.564,
            records = emptyList(),
            property = property
        )
    }

    private fun testSuggestion(client: Client): ClientSuggestion {
        return ClientSuggestion(
            client = client,
            daysSinceLast = 30,
            distanceMiles = 1.0,
            distanceToShopMiles = 1.0,
            mowWindowPreferred = true,
            eligibleSteps = setOf(ServiceType.ROUND_1)
        )
    }

    private fun plannedRound3Client(id: String, lat: Double, lng: Double): PlannedClient {
        val client = testClient(id).copy(
            subscribedSteps = setOf(3),
            lawnSizeSqFt = 10_000,
            latitude = lat,
            longitude = lng,
            property = testClient(id).property?.copy(lawnSizeSqFt = 10_000)
        )
        return PlannedClient(
            client = client,
            fitnessScore = 70,
            fitnessLabel = "Good",
            primaryReason = "Test",
            eligibleSteps = setOf(ServiceType.ROUND_3),
            daysOverdue = 21
        )
    }

    private fun nextDateMillis(dayOfWeek: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        repeat(8) {
            if (calendar.get(Calendar.DAY_OF_WEEK) == dayOfWeek) {
                return calendar.timeInMillis
            }
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }
}
