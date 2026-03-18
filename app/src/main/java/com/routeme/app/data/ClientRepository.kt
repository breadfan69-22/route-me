package com.routeme.app.data

import android.content.Context
import android.net.Uri
import com.routeme.app.Client
import com.routeme.app.ClientDao
import com.routeme.app.ClientStopEventEntity
import com.routeme.app.ClientStopRow
import com.routeme.app.ClientImportParser
import com.routeme.app.DailyRecordRow
import com.routeme.app.ClientStopStatus
import com.routeme.app.ImportResult
import com.routeme.app.NonClientStop
import com.routeme.app.NonClientStopDao
import com.routeme.app.NonClientStopEntity
import com.routeme.app.ServiceRecord
import com.routeme.app.ServiceType
import com.routeme.app.network.DistanceMatrixHelper
import com.routeme.app.network.GeocodingHelper
import com.routeme.app.network.GoogleSheetsSync
import com.routeme.app.network.SheetsWriteBack
import com.routeme.app.toDomain
import com.routeme.app.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClientRepository(
    private val appContext: Context,
    private val clientDao: ClientDao,
    private val nonClientStopDao: NonClientStopDao
) {
    suspend fun loadAllClients(): List<Client> = withContext(Dispatchers.IO) {
        clientDao.getAllClientsWithRecords().map { it.toDomain() }
    }

    suspend fun saveClients(clients: List<Client>) = withContext(Dispatchers.IO) {
        for (client in clients) {
            clientDao.insertClient(client.toEntity())
            for (record in client.records) {
                clientDao.insertServiceRecord(record.toEntity(client.id))
            }
        }
    }

    suspend fun saveServiceRecord(clientId: String, record: ServiceRecord) = withContext(Dispatchers.IO) {
        clientDao.insertServiceRecord(record.toEntity(clientId))
    }

    suspend fun saveClientStopEvent(
        clientId: String,
        clientName: String,
        arrivedAtMillis: Long?,
        endedAtMillis: Long,
        durationMinutes: Long,
        status: ClientStopStatus,
        serviceTypes: Set<ServiceType> = emptySet(),
        cancelReason: String? = null,
        notes: String = "",
        lat: Double? = null,
        lng: Double? = null,
        weatherTempF: Int? = null,
        weatherWindMph: Int? = null,
        weatherDesc: String? = null
    ) = withContext(Dispatchers.IO) {
        clientDao.insertClientStopEvent(
            ClientStopEventEntity(
                clientId = clientId,
                clientName = clientName,
                arrivedAtMillis = arrivedAtMillis,
                endedAtMillis = endedAtMillis,
                durationMinutes = durationMinutes,
                status = status.name,
                serviceTypes = serviceTypes.joinToString(",") { it.name },
                cancelReason = cancelReason,
                notes = notes,
                lat = lat,
                lng = lng,
                weatherTempF = weatherTempF,
                weatherWindMph = weatherWindMph,
                weatherDesc = weatherDesc
            )
        )
    }

    suspend fun updateClientCoordinates(clientId: String, lat: Double, lng: Double) = withContext(Dispatchers.IO) {
        clientDao.updateClientCoordinates(clientId, lat, lng)
    }

    suspend fun syncFromSheets(url: String): GoogleSheetsSync.SyncResult = withContext(Dispatchers.IO) {
        val result = GoogleSheetsSync.fetch(url)
        if (result.clients.isNotEmpty()) {
            // Preserve existing coordinates so a re-sync doesn't lose geocoded data
            val existingCoords = clientDao.getAllClients().associate { entity ->
                entity.name.lowercase() to Pair(entity.latitude, entity.longitude)
            }

            val mergedClients = result.clients.map { client ->
                if (client.latitude == null || client.longitude == null) {
                    val saved = existingCoords[client.name.lowercase()]
                    if (saved != null) {
                        client.copy(latitude = saved.first, longitude = saved.second)
                    } else {
                        client
                    }
                } else {
                    client
                }
            }

            clientDao.deleteAllClients()
            for (client in mergedClients) {
                clientDao.insertClient(client.toEntity())
                for (record in client.records) {
                    clientDao.insertServiceRecord(record.toEntity(client.id))
                }
            }

            return@withContext result.copy(clients = mergedClients)
        }
        result
    }

    suspend fun writeBackServiceCompletion(
        clientName: String,
        serviceType: com.routeme.app.ServiceType,
        completedAtMillis: Long
    ): SheetsWriteBack.WriteResult = withContext(Dispatchers.IO) {
        SheetsWriteBack.markDone(clientName, serviceType, completedAtMillis)
    }

    suspend fun importFromUri(uri: Uri): ImportResult {
        val result = ClientImportParser.parse(appContext, uri)
        if (result.clients.isNotEmpty()) {
            saveClients(result.clients)
        }
        return result
    }

    suspend fun geocodeClients(clients: List<Client>): GeocodingHelper.GeocodingResult = withContext(Dispatchers.IO) {
        val result = GeocodingHelper.geocodeClients(appContext, clients)
        for (client in result.clients) {
            val lat = client.latitude
            val lng = client.longitude
            if (lat != null && lng != null) {
                clientDao.updateClientCoordinates(client.id, lat, lng)
            }
        }
        result
    }

    suspend fun fetchDrivingTimes(
        originLat: Double,
        originLng: Double,
        clients: List<Client>
    ): List<DistanceMatrixHelper.DrivingInfo> = withContext(Dispatchers.IO) {
        DistanceMatrixHelper.fetchDrivingTimes(originLat, originLng, clients)
    }

    suspend fun getDailyRecords(startMillis: Long, endMillis: Long): List<DailyRecordRow> = withContext(Dispatchers.IO) {
        clientDao.getRecordsForDateRange(startMillis, endMillis)
    }

    suspend fun getDistinctServiceDates(): List<Long> = withContext(Dispatchers.IO) {
        clientDao.getDistinctServiceDates()
    }

    suspend fun getClientStops(startMillis: Long, endMillis: Long): List<ClientStopRow> = withContext(Dispatchers.IO) {
        clientDao.getClientStopsForDateRange(startMillis, endMillis)
    }

    suspend fun getDistinctClientStopDates(): List<Long> = withContext(Dispatchers.IO) {
        clientDao.getDistinctClientStopDates()
    }

    suspend fun deleteServiceRecord(clientId: String, completedAtMillis: Long) = withContext(Dispatchers.IO) {
        clientDao.deleteServiceRecord(clientId, completedAtMillis)
    }

    suspend fun updateClientNotes(clientId: String, notes: String) = withContext(Dispatchers.IO) {
        clientDao.updateClientNotes(clientId, notes)
    }

    suspend fun writeBackClientNotes(clientName: String, notes: String): SheetsWriteBack.WriteResult = withContext(Dispatchers.IO) {
        SheetsWriteBack.updateNotes(clientName, notes)
    }

    // ─── Non-client stops ──────────────────────────────────────

    suspend fun getNonClientStops(startMillis: Long, endMillis: Long): List<NonClientStop> = withContext(Dispatchers.IO) {
        nonClientStopDao.getStopsForDateRange(startMillis, endMillis).map { it.toDomain() }
    }

    suspend fun getDistinctNonClientStopDates(): List<Long> = withContext(Dispatchers.IO) {
        nonClientStopDao.getDistinctStopDates()
    }
}
