package com.routeme.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ClientEntity::class,
        ServiceRecordEntity::class,
        PendingWriteBackEntity::class,
        NonClientStopEntity::class,
        ClientStopEventEntity::class,
        com.routeme.app.data.db.DailyWeatherEntity::class,
        com.routeme.app.data.db.GeocodeCacheEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun nonClientStopDao(): NonClientStopDao
    abstract fun weatherDao(): com.routeme.app.data.db.WeatherDao
    abstract fun geocodeCacheDao(): com.routeme.app.data.db.GeocodeCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "routeme_database"
                )
                    .addMigrations(*AppDatabaseMigrations.ALL)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}


