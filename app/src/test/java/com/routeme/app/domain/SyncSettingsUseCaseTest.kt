package com.routeme.app.domain

import android.net.Uri
import com.routeme.app.Client
import com.routeme.app.ImportResult
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WriteBackRetryQueue
import com.routeme.app.network.GoogleSheetsSync
import com.routeme.app.network.SheetsWriteBack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncSettingsUseCaseTest {

    private lateinit var repository: ClientRepository
    private lateinit var prefs: PreferencesRepository
    private lateinit var retryQueue: WriteBackRetryQueue

    private var readUrlStore = ""
    private var writeUrlStore = ""
    private var loggingEnabledStore = true
    private var thresholdStore = 5

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        retryQueue = mockk(relaxed = true)

        every { prefs.sheetsReadUrl } answers { readUrlStore }
        every { prefs.sheetsReadUrl = any() } answers {
            readUrlStore = firstArg()
            Unit
        }

        every { prefs.sheetsWriteUrl } answers { writeUrlStore }
        every { prefs.sheetsWriteUrl = any() } answers {
            writeUrlStore = firstArg()
            Unit
        }

        every { prefs.nonClientLoggingEnabled } answers { loggingEnabledStore }
        every { prefs.nonClientLoggingEnabled = any() } answers {
            loggingEnabledStore = firstArg()
            Unit
        }

        every { prefs.nonClientStopThresholdMinutes } answers { thresholdStore }
        every { prefs.nonClientStopThresholdMinutes = any() } answers {
            thresholdStore = firstArg()
            Unit
        }

        SheetsWriteBack.webAppUrl = ""
    }

    @Test
    fun `loadClients returns success with loaded status`() = runTest {
        val useCase = SyncSettingsUseCase(repository, prefs, retryQueue)
        val clients = listOf(testClient("1"), testClient("2"))
        coEvery { repository.loadAllClients() } returns clients

        val result = useCase.loadClients()

        assertTrue(result is SyncSettingsUseCase.LoadClientsResult.Success)
        val success = result as SyncSettingsUseCase.LoadClientsResult.Success
        assertEquals(clients, success.clients)
        assertEquals("Loaded 2 client(s).", success.statusMessage)
    }

    @Test
    fun `importClients merges imported clients and sets didImport flag`() = runTest {
        val useCase = SyncSettingsUseCase(repository, prefs, retryQueue)
        val existing = listOf(testClient("1"))
        val imported = listOf(testClient("2"))
        val uri = mockk<Uri>()

        coEvery { repository.importFromUri(uri) } returns ImportResult(imported, "Imported 1")

        val result = useCase.importClients(existing, uri)

        assertTrue(result is SyncSettingsUseCase.ImportClientsResult.Success)
        val success = result as SyncSettingsUseCase.ImportClientsResult.Success
        assertEquals(2, success.clients.size)
        assertTrue(success.didImportClients)
        assertEquals("Imported 1", success.statusMessage)
    }

    @Test
    fun `syncFromSheets returns synced clients and auto geocode flag`() = runTest {
        val useCase = SyncSettingsUseCase(repository, prefs, retryQueue)
        val synced = listOf(testClient("1"))

        coEvery { repository.syncFromSheets("https://sheet") } returns GoogleSheetsSync.SyncResult(synced, "Synced")

        val result = useCase.syncFromSheets("https://sheet")

        assertTrue(result is SyncSettingsUseCase.SyncFromSheetsResult.Success)
        val success = result as SyncSettingsUseCase.SyncFromSheetsResult.Success
        assertEquals("Synced", success.statusMessage)
        assertEquals(synced, success.syncedClients)
        assertTrue(success.shouldAutoGeocode)
    }

    @Test
    fun `geocodeMissingClientCoordinates returns no-missing result when all geocoded`() = runTest {
        val useCase = SyncSettingsUseCase(repository, prefs, retryQueue)
        val clients = listOf(testClient("1"))

        val result = useCase.geocodeMissingClientCoordinates(clients)

        assertTrue(result is SyncSettingsUseCase.GeocodeResult.NoMissingCoordinates)
    }

    @Test
    fun `load and update sync settings read and write values`() {
        val useCase = SyncSettingsUseCase(repository, prefs, retryQueue)
        readUrlStore = "read-old"
        writeUrlStore = "write-old"

        val loaded = useCase.loadSyncSettings()
        assertEquals("read-old", loaded.readUrl)
        assertEquals("write-old", loaded.writeUrl)
        assertEquals("write-old", SheetsWriteBack.webAppUrl)

        val updated = useCase.updateSyncSettings("read-new", "write-new")
        assertEquals("read-new", updated.readUrl)
        assertEquals("write-new", updated.writeUrl)
        assertEquals("read-new", readUrlStore)
        assertEquals("write-new", writeUrlStore)
        assertEquals("write-new", SheetsWriteBack.webAppUrl)
    }

    @Test
    fun `toggle and threshold settings update values and status`() {
        val useCase = SyncSettingsUseCase(repository, prefs, retryQueue)

        loggingEnabledStore = false
        val toggled = useCase.toggleNonClientLogging()
        assertTrue(toggled.enabled)
        assertEquals("Non-client stop logging ON", toggled.statusMessage)

        val threshold = useCase.setNonClientStopThreshold(45)
        assertEquals(30, threshold.thresholdMinutes)
        assertEquals("Non-client stop threshold: 45min", threshold.statusMessage)
        assertEquals(30, useCase.getNonClientStopThreshold())
    }

    @Test
    fun `retryPendingWrites skips when write URL blank and drains when configured`() = runTest {
        val useCase = SyncSettingsUseCase(repository, prefs, retryQueue)

        SheetsWriteBack.webAppUrl = ""
        val skipped = useCase.retryPendingWrites()
        assertEquals(0, skipped.succeeded)

        SheetsWriteBack.webAppUrl = "https://write"
        coEvery { retryQueue.drainQueue() } returns WriteBackRetryQueue.DrainResult(3, 0, 0)

        val drained = useCase.retryPendingWrites()
        assertEquals(3, drained.succeeded)
        coVerify(exactly = 1) { retryQueue.drainQueue() }
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
            latitude = 42.2,
            longitude = -85.5,
            records = mutableListOf()
        )
    }
}
