package com.routeme.app.data

import android.content.Context
import android.net.Uri
import com.routeme.app.Client
import com.routeme.app.ClientDao
import com.routeme.app.ClientPropertyDao
import com.routeme.app.ClientPropertyEntity
import com.routeme.app.ClientStopEventEntity
import com.routeme.app.ClientStopRow
import com.routeme.app.ClientImportParser
import com.routeme.app.PropertyInput
import com.routeme.app.DailyRecordRow
import com.routeme.app.ClientStopStatus
import com.routeme.app.ImportResult
import com.routeme.app.NonClientStop
import com.routeme.app.NonClientStopDao
import com.routeme.app.NonClientStopEntity
import com.routeme.app.ServiceRecord
import com.routeme.app.ServiceType
import com.routeme.app.SunShade
import com.routeme.app.WindExposure
import com.routeme.app.data.db.GeocodeCacheEntity
import com.routeme.app.network.DistanceMatrixHelper
import com.routeme.app.network.GeocodingHelper
import com.routeme.app.network.GoogleSheetsSync
import com.routeme.app.network.SheetsWriteBack
import com.routeme.app.toDomain
import com.routeme.app.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ClientRepository(
    private val appContext: Context,
    private val clientDao: ClientDao,
    private val clientPropertyDao: ClientPropertyDao,
    private val nonClientStopDao: NonClientStopDao,
    private val geocodeCacheDao: com.routeme.app.data.db.GeocodeCacheDao? = null
) {
    suspend fun loadAllClients(): List<Client> = withContext(Dispatchers.IO) {
        val clients = clientDao.getAllClientsWithRecords().map { it.toDomain() }
        val result = applyCachedCoordinates(clients)
        val withCoords = result.count { it.latitude != null }
        android.util.Log.d("AutoSuggestions", "loadAllClients: ${clients.size} raw, $withCoords with coords after cache overlay")
        result
    }

    suspend fun loadClientById(clientId: String): Client? = withContext(Dispatchers.IO) {
        clientDao.getClientWithRecordsById(clientId)?.toDomain()
    }

    suspend fun saveClients(clients: List<Client>) = withContext(Dispatchers.IO) {
        val clientsToSave = applyCachedCoordinates(clients)

        for (client in clientsToSave) {
            val existing = clientDao.getClientById(client.id)
            val merged = if (existing != null) {
                client.copy(
                    lawnSizeSqFt = if (client.lawnSizeSqFt > 0) client.lawnSizeSqFt else existing.lawnSizeSqFt,
                    sunShade = if (SunShade.fromStorage(client.sunShade) != SunShade.UNKNOWN) {
                        client.sunShade
                    } else {
                        existing.sunShade
                    },
                    windExposure = if (WindExposure.fromStorage(client.windExposure) != WindExposure.UNKNOWN) {
                        client.windExposure
                    } else {
                        existing.windExposure
                    },
                    terrain = client.terrain.ifBlank { existing.terrain }
                )
            } else {
                client
            }

            clientDao.insertClient(merged.toEntity())
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

    suspend fun syncFromSheets(url: String, propertySheetUrl: String = ""): SyncResult = withContext(Dispatchers.IO) {
        val result = GoogleSheetsSync.fetch(url)

        // Fetch coordinates from PropertySpecs sheet (direct CSV)
        val propertyCoords = if (propertySheetUrl.isNotBlank()) {
            GoogleSheetsSync.fetchPropertyCoordinates(propertySheetUrl)
        } else {
            emptyMap()
        }
        android.util.Log.d("ClientSync", "PropertySpecs provided ${propertyCoords.size} coordinates")

        if (result.clients.isNotEmpty()) {
            val existingClients = clientDao.getAllClients()
            val existingPropertiesByClientKey = existingPropertiesByClientKey(existingClients)
            val existingByName = existingClients.associateBy { it.name.lowercase() }

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
                val existing = existingByName[client.name.lowercase()]
                val nameKey = client.name.lowercase()
                val propData = propertyCoords[nameKey]

                // Property data priority: 1. PropertySpecs (source of truth) 2. ClientSheet 3. Existing DB
                val mergedLawnSize = when {
                    propData?.lawnSizeSqFt != null && propData.lawnSizeSqFt > 0 -> propData.lawnSizeSqFt
                    client.lawnSizeSqFt > 0 -> client.lawnSizeSqFt
                    else -> existing?.lawnSizeSqFt ?: 0
                }
                val mergedSunShade = when {
                    propData?.sunShade != null && SunShade.fromStorage(propData.sunShade) != SunShade.UNKNOWN -> propData.sunShade
                    SunShade.fromStorage(client.sunShade) != SunShade.UNKNOWN -> client.sunShade
                    else -> existing?.sunShade ?: ""
                }
                val mergedWindExposure = when {
                    propData?.windExposure != null && WindExposure.fromStorage(propData.windExposure) != WindExposure.UNKNOWN -> propData.windExposure
                    WindExposure.fromStorage(client.windExposure) != WindExposure.UNKNOWN -> client.windExposure
                    else -> existing?.windExposure ?: ""
                }
                val mergedTerrain = when {
                    propData?.terrain != null && propData.terrain.isNotBlank() -> propData.terrain
                    client.terrain.isNotBlank() -> client.terrain
                    else -> existing?.terrain.orEmpty()
                }
                val mergedIrrigation = when {
                    propData?.irrigation != null && propData.irrigation.isNotBlank() -> propData.irrigation
                    client.irrigation.isNotBlank() -> client.irrigation
                    else -> ""
                }

                // Coordinate priority: 1. PropertySpecs (source of truth) 2. ClientSheet 3. Existing DB
                val (finalLat, finalLng) = when {
                    propData?.lat != null && propData.lng != null ->
                        propData.lat to propData.lng
                    client.latitude != null && client.longitude != null ->
                        client.latitude to client.longitude
                    existingCoords[nameKey]?.first != null && existingCoords[nameKey]?.second != null ->
                        existingCoords[nameKey]!!.first to existingCoords[nameKey]!!.second
                    else -> null to null
                }

                client.copy(
                    latitude = finalLat,
                    longitude = finalLng,
                    lawnSizeSqFt = mergedLawnSize,
                    sunShade = mergedSunShade,
                    windExposure = mergedWindExposure,
                    terrain = mergedTerrain,
                    irrigation = mergedIrrigation
                )
            }

            val mergedClients = applyCachedCoordinates(mergedByNameClients)

            clientPropertyDao.deleteAllProperties()
            clientDao.deleteAllClients()
            for (client in mergedClients) {
                clientDao.insertClient(client.toEntity())
                for (record in client.records) {
                    clientDao.insertServiceRecord(record.toEntity(client.id))
                }
            }
            upsertImportedProperties(mergedClients, existingPropertiesByClientKey)

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

        val existing = clientPropertyDao.getPropertyForClient(clientId)
        clientPropertyDao.upsertProperty(
            ClientPropertyEntity(
                clientId = clientId,
                lawnSizeSqFt = sqFt.coerceAtLeast(0),
                sunShade = existing?.sunShade ?: SunShade.UNKNOWN.name,
                windExposure = existing?.windExposure ?: WindExposure.UNKNOWN.name,
                hasSteepSlopes = existing?.hasSteepSlopes ?: false,
                hasIrrigation = existing?.hasIrrigation ?: false,
                propertyNotes = existing?.propertyNotes ?: "",
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveClientPropertyInput(clientId: String, property: PropertyInput) = withContext(Dispatchers.IO) {
        if (!property.hasAnyData) return@withContext

        val existingClient = clientDao.getClientById(clientId)
        val existing = clientPropertyDao.getPropertyForClient(clientId)
        val parsedSunShade = SunShade.fromStorage(property.sunShade)
        val parsedWindExposure = WindExposure.fromStorage(property.windExposure)
        val parsedSteepSlopes = parseYesNo(property.steepSlopes)
        val parsedIrrigation = parseYesNo(property.irrigation)

        val effectiveSunShadeStorage = if (parsedSunShade != SunShade.UNKNOWN) {
            parsedSunShade.name
        } else {
            existing?.sunShade ?: SunShade.UNKNOWN.name
        }
        val effectiveWindExposureStorage = if (parsedWindExposure != WindExposure.UNKNOWN) {
            parsedWindExposure.name
        } else {
            existing?.windExposure ?: WindExposure.UNKNOWN.name
        }

        clientPropertyDao.upsertProperty(
            ClientPropertyEntity(
                clientId = clientId,
                lawnSizeSqFt = existing?.lawnSizeSqFt ?: 0,
                sunShade = effectiveSunShadeStorage,
                windExposure = effectiveWindExposureStorage,
                hasSteepSlopes = parsedSteepSlopes ?: existing?.hasSteepSlopes ?: false,
                hasIrrigation = parsedIrrigation ?: existing?.hasIrrigation ?: false,
                propertyNotes = existing?.propertyNotes ?: "",
                updatedAtMillis = System.currentTimeMillis()
            )
        )

        if (existingClient != null) {
            val legacyTerrain = when (parsedSteepSlopes) {
                true -> "Steep Slopes"
                false -> "Flat"
                null -> existingClient.terrain
            }

            clientDao.updateClientPropertyFields(
                clientId = clientId,
                lawnSizeSqFt = existingClient.lawnSizeSqFt,
                sunShade = storageToDisplaySunShade(effectiveSunShadeStorage),
                terrain = legacyTerrain,
                windExposure = storageToDisplayWindExposure(effectiveWindExposureStorage)
            )
        }
    }

    suspend fun importFromUri(uri: Uri): ImportResult {
        val result = ClientImportParser.parse(appContext, uri)
        if (result.clients.isNotEmpty()) {
            val existingPropertiesByClientKey = existingPropertiesByClientKey(clientDao.getAllClients())
            saveClients(result.clients)
            upsertImportedProperties(result.clients, existingPropertiesByClientKey)
        }
        return result
    }

    suspend fun geocodeClients(clients: List<Client>): GeocodingHelper.GeocodingResult = withContext(Dispatchers.IO) {
        val clientsWithCachedCoordinates = applyCachedCoordinates(clients)
        // Track which clients need geocoding (no coords before geocode call)
        val clientsNeedingGeocode = clientsWithCachedCoordinates
            .filter { it.latitude == null || it.longitude == null }
            .map { it.id }
            .toSet()
        android.util.Log.d("GeocodingFlow", "geocodeClients: ${clientsNeedingGeocode.size} actually need API geocoding")
        val result = GeocodingHelper.geocodeClients(appContext, clientsWithCachedCoordinates)
        android.util.Log.d("GeocodingFlow", "geocodeClients: API returned, geocoded=${result.geocodedCount}, failed=${result.failedCount}")
        
        // Only process clients that were newly geocoded (had no coords, now have coords)
        val newlyGeocodedClients = result.clients.filter { client ->
            client.id in clientsNeedingGeocode && client.latitude != null && client.longitude != null
        }
        android.util.Log.d("GeocodingFlow", "geocodeClients: ${newlyGeocodedClients.size} newly geocoded clients to update")
        
        for (client in newlyGeocodedClients) {
            val lat = client.latitude!!
            val lng = client.longitude!!
            clientDao.updateClientCoordinates(client.id, lat, lng)
        }
        upsertGeocodeCacheEntries(cacheEntriesFrom(newlyGeocodedClients))
        
        // Write back coords to property sheet in background (fire-and-forget, don't block)
        if (newlyGeocodedClients.isNotEmpty() && SheetsWriteBack.propertyWebAppUrl.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                for (client in newlyGeocodedClients) {
                    val lat = client.latitude!!
                    val lng = client.longitude!!
                    runCatching { SheetsWriteBack.postPropertyRaw(client.name, "lat", "%.7f".format(lat)) }
                    runCatching { SheetsWriteBack.postPropertyRaw(client.name, "lng", "%.7f".format(lng)) }
                }
            }
        }
        android.util.Log.d("GeocodingFlow", "geocodeClients: done")
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

        android.util.Log.d("AutoSuggestions", "applyCachedCoords: ${keysNeedingCoordinates.size} keys needing coords, geocodeCacheDao=${geocodeCacheDao != null}")
        if (keysNeedingCoordinates.isEmpty()) return clients

        val cachedCoordinates = cachedCoordsByKey(keysNeedingCoordinates)
        android.util.Log.d("AutoSuggestions", "applyCachedCoords: ${cachedCoordinates.size} cache hits")
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

    private suspend fun existingPropertiesByClientKey(clients: List<com.routeme.app.ClientEntity>): Map<String, ClientPropertyEntity> {
        if (clients.isEmpty()) return emptyMap()

        val existingByClientId = clientPropertyDao
            .getPropertiesForClients(clients.map { it.id })
            .associateBy { it.clientId }

        return clients.mapNotNull { client ->
            val key = clientKey(client.name, client.address) ?: return@mapNotNull null
            val property = existingByClientId[client.id] ?: return@mapNotNull null
            key to property
        }.toMap()
    }

    private suspend fun upsertImportedProperties(
        clients: List<Client>,
        existingByClientKey: Map<String, ClientPropertyEntity>
    ) {
        if (clients.isEmpty()) return

        val now = System.currentTimeMillis()
        val entities = clients.mapNotNull { client ->
            val key = clientKey(client.name, client.address)
            val existing = key?.let { existingByClientKey[it] }

            val importedLawnSize = client.lawnSizeSqFt.takeIf { it > 0 }
            val importedSunShade = SunShade.fromStorage(client.sunShade)
            val importedWindExposure = WindExposure.fromStorage(client.windExposure)
            val hasImportedTerrain = client.terrain.isNotBlank()
            val importedIrrigation = parseYesNo(client.irrigation)

            val hasImportData =
                importedLawnSize != null ||
                    importedSunShade != SunShade.UNKNOWN ||
                    importedWindExposure != WindExposure.UNKNOWN ||
                    hasImportedTerrain ||
                    importedIrrigation != null

            if (!hasImportData && existing == null) {
                return@mapNotNull null
            }

            ClientPropertyEntity(
                clientId = client.id,
                lawnSizeSqFt = importedLawnSize ?: existing?.lawnSizeSqFt ?: 0,
                sunShade = if (importedSunShade != SunShade.UNKNOWN) {
                    importedSunShade.name
                } else {
                    existing?.sunShade ?: SunShade.UNKNOWN.name
                },
                windExposure = if (importedWindExposure != WindExposure.UNKNOWN) {
                    importedWindExposure.name
                } else {
                    existing?.windExposure ?: WindExposure.UNKNOWN.name
                },
                hasSteepSlopes = if (hasImportedTerrain) {
                    inferHasSteepSlopes(client.terrain)
                } else {
                    existing?.hasSteepSlopes ?: false
                },
                hasIrrigation = importedIrrigation ?: existing?.hasIrrigation ?: false,
                propertyNotes = existing?.propertyNotes ?: "",
                updatedAtMillis = now
            )
        }

        if (entities.isNotEmpty()) {
            clientPropertyDao.upsertProperties(entities)
        }
    }

    private fun clientKey(name: String, address: String): String? {
        val normalizedName = name
            .lowercase()
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        val normalizedAddress = address
            .lowercase()
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        if (normalizedName.isBlank() || normalizedAddress.isBlank()) {
            return null
        }

        return "$normalizedName|$normalizedAddress"
    }

    private fun inferHasSteepSlopes(terrain: String): Boolean {
        val normalized = terrain.lowercase().trim()
        if (normalized.isBlank()) return false

        if (normalized.contains("flat") || normalized.contains("level") || normalized.contains("no slope")) {
            return false
        }

        return true
    }

    private fun parseYesNo(value: String): Boolean? {
        return when (value.lowercase().trim()) {
            "yes", "y", "true", "1" -> true
            "no", "n", "false", "0" -> false
            else -> null
        }
    }

    private fun storageToDisplaySunShade(value: String): String {
        return when (SunShade.fromStorage(value)) {
            SunShade.FULL_SUN -> "Full Sun"
            SunShade.PARTIAL_SHADE -> "Partial Shade"
            SunShade.FULL_SHADE -> "Full Shade"
            SunShade.UNKNOWN -> ""
        }
    }

    private fun storageToDisplayWindExposure(value: String): String {
        return when (WindExposure.fromStorage(value)) {
            WindExposure.EXPOSED -> "Exposed"
            WindExposure.SHELTERED -> "Sheltered"
            WindExposure.MIXED -> "Mixed"
            WindExposure.UNKNOWN -> ""
        }
    }

    companion object {
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }

    data class PropertySyncResult(val updated: Int, val message: String)

    /**
     * Fetches all rows from the property sheet Apps Script (`?action=exportAll`)
     * and upserts into client_properties, only overwriting UNKNOWN/empty fields.
     */
    suspend fun syncPropertyDataFromSheet(propertyUrl: String): PropertySyncResult = withContext(Dispatchers.IO) {
        val exportUrl = if (propertyUrl.contains('?')) {
            "$propertyUrl&action=exportAll"
        } else {
            "$propertyUrl?action=exportAll"
        }

        val json = try {
            fetchJsonGet(exportUrl)
        } catch (e: Exception) {
            return@withContext PropertySyncResult(0, "Property sheet fetch failed: ${e.message}")
        }

        val status = json.optString("status")
        if (status != "ok") {
            return@withContext PropertySyncResult(0, "Property sheet error: ${json.optString("message")}")
        }

        val rows = json.optJSONArray("rows")
        if (rows == null || rows.length() == 0) {
            return@withContext PropertySyncResult(0, "Property sheet returned 0 rows.")
        }

        val allClients = clientDao.getAllClients()
        val clientsByName = allClients.associateBy { it.name.lowercase().trim() }

        var updatedCount = 0
        var coordsUpdated = 0
        val now = System.currentTimeMillis()
        val coordCacheEntries = mutableListOf<GeocodeCacheEntity>()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("Client Name", "").trim()
            if (name.isBlank()) continue

            val entity = clientsByName[name.lowercase()] ?: continue
            val clientId = entity.id
            val existing = clientPropertyDao.getPropertyForClient(clientId)

            // Read lat/lng from property sheet
            val sheetLat = row.optDouble("Latitude", Double.NaN).takeIf { !it.isNaN() && it in -90.0..90.0 }
                ?: row.optDouble("lat", Double.NaN).takeIf { !it.isNaN() && it in -90.0..90.0 }
            val sheetLng = row.optDouble("Longitude", Double.NaN).takeIf { !it.isNaN() && it in -180.0..180.0 }
                ?: row.optDouble("lng", Double.NaN).takeIf { !it.isNaN() && it in -180.0..180.0 }
            if (sheetLat != null && sheetLng != null) {
                clientDao.updateClientCoordinates(clientId, sheetLat, sheetLng)
                coordsUpdated++
                val cacheKey = addressKeyFor(entity.address, entity.zone)
                if (cacheKey != null) {
                    coordCacheEntries.add(GeocodeCacheEntity(
                        addressKey = cacheKey,
                        latitude = sheetLat,
                        longitude = sheetLng,
                        updatedAtMillis = now
                    ))
                }
            }

            val sheetSunShade = SunShade.fromStorage(
                row.optString("Sun/Shade", "")
            )
            val sheetWindExposure = WindExposure.fromStorage(
                row.optString("Wind Exposure", "")
            )
            val sheetSteepSlopes = parseYesNo(row.optString("Steep Slopes", ""))
            val sheetIrrigation = parseYesNo(row.optString("Irrigation", ""))
            val sheetLawnSize = row.optString("Lawn Size", "")
                .replace(",", "").trim().toIntOrNull()?.takeIf { it > 0 }

            val effectiveSunShade = if (existing?.sunShade != null && SunShade.fromStorage(existing.sunShade) != SunShade.UNKNOWN)
                existing.sunShade
            else if (sheetSunShade != SunShade.UNKNOWN) sheetSunShade.name
            else existing?.sunShade ?: SunShade.UNKNOWN.name

            val effectiveWindExposure = if (existing?.windExposure != null && WindExposure.fromStorage(existing.windExposure) != WindExposure.UNKNOWN)
                existing.windExposure
            else if (sheetWindExposure != WindExposure.UNKNOWN) sheetWindExposure.name
            else existing?.windExposure ?: WindExposure.UNKNOWN.name

            val effectiveSteepSlopes = existing?.hasSteepSlopes?.takeIf { it }
                ?: sheetSteepSlopes
                ?: existing?.hasSteepSlopes
                ?: false

            val effectiveIrrigation = existing?.hasIrrigation?.takeIf { it }
                ?: sheetIrrigation
                ?: existing?.hasIrrigation
                ?: false

            val effectiveLawnSize = if ((existing?.lawnSizeSqFt ?: 0) > 0)
                existing!!.lawnSizeSqFt
            else sheetLawnSize ?: existing?.lawnSizeSqFt ?: 0

            clientPropertyDao.upsertProperty(
                ClientPropertyEntity(
                    clientId = clientId,
                    lawnSizeSqFt = effectiveLawnSize,
                    sunShade = effectiveSunShade,
                    windExposure = effectiveWindExposure,
                    hasSteepSlopes = effectiveSteepSlopes,
                    hasIrrigation = effectiveIrrigation,
                    propertyNotes = existing?.propertyNotes ?: "",
                    updatedAtMillis = now
                )
            )
            updatedCount++
        }

        // Populate geocode cache for future loads
        if (coordCacheEntries.isNotEmpty()) {
            upsertGeocodeCacheEntries(coordCacheEntries)
        }

        val coordMsg = if (coordsUpdated > 0) ", $coordsUpdated coordinates" else ""
        PropertySyncResult(updatedCount, "Property sheet: $updatedCount client(s) updated$coordMsg.")
    }

    private fun fetchJsonGet(urlString: String): JSONObject {
        var currentUrl = urlString
        for (i in 0 until 5) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false

            val code = conn.responseCode
            if (code in 301..303 || code == 307 || code == 308) {
                currentUrl = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw Exception("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            return JSONObject(body)
        }
        throw Exception("Too many redirects")
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
