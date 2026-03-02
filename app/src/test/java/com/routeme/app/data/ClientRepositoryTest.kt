package com.routeme.app.data

import android.content.Context
import com.routeme.app.Client
import com.routeme.app.ClientDao
import com.routeme.app.ClientEntity
import com.routeme.app.ClientWithRecords
import com.routeme.app.DistanceMatrixHelper
import com.routeme.app.GoogleSheetsSync
import com.routeme.app.NonClientStopDao
import com.routeme.app.ServiceRecord
import com.routeme.app.ServiceRecordEntity
import com.routeme.app.ServiceType
import com.routeme.app.SheetsWriteBack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientRepositoryTest {
    private lateinit var appContext: Context
    private lateinit var dao: ClientDao
    private lateinit var nonClientStopDao: NonClientStopDao
    private lateinit var repository: ClientRepository

    @Before
    fun setup() {
        appContext = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        nonClientStopDao = mockk(relaxed = true)
        repository = ClientRepository(appContext, dao, nonClientStopDao)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `loadAllClients maps entities to domain`() = runTest {
        coEvery { dao.getAllClientsWithRecords() } returns listOf(
            ClientWithRecords(
                client = ClientEntity(
                    id = "1",
                    name = "Test",
                    address = "123 St",
                    zone = "KAL",
                    notes = "",
                    subscribedSteps = "1,2",
                    hasGrub = false,
                    mowDayOfWeek = 0,
                    lawnSizeSqFt = 0,
                    sunShade = "",
                    terrain = "",
                    windExposure = "",
                    cuSpringPending = false,
                    cuFallPending = false,
                    latitude = 42.0,
                    longitude = -85.0
                ),
                records = listOf(
                    ServiceRecordEntity(
                        recordId = 1,
                        clientId = "1",
                        serviceType = ServiceType.ROUND_1.name,
                        completedAtMillis = System.currentTimeMillis(),
                        durationMinutes = 20,
                        lat = null,
                        lng = null
                    )
                )
            )
        )

        val clients = repository.loadAllClients()

        assertEquals(1, clients.size)
        assertEquals("1", clients.first().id)
        assertEquals(1, clients.first().records.size)
    }

    @Test
    fun `saveClients inserts clients and records`() = runTest {
        val client = testClient("1")

        repository.saveClients(listOf(client))

        coVerify { dao.insertClient(any()) }
        coVerify { dao.insertServiceRecord(any()) }
    }

    @Test
    fun `saveServiceRecord delegates to dao`() = runTest {
        val record = ServiceRecord(serviceType = ServiceType.ROUND_1, completedAtMillis = System.currentTimeMillis(), durationMinutes = 15, lat = null, lng = null)

        repository.saveServiceRecord("abc", record)

        coVerify { dao.insertServiceRecord(any()) }
    }

    @Test
    fun `syncFromSheets replaces db and returns result`() = runTest {
        mockkObject(GoogleSheetsSync)
        val syncedClient = testClient("sync-1")
        every { GoogleSheetsSync.fetch("url") } returns GoogleSheetsSync.SyncResult(
            clients = listOf(syncedClient),
            message = "ok"
        )

        val result = repository.syncFromSheets("url")

        assertEquals(1, result.clients.size)
        coVerify { dao.deleteAllClients() }
        coVerify { dao.insertClient(any()) }
    }

    @Test
    fun `writeBackServiceCompletion delegates to SheetsWriteBack`() = runTest {
        mockkObject(SheetsWriteBack)
        every {
            SheetsWriteBack.markDone("A", ServiceType.ROUND_1, any())
        } returns SheetsWriteBack.WriteResult(success = true, message = "ok")

        val result = repository.writeBackServiceCompletion("A", ServiceType.ROUND_1, 123L)

        assertTrue(result.success)
    }

    @Test
    fun `fetchDrivingTimes delegates to distance matrix helper`() = runTest {
        mockkObject(DistanceMatrixHelper)
        val client = testClient("2")
        every {
            DistanceMatrixHelper.fetchDrivingTimes(any(), any(), any())
        } returns listOf(
            DistanceMatrixHelper.DrivingInfo(
                clientId = "2",
                distanceMeters = 1000,
                distanceText = "0.6 mi",
                durationSeconds = 120,
                durationText = "2 mins"
            )
        )

        val result = repository.fetchDrivingTimes(42.0, -85.0, listOf(client))

        assertEquals(1, result.size)
        assertEquals("2", result.first().clientId)
    }

    private fun testClient(id: String): Client {
        return Client(
            id = id,
            name = "Client-$id",
            address = "123 St",
            zone = "KAL",
            notes = "",
            subscribedSteps = setOf(1),
            hasGrub = false,
            mowDayOfWeek = 0,
            lawnSizeSqFt = 0,
            sunShade = "",
            terrain = "",
            windExposure = "",
            latitude = 42.0,
            longitude = -85.0,
            records = mutableListOf(
                ServiceRecord(serviceType = ServiceType.ROUND_1, completedAtMillis = System.currentTimeMillis(), durationMinutes = 20, lat = null, lng = null)
            )
        )
    }
}
