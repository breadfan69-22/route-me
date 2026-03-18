package com.routeme.app.domain

import com.routeme.app.Client
import com.routeme.app.ClientSuggestion
import com.routeme.app.RouteDirection
import com.routeme.app.ServiceType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionUseCaseTest {

    @Test
    fun `suggestNextClients returns selected client and status when ranked list is not empty`() {
        val routingEngine = mockk<RoutingEngine>()
        val useCase = SuggestionUseCase(routingEngine)

        val client = testClient("1")
        val suggestion = testSuggestion(client)

        every {
            routingEngine.rankClients(
                clients = listOf(client),
                serviceTypes = setOf(ServiceType.ROUND_1),
                minDays = 21,
                lastLocation = null,
                cuOverrideEnabled = false,
                routeDirection = RouteDirection.OUTWARD,
                skippedClientIds = any(),
                destination = null
            )
        } returns listOf(suggestion)
        every { routingEngine.buildClientDetails(client) } returns "details"

        val result = useCase.suggestNextClients(
            clients = listOf(client),
            selectedServiceTypes = setOf(ServiceType.ROUND_1),
            minDays = 21,
            cuOverrideEnabled = false,
            routeDirection = RouteDirection.OUTWARD,
            activeDestination = null,
            currentLocation = null
        )

        assertEquals(1, result.suggestions.size)
        assertEquals("1", result.selectedClient?.id)
        assertEquals("details", result.selectedClientDetails)
        assertEquals(0, result.suggestionOffset)
        assertEquals("Selected Client-1", result.statusMessage)
        assertFalse(result.dateRolloverDetected)
    }

    @Test
    fun `skipSelectedClientToday removes selected client and tracks skipped count`() {
        val routingEngine = mockk<RoutingEngine>()
        val useCase = SuggestionUseCase(routingEngine)

        val first = testClient("1")
        val second = testClient("2")
        val suggestions = listOf(testSuggestion(first), testSuggestion(second))

        every { routingEngine.buildClientDetails(second) } returns "second-details"

        val result = useCase.skipSelectedClientToday(
            selectedClient = first,
            suggestions = suggestions
        )

        assertTrue(result != null)
        assertEquals(1, result!!.suggestions.size)
        assertEquals("2", result.selectedClient?.id)
        assertEquals("second-details", result.selectedClientDetails)
        assertEquals(1, useCase.skippedCount())
        assertTrue(result.statusMessage.contains("1 skipped"))
    }

    @Test
    fun `suggestNextClients clears skipped set on date rollover`() {
        var nowMillis = 0L
        val routingEngine = mockk<RoutingEngine>()
        val useCase = SuggestionUseCase(routingEngine, nowProvider = { nowMillis })

        val client = testClient("1")
        val suggestion = testSuggestion(client)

        every { routingEngine.buildClientDetails(client) } returns "details"

        useCase.skipSelectedClientToday(
            selectedClient = client,
            suggestions = listOf(suggestion)
        )
        assertEquals(1, useCase.skippedCount())

        val skippedSlot = slot<Set<String>>()
        every {
            routingEngine.rankClients(
                clients = listOf(client),
                serviceTypes = setOf(ServiceType.ROUND_1),
                minDays = 21,
                lastLocation = null,
                cuOverrideEnabled = false,
                routeDirection = RouteDirection.OUTWARD,
                skippedClientIds = capture(skippedSlot),
                destination = null
            )
        } returns listOf(suggestion)

        nowMillis = 86_400_000L

        val result = useCase.suggestNextClients(
            clients = listOf(client),
            selectedServiceTypes = setOf(ServiceType.ROUND_1),
            minDays = 21,
            cuOverrideEnabled = false,
            routeDirection = RouteDirection.OUTWARD,
            activeDestination = null,
            currentLocation = null
        )

        assertTrue(result.dateRolloverDetected)
        assertTrue(skippedSlot.captured.isEmpty())
        assertEquals(0, useCase.skippedCount())
    }

    @Test
    fun `pagination helpers compute offsets and remaining counts`() {
        val useCase = SuggestionUseCase(mockk())
        val suggestions = (1..12).map { idx -> testSuggestion(testClient(idx.toString())) }

        assertEquals(5, useCase.currentPageSuggestions(suggestions, 0).size)
        assertEquals(5, useCase.nextSuggestionOffset(0, suggestions.size))
        assertEquals(10, useCase.nextSuggestionOffset(5, suggestions.size))
        assertEquals(0, useCase.nextSuggestionOffset(10, suggestions.size))
        assertEquals(5, useCase.previousSuggestionOffset(10))
        assertTrue(useCase.canShowMoreSuggestions(5, suggestions.size))
        assertFalse(useCase.canShowMoreSuggestions(10, suggestions.size))
        assertTrue(useCase.canShowPreviousSuggestions(5))
        assertFalse(useCase.canShowPreviousSuggestions(0))
        assertEquals(2, useCase.remainingSuggestionCount(5, suggestions.size))
        assertEquals(0, useCase.remainingSuggestionCount(10, suggestions.size))
    }

    private fun testSuggestion(client: Client): ClientSuggestion {
        return ClientSuggestion(
            client = client,
            daysSinceLast = 30,
            distanceMiles = 1.0,
            distanceToShopMiles = 1.0,
            mowWindowPreferred = true
        )
    }

    private fun testClient(id: String): Client {
        return Client(
            id = id,
            name = "Client-$id",
            address = "123 Test St",
            zone = "KAL",
            notes = "",
            subscribedSteps = setOf(1),
            hasGrub = false,
            mowDayOfWeek = 0,
            lawnSizeSqFt = 0,
            sunShade = "",
            terrain = "",
            windExposure = "",
            latitude = 42.2478,
            longitude = -85.564,
            records = mutableListOf()
        )
    }
}
