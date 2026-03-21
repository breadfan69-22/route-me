package com.routeme.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ForecastDao {
    @Query("SELECT * FROM forecast_days ORDER BY dateMillis ASC")
    suspend fun getAll(): List<ForecastDayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(days: List<ForecastDayEntity>)

    @Query("DELETE FROM forecast_days")
    suspend fun clearAll()
}
