package com.routeme.app.ui

import app.cash.turbine.test
import com.routeme.app.Client
import com.routeme.app.ClientSuggestion
import com.routeme.app.RouteDirection
import com.routeme.app.ServiceRecord
import com.routeme.app.ServiceType
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WriteBackRetryQueue
import com.routeme.app.domain.RoutingEngine
import com.routeme.app.network.SheetsWriteBack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.lifecycle.SavedStateHandle

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: ClientRepository
    private lateinit var prefs: PreferencesRepository
    private lateinit var routingEngine: RoutingEngine
    private lateinit var retryQueue: WriteBackRetryQueue

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        routingEngine = mockk(relaxed = true)
        retryQueue = mockk(relaxed = true)

        every { prefs.sheetsReadUrl } returns ""
        every { prefs.sheetsWriteUrl } returns ""
        SheetsWriteBack.webAppUrl = ""
    }

    @Test
    fun `init loads clients into state`() = runTest {
        val client = testClient("1")
        coEvery { repository.loadAllClients() } returns listOf(client)
        every { routingEngine.buildClientDetails(any()) } returns "details"

        val vm = MainViewModel(repository, prefs, routingEngine, SavedStateHandle(), retryQueue)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.clients.size)
        assertEquals("1", vm.uiState.value.clients.first().id)
    }

    @Test
    fun `toggleCuOverride flips value`() = runTest {
        coEvery { repository.loadAllClients() } returns emptyList()
        val vm = MainViewModel(repository, prefs, routingEngine, SavedStateHandle(), retryQueue)
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.cuOverrideEnabled)
        vm.toggleCuOverride()
        assertEquals(true, vm.uiState.value.cuOverrideEnabled)
    }

    @Test
    fun `suggestNextClients updates suggestions and selected client`() = runTest {
        val client = testClient("10")
        val suggestion = ClientSuggestion(
            client = client,
            daysSinceLast = 30,
            distanceMiles = 1.0,
            distanceToShopMiles = 1.0,
            mowWindowPreferred = true
        )

        coEvery { repository.loadAllClients() } returns listOf(client)
        every {
            routingEngine.rankClients(any(), any(), any(), any(), any(), any())
        } returns listOf(suggestion)
        every { routingEngine.buildClientDetails(client) } returns "client-details"
        coEvery { repository.fetchDrivingTimes(any(), any(), any()) } returns emptyList()

        val vm = MainViewModel(repository, prefs, routingEngine, SavedStateHandle(), retryQueue)
        advanceUntilIdle()

        vm.suggestNextClients(null)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.suggestions.size)
        assertEquals("10", vm.uiState.value.selectedClient?.id)
        assertEquals("client-details", vm.uiState.value.selectedClientDetails)
    }

    @Test
    fun `confirmSelectedClientService requires arrival before save`() = runTest {
        val client = testClient("11")
        val suggestion = ClientSuggestion(
            client = client,
            daysSinceLast = 45,
            distanceMiles = 1.0,
            distanceToShopMiles = 1.2,
            mowWindowPreferred = true
        )

        coEvery { repository.loadAllClients() } returns listOf(client)
        every {
            routingEngine.rankClients(any(), any(), any(), any(), any(), any())
        } returns listOf(suggestion)
        every { routingEngine.buildClientDetails(any()) } returns "client"
        coEvery { repository.fetchDrivingTimes(any(), any(), any()) } returns emptyList()
        coEvery { repository.saveServiceRecord(any(), any()) } returns Unit

        val vm = MainViewModel(repository, prefs, routingEngine, SavedStateHandle(), retryQueue)
        advanceUntilIdle()
        vm.suggestNextClients(null)
        advanceUntilIdle()

        vm.events.test {
            vm.confirmSelectedClientService(null)
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is MainEvent.ShowSnackbar)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { repository.saveServiceRecord(any(), any()) }
    }

    @Test
    fun `exportTopRouteToMaps emits open maps event with route URI`() = runTest {
        val clients = listOf(testClient("21"), testClient("22"))
        val suggestions = clients.mapIndexed { index, client ->
            ClientSuggestion(
                client = client,
                daysSinceLast = 30 + index,
                distanceMiles = 1.0 + index,
                distanceToShopMiles = 1.0 + index,
                mowWindowPreferred = true
            )
        }

        coEvery { repository.loadAllClients() } returns clients
        every { routingEngine.rankClients(any(), any(), any(), any(), any(), any()) } returns suggestions
        every { routingEngine.buildClientDetails(any()) } returns "details"

        val vm = MainViewModel(repository, prefs, routingEngine, SavedStateHandle(), retryQueue)
        advanceUntilIdle()
        vm.suggestNextClients(null)
        advanceUntilIdle()

        val export = vm.buildTopRouteExportForTests()
        assertTrue(export != null)
        val uri = export!!.first
        assertTrue(uri.contains("https://www.google.com/maps/dir/"))
        assertTrue(uri.contains("api=1"))
        assertTrue(uri.contains("travelmode=driving"))
        assertTrue(uri.contains("origin="))
        assertTrue(uri.contains("destination="))
        assertEquals(2, export.second)
    }

    @Test
    fun `exportTopRouteToMaps clips stops when exceeding waypoint limit`() = runTest {
        val clients = (1..12).map { idx ->
            testClient("C$idx").copy(
                id = "C$idx",
                name = "Client-C$idx",
                latitude = 42.2478 + (idx / 1000.0),
                longitude = -85.564 - (idx / 1000.0)
            )
        }
        val suggestions = clients.mapIndexed { index, client ->
            ClientSuggestion(
                client = client,
                daysSinceLast = 20 + index,
                distanceMiles = 1.0 + index,
                distanceToShopMiles = 2.0 + index,
                mowWindowPreferred = true
            )
        }

        coEvery { repository.loadAllClients() } returns clients
        every { routingEngine.rankClients(any(), any(), any(), any(), any(), any()) } returns suggestions
        every { routingEngine.buildClientDetails(any()) } returns "details"

        val vm = MainViewModel(repository, prefs, routingEngine, SavedStateHandle(), retryQueue)
        advanceUntilIdle()
        vm.suggestNextClients(null)
        advanceUntilIdle()

        val export = vm.buildTopRouteExportForTests()
        assertTrue(export != null)
        assertTrue(export!!.first.contains("https://www.google.com/maps/dir/"))
        assertEquals(9, export.second)
    }

    @Test
    fun `exportTopRouteToMaps homeward ends at shop destination`() = runTest {
        val clients = listOf(testClient("31"), testClient("32"))
        val suggestions = clients.mapIndexed { index, client ->
            ClientSuggestion(
                client = client,
                daysSinceLast = 25 + index,
                distanceMiles = 1.0 + index,
                distanceToShopMiles = 1.5 + index,
                mowWindowPreferred = true
            )
        }

        coEvery { repository.loadAllClients() } returns clients
        every { routingEngine.rankClients(any(), any(), any(), any(), any(), any()) } returns suggestions
        every { routingEngine.buildClientDetails(any()) } returns "details"

        val vm = MainViewModel(repository, prefs, routingEngine, SavedStateHandle(), retryQueue)
        advanceUntilIdle()
        vm.toggleRouteDirection() // OUTWARD -> HOMEWARD
        vm.suggestNextClients(null)
        advanceUntilIdle()

        val export = vm.buildTopRouteExportForTests()
        assertTrue(export != null)
        val uri = export!!.first
        assertTrue(uri.contains("destination=42.2478%2C-85.564"))
    }

    private fun testClient(id: String): Client {
        return Client(
            id = id,
            name = "Client-$id",
            address = "123 Test",
            zone = "KAL",
            notes = "",
            subscribedSteps = setOf(1, 2, 3, 4, 5, 6),
            hasGrub = false,
            mowDayOfWeek = 0,
            lawnSizeSqFt = 0,
            sunShade = "",
            terrain = "",
            windExposure = "",
            latitude = 42.2478,
            longitude = -85.564,
            records = mutableListOf(
                ServiceRecord(serviceType = ServiceType.ROUND_1, completedAtMillis = System.currentTimeMillis() - 40L * DAY_MS, durationMinutes = 20, lat = null, lng = null)
            )
        )
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
