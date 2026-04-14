package com.routeme.app.domain

import com.routeme.app.Client
import com.routeme.app.ServiceRecord
import com.routeme.app.ServiceType
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WriteBackRetryQueue
import com.routeme.app.network.SheetsWriteBack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServiceCompletionUseCaseTest {

    private lateinit var repository: ClientRepository
    private lateinit var retryQueue: WriteBackRetryQueue
    private lateinit var preferencesRepository: PreferencesRepository

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        retryQueue = mockk(relaxed = true)
        preferencesRepository = mockk(relaxed = true)
        SheetsWriteBack.webAppUrl = ""
        SheetsWriteBack.propertyWebAppUrl = ""
    }

    @Test
    fun `confirmSelectedClientService returns error when no selected client`() = runTest {
        val useCase = ServiceCompletionUseCase(repository, retryQueue, preferencesRepository)

        val result = useCase.confirmSelectedClientService(
            ServiceCompletionUseCase.ConfirmSelectedRequest(
                clients = emptyList(),
                selectedClient = null,
                arrivalStartedAtMillis = 1L,
                arrivalLat = null,
                arrivalLng = null,
                selectedSuggestionEligibleSteps = emptySet(),
                selectedServiceTypes = setOf(ServiceType.ROUND_1),
                currentLocation = null,
                visitNotes = ""
            )
        )

        assertTrue(result is ServiceCompletionUseCase.ConfirmSelectedResult.Error)
        assertEquals(
            "Pick a client first",
            (result as ServiceCompletionUseCase.ConfirmSelectedResult.Error).message
        )
    }

    @Test
    fun `confirmSelectedClientService returns error when no arrival timestamp`() = runTest {
        val useCase = ServiceCompletionUseCase(repository, retryQueue, preferencesRepository)

        val result = useCase.confirmSelectedClientService(
            ServiceCompletionUseCase.ConfirmSelectedRequest(
                clients = listOf(testClient("1")),
                selectedClient = testClient("1"),
                arrivalStartedAtMillis = null,
                arrivalLat = null,
                arrivalLng = null,
                selectedSuggestionEligibleSteps = emptySet(),
                selectedServiceTypes = setOf(ServiceType.ROUND_1),
                currentLocation = null,
                visitNotes = ""
            )
        )

        assertTrue(result is ServiceCompletionUseCase.ConfirmSelectedResult.Error)
        assertEquals(
            "Tap Arrived first",
            (result as ServiceCompletionUseCase.ConfirmSelectedResult.Error).message
        )
    }

    @Test
    fun `confirmSelectedClientService persists record and returns success`() = runTest {
        val fixedNow = 200_000L
        val useCase = ServiceCompletionUseCase(repository, retryQueue, preferencesRepository, nowProvider = { fixedNow })
        val client = testClient("10")

        coEvery { repository.saveServiceRecord(any(), any()) } returns Unit

        val result = useCase.confirmSelectedClientService(
            ServiceCompletionUseCase.ConfirmSelectedRequest(
                clients = listOf(client),
                selectedClient = client,
                arrivalStartedAtMillis = 140_000L,
                arrivalLat = 42.2,
                arrivalLng = -85.5,
                selectedSuggestionEligibleSteps = setOf(ServiceType.ROUND_2),
                selectedServiceTypes = setOf(ServiceType.ROUND_1),
                currentLocation = null,
                visitNotes = "  done note  "
            )
        )

        assertTrue(result is ServiceCompletionUseCase.ConfirmSelectedResult.Success)
        val success = result as ServiceCompletionUseCase.ConfirmSelectedResult.Success
        assertEquals(1, success.updatedClients.size)
        assertEquals(client.id, success.selectedClient.id)
        assertEquals(1, success.selectedClient.records.count { it.completedAtMillis == fixedNow })
        assertEquals("done note", success.selectedClient.records.last().notes)
        assertTrue(success.statusMessage.contains("Confirmed Step 2 for"))
        assertEquals(null, success.sheetStatusMessage)
        assertEquals(null, success.sheetSnackbarMessage)

        coVerify(exactly = 1) { repository.saveServiceRecord(client.id, any()) }
    }

    @Test
    fun `confirmSelectedClientService uses provided completion timestamp override`() = runTest {
        val fixedNow = 200_000L
        val overrideCompletedAt = 175_000L
        val useCase = ServiceCompletionUseCase(repository, retryQueue, preferencesRepository, nowProvider = { fixedNow })
        val client = testClient("11")

        coEvery { repository.saveServiceRecord(any(), any()) } returns Unit

        val result = useCase.confirmSelectedClientService(
            ServiceCompletionUseCase.ConfirmSelectedRequest(
                clients = listOf(client),
                selectedClient = client,
                arrivalStartedAtMillis = 140_000L,
                arrivalLat = 42.2,
                arrivalLng = -85.5,
                completedAtMillisOverride = overrideCompletedAt,
                selectedSuggestionEligibleSteps = setOf(ServiceType.ROUND_2),
                selectedServiceTypes = setOf(ServiceType.ROUND_1),
                currentLocation = null,
                visitNotes = ""
            )
        )

        assertTrue(result is ServiceCompletionUseCase.ConfirmSelectedResult.Success)
        val success = result as ServiceCompletionUseCase.ConfirmSelectedResult.Success
        assertEquals(overrideCompletedAt, success.selectedClient.records.last().completedAtMillis)
    }

    @Test
    fun `confirmClusterService confirms selected members`() = runTest {
        val fixedNow = 500_000L
        val useCase = ServiceCompletionUseCase(repository, retryQueue, preferencesRepository, nowProvider = { fixedNow })
        val client = testClient("20")

        coEvery { repository.saveServiceRecord(any(), any()) } returns Unit

        val result = useCase.confirmClusterService(
            ServiceCompletionUseCase.ConfirmClusterRequest(
                clients = listOf(client),
                selectedServiceTypes = setOf(ServiceType.ROUND_1),
                suggestionEligibleStepsByClientId = mapOf(client.id to setOf(ServiceType.ROUND_1)),
                selectedMembers = listOf(
                    ServiceCompletionUseCase.ClusterMemberInput(
                        clientId = client.id,
                        clientName = client.name,
                        arrivedAtMillis = 440_000L,
                        completedAtMillis = 500_000L,
                        location = ServiceCompletionUseCase.GeoPoint(42.1, -85.1)
                    )
                )
            )
        )

        assertTrue(result is ServiceCompletionUseCase.ConfirmClusterResult.Success)
        val success = result as ServiceCompletionUseCase.ConfirmClusterResult.Success
        assertEquals(listOf(client.name), success.confirmedNames)
        assertEquals(listOf(client.id), success.confirmedIds)
        assertTrue(success.statusMessage.contains("1 stops"))
        coVerify(exactly = 1) { repository.saveServiceRecord(client.id, any()) }
    }

    @Test
    fun `undoLastConfirmation deletes persisted record and removes in-memory record`() = runTest {
        val useCase = ServiceCompletionUseCase(repository, retryQueue, preferencesRepository)
        val completedAt = 123_456L
        val client = testClient("30").copy(
            records = listOf(
                ServiceRecord(
                    serviceType = ServiceType.ROUND_1,
                    completedAtMillis = completedAt,
                    durationMinutes = 15,
                    lat = null,
                    lng = null
                )
            )
        )

        coEvery { repository.deleteServiceRecord(client.id, completedAt) } returns Unit

        val result = useCase.undoLastConfirmation(listOf(client), client.id, completedAt)

        assertTrue(result is ServiceCompletionUseCase.UndoLastResult.Success)
        val success = result as ServiceCompletionUseCase.UndoLastResult.Success
        assertTrue(success.updatedClient?.records?.none { it.completedAtMillis == completedAt } == true)
        coVerify(exactly = 1) { repository.deleteServiceRecord(client.id, completedAt) }
    }

    @Test
    fun `editSelectedClientNotes returns editor payload`() {
        val useCase = ServiceCompletionUseCase(repository, retryQueue, preferencesRepository)
        val client = testClient("40").copy(notes = "Needs gate code")

        val result = useCase.editSelectedClientNotes(client)

        assertTrue(result is ServiceCompletionUseCase.EditNotesResult.OpenEditor)
        val payload = result as ServiceCompletionUseCase.EditNotesResult.OpenEditor
        assertEquals(client.id, payload.clientId)
        assertEquals(client.name, payload.clientName)
        assertEquals("Needs gate code", payload.currentNotes)
    }

    @Test
    fun `saveClientNotes updates client notes and returns updated selected client`() = runTest {
        val useCase = ServiceCompletionUseCase(repository, retryQueue, preferencesRepository)
        val client = testClient("50")

        coEvery { repository.updateClientNotes(client.id, "trimmed") } returns Unit

        val result = useCase.saveClientNotes(
            ServiceCompletionUseCase.SaveNotesRequest(
                clients = listOf(client),
                currentSelectedClientId = client.id,
                clientId = client.id,
                notes = "  trimmed  "
            )
        )

        assertTrue(result is ServiceCompletionUseCase.SaveNotesResult.Success)
        val success = result as ServiceCompletionUseCase.SaveNotesResult.Success
        assertEquals("trimmed", success.updatedSelectedClient?.notes)
        assertEquals("Notes saved for ${client.name}", success.savedStatusMessage)
        assertEquals(null, success.syncedStatusMessage)

        coVerify(exactly = 1) { repository.updateClientNotes(client.id, "trimmed") }
    }

    private fun testClient(id: String): Client {
        return Client(
            id = id,
            name = "Client-$id",
            address = "123 Test St",
            zone = "KAL",
            notes = "",
            subscribedSteps = setOf(1, 2, 3),
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
