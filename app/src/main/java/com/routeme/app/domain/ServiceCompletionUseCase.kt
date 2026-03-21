package com.routeme.app.domain

import com.routeme.app.Client
import com.routeme.app.ClientStopStatus
import com.routeme.app.PropertyInput
import com.routeme.app.ServiceRecord
import com.routeme.app.ServiceType
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.PreferencesRepository
import com.routeme.app.data.WriteBackRetryQueue
import com.routeme.app.estimateGranularSqFt
import com.routeme.app.estimateSpraySqFt
import com.routeme.app.isSpray
import com.routeme.app.network.SheetsWriteBack
import java.util.Calendar

class ServiceCompletionUseCase(
    private val clientRepository: ClientRepository,
    private val retryQueue: WriteBackRetryQueue,
    private val preferencesRepository: PreferencesRepository,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    data class GeoPoint(
        val latitude: Double,
        val longitude: Double
    )

    data class ClusterMemberInput(
        val clientId: String,
        val clientName: String,
        val arrivedAtMillis: Long,
        val location: GeoPoint,
        val weatherTempF: Int? = null,
        val weatherWindMph: Int? = null,
        val weatherDesc: String? = null
    )

    data class ConfirmSelectedRequest(
        val clients: List<Client>,
        val selectedClient: Client?,
        val arrivalStartedAtMillis: Long?,
        val arrivalLat: Double?,
        val arrivalLng: Double?,
        val weatherTempF: Int? = null,
        val weatherWindMph: Int? = null,
        val weatherDesc: String? = null,
        val selectedSuggestionEligibleSteps: Set<ServiceType>,
        val selectedServiceTypes: Set<ServiceType>,
        val currentLocation: GeoPoint?,
        val visitNotes: String,
        val amountUsed: Double? = null,
        val amountUsed2: Double? = null,
        val property: PropertyInput = PropertyInput()
    )

    sealed class ConfirmSelectedResult {
        data class Success(
            val updatedClients: List<Client>,
            val selectedClient: Client,
            val finishedAt: Long,
            val durationMinutes: Long,
            val statusMessage: String,
            val sheetStatusMessage: String?,
            val sheetSnackbarMessage: String?,
            val retryDrainSucceeded: Int
        ) : ConfirmSelectedResult()

        data class Error(val message: String) : ConfirmSelectedResult()
    }

    data class ConfirmClusterRequest(
        val clients: List<Client>,
        val selectedServiceTypes: Set<ServiceType>,
        val suggestionEligibleStepsByClientId: Map<String, Set<ServiceType>>,
        val selectedMembers: List<ClusterMemberInput>
    )

    sealed class ConfirmClusterResult {
        data class Success(
            val updatedClients: List<Client>,
            val confirmedNames: List<String>,
            val confirmedIds: List<String>,
            val finishedAt: Long,
            val statusMessage: String,
            val transientFailureMessages: List<String>
        ) : ConfirmClusterResult()

        data class Error(val message: String) : ConfirmClusterResult()
    }

    sealed class UndoLastResult {
        data class Success(
            val updatedClients: List<Client>,
            val updatedClient: Client?
        ) : UndoLastResult()

        data class Error(val message: String) : UndoLastResult()
    }

    sealed class UndoClusterResult {
        data class Success(val updatedClients: List<Client>) : UndoClusterResult()
        data class Error(val message: String) : UndoClusterResult()
    }

    sealed class EditNotesResult {
        data class OpenEditor(
            val clientId: String,
            val clientName: String,
            val currentNotes: String
        ) : EditNotesResult()

        data class Error(val message: String) : EditNotesResult()
    }

    data class SaveNotesRequest(
        val clients: List<Client>,
        val currentSelectedClientId: String?,
        val clientId: String,
        val notes: String
    )

    sealed class SaveNotesResult {
        data class Success(
            val updatedClients: List<Client>,
            val updatedSelectedClient: Client?,
            val savedStatusMessage: String,
            val syncedStatusMessage: String?,
            val retryDrainSucceeded: Int
        ) : SaveNotesResult()

        data class Error(val message: String) : SaveNotesResult()
    }

    suspend fun confirmSelectedClientService(request: ConfirmSelectedRequest): ConfirmSelectedResult {
        val client = request.selectedClient ?: return ConfirmSelectedResult.Error("Pick a client first")
        val arrivalStartedAtMillis = request.arrivalStartedAtMillis
            ?: return ConfirmSelectedResult.Error("Tap Arrived first")

        val finishedAt = nowProvider()
        val durationMinutes = (((finishedAt - arrivalStartedAtMillis) / 60000.0).toLong()).coerceAtLeast(1)

        val stepsToConfirm = if (request.selectedSuggestionEligibleSteps.isNotEmpty()) {
            request.selectedSuggestionEligibleSteps
        } else {
            setOf(request.selectedServiceTypes.first())
        }

        val trimmedNotes = request.visitNotes.trim()
        var updatedClient = client

        for (serviceType in stepsToConfirm) {
            val record = ServiceRecord(
                serviceType = serviceType,
                arrivedAtMillis = arrivalStartedAtMillis,
                completedAtMillis = finishedAt,
                durationMinutes = durationMinutes,
                lat = request.arrivalLat ?: request.currentLocation?.latitude,
                lng = request.arrivalLng ?: request.currentLocation?.longitude,
                notes = trimmedNotes,
                amountUsed = request.amountUsed,
                amountUsed2 = request.amountUsed2
            )

            updatedClient = updatedClient.copy(records = updatedClient.records + record)
            try {
                clientRepository.saveServiceRecord(client.id, record)
            } catch (e: Exception) {
                return ConfirmSelectedResult.Error("Save record failed: ${e.message ?: "Unknown error"}")
            }
        }

        val updatedClients = request.clients.map { if (it.id == updatedClient.id) updatedClient else it }
        val stepsLabel = stepsToConfirm.joinToString("+") { it.label }
        val statusMsg = "Confirmed $stepsLabel for ${updatedClient.name} at ${DateUtilsBridge.formatTimestamp(finishedAt)} (${durationMinutes}m)"

        val stopLat = request.arrivalLat ?: request.currentLocation?.latitude
        val stopLng = request.arrivalLng ?: request.currentLocation?.longitude

        runCatching {
            clientRepository.saveClientStopEvent(
                clientId = updatedClient.id,
                clientName = updatedClient.name,
                arrivedAtMillis = arrivalStartedAtMillis,
                endedAtMillis = finishedAt,
                durationMinutes = durationMinutes,
                status = ClientStopStatus.DONE,
                serviceTypes = stepsToConfirm,
                notes = trimmedNotes,
                lat = stopLat,
                lng = stopLng,
                weatherTempF = request.weatherTempF,
                weatherWindMph = request.weatherWindMph,
                weatherDesc = request.weatherDesc
            )
        }

        var sheetStatusMessage: String? = null
        var sheetSnackbarMessage: String? = null
        var retryDrainSucceeded = 0

        if (SheetsWriteBack.webAppUrl.isNotBlank()) {
            for (serviceType in stepsToConfirm) {
                try {
                    val result = clientRepository.writeBackServiceCompletion(updatedClient.name, serviceType, finishedAt)
                    if (result.success) {
                        retryDrainSucceeded += drainRetryQueueSucceededCount()
                    } else {
                        retryQueue.enqueue(updatedClient.name, serviceTypeToColumn(serviceType), "√${formatCheckValue(finishedAt)}")
                    }
                } catch (_: Exception) {
                    retryQueue.enqueue(updatedClient.name, serviceTypeToColumn(serviceType), "√${formatCheckValue(finishedAt)}")
                }
            }

            if (stepsToConfirm.size > 1) {
                sheetSnackbarMessage = "Sheet updated for ${stepsToConfirm.size} steps"
            } else {
                sheetStatusMessage = "Sheet updated. $statusMsg"
            }
        }

        // Estimate lawn size from product usage
        val estimatedSqFt = calculateEstimatedSqFt(
            request.amountUsed, request.amountUsed2,
            stepsToConfirm.first()
        )
        if (estimatedSqFt != null && estimatedSqFt in 1_000..200_000) {
            runCatching {
                clientRepository.updateClientLawnSize(client.id, estimatedSqFt)
            }
            if (SheetsWriteBack.propertyWebAppUrl.isNotBlank()) {
                runCatching {
                    clientRepository.writeBackPropertyRaw(
                        updatedClient.name, "Lawn Size", estimatedSqFt.toString()
                    )
                }
            }
        }

        // Write property stats to property sheet
        val prop = request.property
        if (prop.hasAnyData && SheetsWriteBack.propertyWebAppUrl.isNotBlank()) {
            val name = updatedClient.name
            val address = updatedClient.address
            
            // Ensure row exists first (idempotent — skips if already exists)
            val rowResult = runCatching { clientRepository.writeBackAddPropertyClientRow(name, address) }.getOrNull()
            if (rowResult != null && !rowResult.success) {
                android.util.Log.w("ServiceCompletion", "Failed to ensure property row: ${rowResult.message}")
            }
            
            if (prop.sunShade.isNotEmpty()) {
                val r = runCatching { clientRepository.writeBackPropertyRaw(name, "Sun/Shade", prop.sunShade) }.getOrNull()
                if (r != null && !r.success) android.util.Log.w("ServiceCompletion", "Sun/Shade write failed: ${r.message}")
            }
            if (prop.windExposure.isNotEmpty()) {
                val r = runCatching { clientRepository.writeBackPropertyRaw(name, "Wind Exposure", prop.windExposure) }.getOrNull()
                if (r != null && !r.success) android.util.Log.w("ServiceCompletion", "Wind Exposure write failed: ${r.message}")
            }
            if (prop.steepSlopes.isNotEmpty()) {
                val r = runCatching { clientRepository.writeBackPropertyRaw(name, "Steep Slopes", prop.steepSlopes) }.getOrNull()
                if (r != null && !r.success) android.util.Log.w("ServiceCompletion", "Steep Slopes write failed: ${r.message}")
            }
            if (prop.irrigation.isNotEmpty()) {
                val r = runCatching { clientRepository.writeBackPropertyRaw(name, "Irrigation", prop.irrigation) }.getOrNull()
                if (r != null && !r.success) android.util.Log.w("ServiceCompletion", "Irrigation write failed: ${r.message}")
            }
            val cal = Calendar.getInstance()
            val dateStr = "%d-%02d-%02d".format(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
            runCatching { clientRepository.writeBackPropertyRaw(name, "Last Updated", dateStr) }
        }

        return ConfirmSelectedResult.Success(
            updatedClients = updatedClients,
            selectedClient = updatedClient,
            finishedAt = finishedAt,
            durationMinutes = durationMinutes,
            statusMessage = statusMsg,
            sheetStatusMessage = sheetStatusMessage,
            sheetSnackbarMessage = sheetSnackbarMessage,
            retryDrainSucceeded = retryDrainSucceeded
        )
    }

    suspend fun confirmClusterService(request: ConfirmClusterRequest): ConfirmClusterResult {
        val serviceTypes = request.selectedServiceTypes
        val finishedAt = nowProvider()
        val confirmedNames = mutableListOf<String>()
        val transientFailures = mutableListOf<String>()
        var updatedClients = request.clients

        for (member in request.selectedMembers) {
            val client = updatedClients.find { it.id == member.clientId } ?: continue
            val durationMinutes = ((finishedAt - member.arrivedAtMillis) / 60_000L).coerceAtLeast(1)
            var updatedClient = client
            var savedAnyRecord = false

            val stepsForClient = request.suggestionEligibleStepsByClientId[client.id]
                ?.takeIf { it.isNotEmpty() }
                ?: setOf(serviceTypes.first())

            for (serviceType in stepsForClient) {
                val record = ServiceRecord(
                    serviceType = serviceType,
                    arrivedAtMillis = member.arrivedAtMillis,
                    completedAtMillis = finishedAt,
                    durationMinutes = durationMinutes,
                    lat = member.location.latitude,
                    lng = member.location.longitude,
                    notes = ""
                )

                updatedClient = updatedClient.copy(records = updatedClient.records + record)
                try {
                    clientRepository.saveServiceRecord(updatedClient.id, record)
                    savedAnyRecord = true
                } catch (e: Exception) {
                    transientFailures += "Save failed for ${updatedClient.name}: ${e.message ?: "Unknown"}"
                    continue
                }

                if (SheetsWriteBack.webAppUrl.isNotBlank()) {
                    try {
                        val result = clientRepository.writeBackServiceCompletion(updatedClient.name, serviceType, finishedAt)
                        if (!result.success) {
                            retryQueue.enqueue(updatedClient.name, serviceTypeToColumn(serviceType), "√${formatCheckValue(finishedAt)}")
                        }
                    } catch (_: Exception) {
                        retryQueue.enqueue(updatedClient.name, serviceTypeToColumn(serviceType), "√${formatCheckValue(finishedAt)}")
                    }
                }
            }

            if (savedAnyRecord) {
                val mLat = member.location.latitude
                val mLng = member.location.longitude
                runCatching {
                    clientRepository.saveClientStopEvent(
                        clientId = updatedClient.id,
                        clientName = updatedClient.name,
                        arrivedAtMillis = member.arrivedAtMillis,
                        endedAtMillis = finishedAt,
                        durationMinutes = durationMinutes,
                        status = ClientStopStatus.DONE,
                        serviceTypes = stepsForClient,
                        lat = mLat,
                        lng = mLng,
                        weatherTempF = member.weatherTempF,
                        weatherWindMph = member.weatherWindMph,
                        weatherDesc = member.weatherDesc
                    )
                }
            }

            confirmedNames.add(updatedClient.name)
            updatedClients = updatedClients.map { if (it.id == updatedClient.id) updatedClient else it }
        }

        val confirmedIds = request.selectedMembers
            .filter { confirmedNames.contains(it.clientName) }
            .map { it.clientId }

        val stepsLabel = serviceTypes.joinToString("+") { it.label }
        val msg = "Confirmed $stepsLabel for ${confirmedNames.joinToString(", ")} (${confirmedNames.size} stops)"

        return ConfirmClusterResult.Success(
            updatedClients = updatedClients,
            confirmedNames = confirmedNames,
            confirmedIds = confirmedIds,
            finishedAt = finishedAt,
            statusMessage = msg,
            transientFailureMessages = transientFailures
        )
    }

    suspend fun undoLastConfirmation(
        clients: List<Client>,
        clientId: String,
        completedAtMillis: Long
    ): UndoLastResult {
        return try {
            clientRepository.deleteServiceRecord(clientId, completedAtMillis)
            val client = clients.find { it.id == clientId }
            val updatedClient = client?.copy(
                records = client.records.filterNot { it.completedAtMillis == completedAtMillis }
            )
            val updatedClients = if (updatedClient == null) {
                clients
            } else {
                clients.map { if (it.id == updatedClient.id) updatedClient else it }
            }
            UndoLastResult.Success(updatedClients, updatedClient)
        } catch (e: Exception) {
            UndoLastResult.Error("Undo failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun undoClusterConfirmation(
        clients: List<Client>,
        clientIds: List<String>,
        completedAtMillis: Long
    ): UndoClusterResult {
        return try {
            for (clientId in clientIds) {
                clientRepository.deleteServiceRecord(clientId, completedAtMillis)
            }
            val clientIdSet = clientIds.toSet()
            val updatedClients = clients.map { client ->
                if (client.id in clientIdSet) {
                    client.copy(records = client.records.filterNot { it.completedAtMillis == completedAtMillis })
                } else {
                    client
                }
            }
            UndoClusterResult.Success(updatedClients)
        } catch (e: Exception) {
            UndoClusterResult.Error("Undo failed: ${e.message ?: "Unknown error"}")
        }
    }

    fun editSelectedClientNotes(selectedClient: Client?): EditNotesResult {
        val client = selectedClient ?: return EditNotesResult.Error("Pick a client first")
        return EditNotesResult.OpenEditor(client.id, client.name, client.notes)
    }

    suspend fun saveClientNotes(request: SaveNotesRequest): SaveNotesResult {
        val trimmedNotes = request.notes.trim()

        try {
            clientRepository.updateClientNotes(request.clientId, trimmedNotes)
        } catch (e: Exception) {
            return SaveNotesResult.Error("Save notes failed: ${e.message ?: "Unknown error"}")
        }

        val updatedClients = request.clients.map { client ->
            if (client.id == request.clientId) client.copy(notes = trimmedNotes) else client
        }
        val updatedClient = updatedClients.find { it.id == request.clientId }
        val updatedSelectedClient = if (request.currentSelectedClientId == request.clientId) {
            updatedClient
        } else {
            null
        }

        val savedStatus = "Notes saved for ${updatedClient?.name ?: "client"}"

        var syncedStatus: String? = null
        var retryDrainSucceeded = 0

        if (SheetsWriteBack.webAppUrl.isNotBlank() && updatedClient != null) {
            try {
                val result = clientRepository.writeBackClientNotes(updatedClient.name, trimmedNotes)
                if (result.success) {
                    syncedStatus = "Notes synced to sheet for ${updatedClient.name}"
                    retryDrainSucceeded = drainRetryQueueSucceededCount()
                } else {
                    retryQueue.enqueue(updatedClient.name, "Notes", trimmedNotes)
                }
            } catch (_: Exception) {
                retryQueue.enqueue(updatedClient.name, "Notes", trimmedNotes)
            }
        }

        return SaveNotesResult.Success(
            updatedClients = updatedClients,
            updatedSelectedClient = updatedSelectedClient,
            savedStatusMessage = savedStatus,
            syncedStatusMessage = syncedStatus,
            retryDrainSucceeded = retryDrainSucceeded
        )
    }

    private suspend fun drainRetryQueueSucceededCount(): Int {
        return try {
            retryQueue.drainQueue().succeeded
        } catch (_: Exception) {
            0
        }
    }

    private fun calculateEstimatedSqFt(
        amountUsed: Double?,
        amountUsed2: Double?,
        serviceType: ServiceType
    ): Int? {
        if (amountUsed == null && amountUsed2 == null) return null
        return if (serviceType.isSpray) {
            // Spray: amountUsed = Hose gal, amountUsed2 = PG gal
            // Both can be used on the same jobsite
            estimateSpraySqFt(amountUsed, amountUsed2)
        } else {
            // Granular: amountUsed = lbs, rate from preferences
            val rate = preferencesRepository.getGranularRate(serviceType)
            estimateGranularSqFt(amountUsed, rate)
        }
    }

    private fun serviceTypeToColumn(type: ServiceType): String = when (type) {
        ServiceType.ROUND_1 -> "Step 1"
        ServiceType.ROUND_2 -> "Step 2"
        ServiceType.ROUND_3 -> "Step 3"
        ServiceType.ROUND_4 -> "Step 4"
        ServiceType.ROUND_5 -> "Step 5"
        ServiceType.ROUND_6 -> "Step 6"
        ServiceType.GRUB -> "Grub"
        ServiceType.INCIDENTAL -> "Incidental"
    }

    private fun formatCheckValue(dateMillis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return "$month.$day"
    }

    private object DateUtilsBridge {
        fun formatTimestamp(timestamp: Long): String {
            return com.routeme.app.util.DateUtils.formatTimestamp(timestamp)
        }
    }
}
