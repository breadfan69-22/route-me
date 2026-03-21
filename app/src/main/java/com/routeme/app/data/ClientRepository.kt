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
import com.routeme.app.data.db.GeocodeCacheEntity
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
    private val nonClientStopDao: NonClientStopDao,
    private val geocodeCacheDao: com.routeme.app.data.db.GeocodeCacheDao? = null
) {
    suspend fun loadAllClients(): List<Client> = withContext(Dispatchers.IO) {
        clientDao.getAllClientsWithRecords().map { it.toDomain() }
    }

    suspend fun saveClients(clients: List<Client>) = withContext(Dispatchers.IO) {
        val clientsToSave = applyCachedCoordinates(clients)

        for (client in clientsToSave) {
            clientDao.insertClient(client.toEntity())
            for (record in client.records) {
                clientDao.insertServiceRecord(record.toEntity(client.id))
            }
        }

        upsertGeocodeCacheEntries(cacheEntriesFrom(clientsToSave))
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

    data class SyncResult(
        val clients: List<Client>,
        val message: String,
        val newlyAddedClients: List<Client>
    )

    suspend fun syncFromSheets(url: String): SyncResult = withContext(Dispatchers.IO) {
        val result = GoogleSheetsSync.fetch(url)
        if (result.clients.isNotEmpty()) {
            val existingClients = clientDao.getAllClients()

            // Preserve existing coordinates so a re-sync doesn't lose geocoded data
            val existingCoords = existingClients.associate { entity ->
                entity.name.lowercase() to Pair(entity.latitude, entity.longitude)
            }

            upsertGeocodeCacheEntries(
                existingClients.mapNotNull { entity ->
                    val lat = entity.latitude ?: return@mapNotNull null
                    val lng = entity.longitude ?: return@mapNotNull null
                    val addressKey = addressKeyFor(entity.address, entity.zone) ?: return@mapNotNull null
                    GeocodeCacheEntity(
                        addressKey = addressKey,
                        latitude = lat,
                        longitude = lng,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                }
            )

            val newlyAdded = result.clients.filter { it.name.lowercase() !in existingCoords }

            val mergedByNameClients = result.clients.map { client ->
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

            val mergedClients = applyCachedCoordinates(mergedByNameClients)

            clientDao.deleteAllClients()
            for (client in mergedClients) {
                clientDao.insertClient(client.toEntity())
                for (record in client.records) {
                    clientDao.insertServiceRecord(record.toEntity(client.id))
                }
            }

            upsertGeocodeCacheEntries(cacheEntriesFrom(mergedClients))

            return@withContext SyncResult(
                clients = mergedClients,
                message = result.message,
                newlyAddedClients = newlyAdded
            )
        }
        SyncResult(clients = result.clients, message = result.message, newlyAddedClients = emptyList())
    }

    suspend fun writeBackServiceCompletion(
        clientName: String,
        serviceType: com.routeme.app.ServiceType,
        completedAtMillis: Long
    ): SheetsWriteBack.WriteResult = withContext(Dispatchers.IO) {
        SheetsWriteBack.markDone(clientName, serviceType, completedAtMillis)
    }

    suspend fun writeBackRaw(
        clientName: String,
        column: String,
        value: String
    ): SheetsWriteBack.WriteResult = withContext(Dispatchers.IO) {
        SheetsWriteBack.postRaw(clientName, column, value)
    }

    suspend fun writeBackPropertyRaw(
        clientName: String,
        column: String,
        value: String
    ): SheetsWriteBack.WriteResult = withContext(Dispatchers.IO) {
        SheetsWriteBack.postPropertyRaw(clientName, column, value)
    }

    suspend fun writeBackAddPropertyClientRow(
        clientName: String,
        address: String
    ): SheetsWriteBack.WriteResult = withContext(Dispatchers.IO) {
        SheetsWriteBack.addPropertyClientRow(clientName, address)
    }

    suspend fun updateClientLawnSize(clientId: String, sqFt: Int) = withContext(Dispatchers.IO) {
        clientDao.updateClientLawnSize(clientId, sqFt)
    }

    suspend fun importFromUri(uri: Uri): ImportResult {
        val result = ClientImportParser.parse(appContext, uri)
        if (result.clients.isNotEmpty()) {
            saveClients(result.clients)
        }
        return result
    }

    suspend fun geocodeClients(clients: List<Client>): GeocodingHelper.GeocodingResult = withContext(Dispatchers.IO) {
        val clientsWithCachedCoordinates = applyCachedCoordinates(clients)
        val result = GeocodingHelper.geocodeClients(appContext, clientsWithCachedCoordinates)
        for (client in result.clients) {
            val lat = client.latitude
            val lng = client.longitude
            if (lat != null && lng != null) {
                clientDao.updateClientCoordinates(client.id, lat, lng)
            }
        }
        upsertGeocodeCacheEntries(cacheEntriesFrom(result.clients))
        result
    }

    private fun addressKeyFor(client: Client): String? = addressKeyFor(client.address, client.zone)

    private fun addressKeyFor(address: String, zone: String): String? {
        val enrichedAddress = GeocodingHelper.enrichAddress(address, zone)
        return enrichedAddress
            .lowercase()
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private suspend fun cachedCoordsByKey(keys: Set<String>): Map<String, Pair<Double, Double>> {
        if (keys.isEmpty()) return emptyMap()
        val cacheDao = geocodeCacheDao ?: return emptyMap()
        return cacheDao.getByKeys(keys.toList()).associate { entry ->
            entry.addressKey to Pair(entry.latitude, entry.longitude)
        }
    }

    private suspend fun applyCachedCoordinates(clients: List<Client>): List<Client> {
        if (clients.isEmpty()) return clients

        val keysNeedingCoordinates = clients
            .filter { it.latitude == null || it.longitude == null }
            .mapNotNull { addressKeyFor(it) }
            .toSet()

        if (keysNeedingCoordinates.isEmpty()) return clients

        val cachedCoordinates = cachedCoordsByKey(keysNeedingCoordinates)
        if (cachedCoordinates.isEmpty()) return clients

        return clients.map { client ->
            if (client.latitude != null && client.longitude != null) {
                client
            } else {
                val key = addressKeyFor(client) ?: return@map client
                val coords = cachedCoordinates[key] ?: return@map client
                client.copy(latitude = coords.first, longitude = coords.second)
            }
        }
    }

    private fun cacheEntriesFrom(clients: List<Client>, now: Long = System.currentTimeMillis()): List<GeocodeCacheEntity> {
        if (clients.isEmpty()) return emptyList()

        val entriesByKey = linkedMapOf<String, GeocodeCacheEntity>()
        for (client in clients) {
            val lat = client.latitude ?: continue
            val lng = client.longitude ?: continue
            val key = addressKeyFor(client) ?: continue
            entriesByKey[key] = GeocodeCacheEntity(
                addressKey = key,
                latitude = lat,
                longitude = lng,
                updatedAtMillis = now
            )
        }

        return entriesByKey.values.toList()
    }

    private suspend fun upsertGeocodeCacheEntries(entries: List<GeocodeCacheEntity>) {
        if (entries.isEmpty()) return
        geocodeCacheDao?.upsertAll(entries)
    }

    companion object {
        private val WHITESPACE_REGEX = "\\s+".toRegex()
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
        val tzOffsetMs = java.util.TimeZone.getDefault().rawOffset.toLong()
        clientDao.getDistinctServiceDates(tzOffsetMs)
    }

    suspend fun getClientStops(startMillis: Long, endMillis: Long): List<ClientStopRow> = withContext(Dispatchers.IO) {
        clientDao.getClientStopsForDateRange(startMillis, endMillis)
    }

    suspend fun getDistinctClientStopDates(): List<Long> = withContext(Dispatchers.IO) {
        val tzOffsetMs = java.util.TimeZone.getDefault().rawOffset.toLong()
        clientDao.getDistinctClientStopDates(tzOffsetMs)
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
        val tzOffsetMs = java.util.TimeZone.getDefault().rawOffset.toLong()
        nonClientStopDao.getDistinctStopDates(tzOffsetMs)
    }
}
