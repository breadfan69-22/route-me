package com.routeme.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WeatherDao {
    @Query("SELECT * FROM daily_weather WHERE dateMillis = :dateMillis LIMIT 1")
    suspend fun getWeatherForDate(dateMillis: Long): DailyWeatherEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyWeatherEntity)
}
