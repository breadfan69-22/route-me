package com.routeme.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GeocodeCacheDao {
    @Query("SELECT * FROM geocode_cache WHERE addressKey IN (:keys)")
    suspend fun getByKeys(keys: List<String>): List<GeocodeCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GeocodeCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<GeocodeCacheEntity>)
}