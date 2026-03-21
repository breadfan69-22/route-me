package com.routeme.app.domain

import android.net.Uri
import com.routeme.app.Client
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WriteBackRetryQueue
import com.routeme.app.network.SheetsWriteBack

class SyncSettingsUseCase(
    private val clientRepository: ClientRepository,
    private val preferencesRepository: PreferencesRepository,
    private val retryQueue: WriteBackRetryQueue
) {
    data class SyncSettings(
        val readUrl: String,
        val writeUrl: String
    )

    data class ToggleResult(
        val enabled: Boolean,
        val statusMessage: String
    )

    data class ThresholdResult(
        val thresholdMinutes: Int,
        val statusMessage: String
    )

    data class RetryResult(
        val succeeded: Int
    )

    sealed interface LoadClientsResult {
        data class Success(
            val clients: List<Client>,
            val statusMessage: String
        ) : LoadClientsResult

        data class Error(val message: String) : LoadClientsResult
    }

    sealed interface ImportClientsResult {
        data class Success(
            val clients: List<Client>,
            val statusMessage: String,
            val didImportClients: Boolean
        ) : ImportClientsResult

        data class Error(val message: String) : ImportClientsResult
    }

    sealed interface SyncFromSheetsResult {
        data class Success(
            val statusMessage: String,
            val syncedClients: List<Client>?,
            val shouldAutoGeocode: Boolean
        ) : SyncFromSheetsResult

        data class Error(val message: String) : SyncFromSheetsResult
    }

    sealed interface GeocodeResult {
        data class Success(
            val clients: List<Client>,
            val statusMessage: String
        ) : GeocodeResult

        data class NoMissingCoordinates(val statusMessage: String) : GeocodeResult

        data class Error(val message: String) : GeocodeResult
    }

    suspend fun loadClients(): LoadClientsResult {
        return try {
            val clients = clientRepository.loadAllClients()
            val status = if (clients.isEmpty()) {
                "No clients found. Import or sync to begin."
            } else {
                "Loaded ${clients.size} client(s)."
            }
            LoadClientsResult.Success(clients = clients, statusMessage = status)
        } catch (e: Exception) {
            LoadClientsResult.Error("Load failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun importClients(existingClients: List<Client>, uri: Uri): ImportClientsResult {
        return try {
            val importResult = clientRepository.importFromUri(uri)
            val didImport = importResult.clients.isNotEmpty()
            val merged = if (didImport) {
                existingClients + importResult.clients
            } else {
                existingClients
            }
            ImportClientsResult.Success(
                clients = merged,
                statusMessage = importResult.message,
                didImportClients = didImport
            )
        } catch (e: Exception) {
            ImportClientsResult.Error("Import failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun syncFromSheets(url: String): SyncFromSheetsResult {
        return try {
            val syncResult = clientRepository.syncFromSheets(url)
            val didSyncClients = syncResult.clients.isNotEmpty()
            SyncFromSheetsResult.Success(
                statusMessage = syncResult.message,
                syncedClients = if (didSyncClients) syncResult.clients else null,
                shouldAutoGeocode = didSyncClients
            )
        } catch (e: Exception) {
            SyncFromSheetsResult.Error("Sync failed: ${e.message ?: "Unknown error"}")
        }
    }

    fun missingCoordinatesCount(clients: List<Client>): Int {
        return clients.count { it.latitude == null || it.longitude == null }
    }

    suspend fun geocodeMissingClientCoordinates(clients: List<Client>): GeocodeResult {
        val withoutCoordsCount = missingCoordinatesCount(clients)
        if (withoutCoordsCount == 0) {
            return GeocodeResult.NoMissingCoordinates("All clients already have coordinates.")
        }

        return try {
            val geocodeResult = clientRepository.geocodeClients(clients)
            GeocodeResult.Success(clients = geocodeResult.clients, statusMessage = geocodeResult.message)
        } catch (e: Exception) {
            GeocodeResult.Error("Geocoding failed: ${e.message ?: "Unknown error"}")
        }
    }

    fun loadSyncSettings(): SyncSettings {
        val readUrl = preferencesRepository.sheetsReadUrl
        val writeUrl = preferencesRepository.sheetsWriteUrl
        val propertyWriteUrl = preferencesRepository.propertySheetWriteUrl
        SheetsWriteBack.webAppUrl = writeUrl
        SheetsWriteBack.propertyWebAppUrl = propertyWriteUrl
        return SyncSettings(readUrl = readUrl, writeUrl = writeUrl)
    }

    fun updateSyncSettings(readUrl: String, writeUrl: String): SyncSettings {
        preferencesRepository.sheetsReadUrl = readUrl
        preferencesRepository.sheetsWriteUrl = writeUrl
        SheetsWriteBack.webAppUrl = writeUrl
        SheetsWriteBack.propertyWebAppUrl = preferencesRepository.propertySheetWriteUrl
        return SyncSettings(readUrl = readUrl, writeUrl = writeUrl)
    }

    fun isNonClientLoggingEnabled(): Boolean = preferencesRepository.nonClientLoggingEnabled

    fun toggleNonClientLogging(): ToggleResult {
        val enabled = !preferencesRepository.nonClientLoggingEnabled
        preferencesRepository.nonClientLoggingEnabled = enabled
        return ToggleResult(
            enabled = enabled,
            statusMessage = if (enabled) "Non-client stop logging ON" else "Non-client stop logging OFF"
        )
    }

    fun getNonClientStopThreshold(): Int = preferencesRepository.nonClientStopThresholdMinutes

    fun setNonClientStopThreshold(minutes: Int): ThresholdResult {
        val threshold = minutes.coerceIn(1, 30)
        preferencesRepository.nonClientStopThresholdMinutes = threshold
        return ThresholdResult(
            thresholdMinutes = threshold,
            statusMessage = "Non-client stop threshold: ${minutes}min"
        )
    }

    suspend fun retryPendingWrites(): RetryResult {
        if (SheetsWriteBack.webAppUrl.isBlank()) {
            return RetryResult(succeeded = 0)
        }

        return try {
            RetryResult(succeeded = retryQueue.drainQueue().succeeded)
        } catch (_: Exception) {
            RetryResult(succeeded = 0)
        }
    }
}
