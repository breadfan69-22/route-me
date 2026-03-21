package com.routeme.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NonClientStopDao {
    @Insert
    suspend fun insertStop(stop: NonClientStopEntity): Long

    @Query("UPDATE non_client_stops SET departedAtMillis = :departedAt, durationMinutes = :duration WHERE stopId = :id")
    suspend fun updateDeparture(id: Long, departedAt: Long, duration: Long)

    @Query("UPDATE non_client_stops SET address = :address WHERE stopId = :id")
    suspend fun updateAddress(id: Long, address: String)

    @Query("SELECT * FROM non_client_stops WHERE arrivedAtMillis >= :startMillis AND arrivedAtMillis < :endMillis ORDER BY arrivedAtMillis")
    suspend fun getStopsForDateRange(startMillis: Long, endMillis: Long): List<NonClientStopEntity>

    @Query("SELECT DISTINCT ((arrivedAtMillis + :tzOffsetMs) / 86400000) * 86400000 - :tzOffsetMs AS dayMillis FROM non_client_stops ORDER BY dayMillis DESC")
    suspend fun getDistinctStopDates(tzOffsetMs: Long): List<Long>
}
