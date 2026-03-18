package com.routeme.app.domain

import com.routeme.app.Client
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalUseCaseTest {

    @Test
    fun `startArrivalForSelected returns error when no selected client`() {
        val useCase = ArrivalUseCase(mockk())

        val result = useCase.startArrivalForSelected(
            selectedClient = null,
            currentLocation = ArrivalUseCase.GeoPoint(42.2478, -85.564)
        )

        assertTrue(result is ArrivalUseCase.StartArrivalResult.Error)
        assertEquals("Pick a client first", (result as ArrivalUseCase.StartArrivalResult.Error).message)
    }

    @Test
    fun `startArrivalForSelected returns error when no location`() {
        val useCase = ArrivalUseCase(mockk())

        val result = useCase.startArrivalForSelected(
            selectedClient = testClient("1"),
            currentLocation = null
        )

        assertTrue(result is ArrivalUseCase.StartArrivalResult.Error)
        assertEquals("Unable to get current location", (result as ArrivalUseCase.StartArrivalResult.Error).message)
    }

    @Test
    fun `startArrivalForSelected returns populated arrival result`() {
        val routingEngine = mockk<RoutingEngine>()
        var nowMillis = 0L
        val useCase = ArrivalUseCase(routingEngine, nowProvider = { nowMillis })

        val client = testClient("1")
        every { routingEngine.buildClientDetails(client) } returns "details"

        nowMillis = 123_456L
        val result = useCase.startArrivalForSelected(
            selectedClient = client,
            currentLocation = ArrivalUseCase.GeoPoint(42.1, -85.1)
        )

        assertTrue(result is ArrivalUseCase.StartArrivalResult.Started)
        val arrival = (result as ArrivalUseCase.StartArrivalResult.Started).arrival
        assertEquals(client, arrival.selectedClient)
        assertEquals("details", arrival.selectedClientDetails)
        assertEquals(123_456L, arrival.arrivalStartedAtMillis)
        assertEquals(42.1, arrival.arrivalLat, 0.0)
        assertEquals(-85.1, arrival.arrivalLng, 0.0)
    }

    @Test
    fun `cancelArrival returns null when nothing to cancel`() {
        val useCase = ArrivalUseCase(mockk())
        val result = useCase.cancelArrival(arrivalStartedAtMillis = null, selectedClientName = "Acme")
        assertNull(result)
    }

    @Test
    fun `cancelArrival returns status when arrival exists`() {
        val useCase = ArrivalUseCase(mockk())
        val result = useCase.cancelArrival(arrivalStartedAtMillis = 1L, selectedClientName = "Acme")
        assertEquals("Cancelled arrival for Acme", result)
    }

    @Test
    fun `createStaleArrivalPrompt sets pending action and resolveStaleArrival consumes it`() {
        var nowMillis = 500_000L
        val useCase = ArrivalUseCase(mockk(), nowProvider = { nowMillis })

        var invoked = false
        val prompt = useCase.createStaleArrivalPrompt(
            arrivalStartedAtMillis = 380_000L,
            selectedClientName = "Client-1",
            deferredAction = { invoked = true }
        )

        assertNotNull(prompt)
        assertEquals("Client-1", prompt!!.clientName)
        assertEquals(2L, prompt.minutesElapsed)

        val resolved = useCase.resolveStaleArrival(markComplete = true, selectedClientName = "Client-1")
        assertTrue(resolved is ArrivalUseCase.ResolveStaleResult.ConfirmAndContinue)
        val action = (resolved as ArrivalUseCase.ResolveStaleResult.ConfirmAndContinue).deferredAction
        assertNotNull(action)
        action?.invoke()
        assertTrue(invoked)

        val consumed = useCase.resolveStaleArrival(markComplete = true, selectedClientName = "Client-1")
        val consumedAction = (consumed as ArrivalUseCase.ResolveStaleResult.ConfirmAndContinue).deferredAction
        assertNull(consumedAction)
    }

    @Test
    fun `resolveStaleArrival discard returns discard status and deferred action`() {
        val useCase = ArrivalUseCase(mockk())
        var invoked = false
        useCase.createStaleArrivalPrompt(
            arrivalStartedAtMillis = 100_000L,
            selectedClientName = "Client-7",
            deferredAction = { invoked = true }
        )

        val result = useCase.resolveStaleArrival(markComplete = false, selectedClientName = "Client-7")
        assertTrue(result is ArrivalUseCase.ResolveStaleResult.DiscardAndContinue)

        val discard = result as ArrivalUseCase.ResolveStaleResult.DiscardAndContinue
        assertEquals("Discarded arrival for Client-7", discard.statusMessage)
        assertNotNull(discard.deferredAction)
        discard.deferredAction?.invoke()
        assertTrue(invoked)
    }

    @Test
    fun `dropPendingStaleAction clears deferred action`() {
        val useCase = ArrivalUseCase(mockk())
        useCase.createStaleArrivalPrompt(
            arrivalStartedAtMillis = 100_000L,
            selectedClientName = "Client-9",
            deferredAction = { }
        )

        useCase.dropPendingStaleAction()

        val result = useCase.resolveStaleArrival(markComplete = true, selectedClientName = "Client-9")
        val action = (result as ArrivalUseCase.ResolveStaleResult.ConfirmAndContinue).deferredAction
        assertNull(action)
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
