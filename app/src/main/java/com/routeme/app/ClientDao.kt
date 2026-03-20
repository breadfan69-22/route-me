package com.routeme.app

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ClientDao {
    @Transaction
    @Query("SELECT * FROM clients ORDER BY name")
    suspend fun getAllClientsWithRecords(): List<ClientWithRecords>

    @Query("SELECT * FROM clients ORDER BY name")
    suspend fun getAllClients(): List<ClientEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClient(client: ClientEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClients(clients: List<ClientEntity>)

    @Insert
    suspend fun insertServiceRecord(record: ServiceRecordEntity)

    @Insert
    suspend fun insertClientStopEvent(event: ClientStopEventEntity)

    @Update
    suspend fun updateClient(client: ClientEntity)

    @Query("UPDATE clients SET latitude = :lat, longitude = :lng WHERE id = :clientId")
    suspend fun updateClientCoordinates(clientId: String, lat: Double, lng: Double)

    @Query("UPDATE clients SET notes = :notes WHERE id = :clientId")
    suspend fun updateClientNotes(clientId: String, notes: String)

    @Query("DELETE FROM clients")
    suspend fun deleteAllClients()

    @Query("DELETE FROM clients WHERE id = :clientId")
    suspend fun deleteClient(clientId: String)

    @Query("SELECT COUNT(*) FROM clients")
    suspend fun getClientCount(): Int

    @Query("SELECT COUNT(*) FROM service_records")
    suspend fun getServiceRecordCount(): Int

    @Transaction
    @Query(
        """
        SELECT sr.clientId AS clientId,
               c.name AS clientName,
               sr.serviceType AS serviceType,
               sr.arrivedAtMillis AS arrivedAtMillis,
               sr.completedAtMillis AS completedAtMillis,
               sr.durationMinutes AS durationMinutes,
               sr.notes AS notes
        FROM service_records sr
        INNER JOIN clients c ON sr.clientId = c.id
        WHERE sr.completedAtMillis >= :startMillis AND sr.completedAtMillis < :endMillis
        ORDER BY sr.completedAtMillis
        """
    )
    suspend fun getRecordsForDateRange(startMillis: Long, endMillis: Long): List<DailyRecordRow>

    @Query("SELECT DISTINCT (completedAtMillis / 86400000) * 86400000 AS dayMillis FROM service_records ORDER BY dayMillis DESC")
    suspend fun getDistinctServiceDates(): List<Long>

    @Query(
        """
        SELECT cse.clientId AS clientId,
               cse.clientName AS clientName,
               cse.arrivedAtMillis AS arrivedAtMillis,
               cse.endedAtMillis AS endedAtMillis,
               cse.durationMinutes AS durationMinutes,
               cse.status AS status,
               cse.serviceTypes AS serviceTypes,
               cse.cancelReason AS cancelReason,
             cse.notes AS notes,
             cse.weatherTempF AS weatherTempF,
             cse.weatherWindMph AS weatherWindMph,
             cse.weatherDesc AS weatherDesc
        FROM client_stop_events cse
        WHERE cse.endedAtMillis >= :startMillis AND cse.endedAtMillis < :endMillis
        ORDER BY cse.endedAtMillis
        """
    )
    suspend fun getClientStopsForDateRange(startMillis: Long, endMillis: Long): List<ClientStopRow>

    @Query("SELECT DISTINCT (endedAtMillis / 86400000) * 86400000 AS dayMillis FROM client_stop_events ORDER BY dayMillis DESC")
    suspend fun getDistinctClientStopDates(): List<Long>

    @Query("SELECT id FROM clients")
    suspend fun getAllClientIds(): List<String>

    @Query("SELECT id FROM clients WHERE name = :name LIMIT 1")
    suspend fun getClientIdByName(name: String): String?

    @Query("DELETE FROM service_records WHERE clientId = :clientId AND completedAtMillis = :completedAtMillis")
    suspend fun deleteServiceRecord(clientId: String, completedAtMillis: Long)

    @Insert
    suspend fun insertPendingWriteBack(item: PendingWriteBackEntity)

    @Query("SELECT * FROM pending_write_backs ORDER BY createdAtMillis ASC")
    suspend fun getAllPendingWriteBacks(): List<PendingWriteBackEntity>

    @Query("SELECT COUNT(*) FROM pending_write_backs")
    suspend fun getPendingWriteBackCount(): Int

    @Delete
    suspend fun deletePendingWriteBack(item: PendingWriteBackEntity)

    @Query("UPDATE pending_write_backs SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: Long)
}
