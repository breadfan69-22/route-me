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
    version = 12,
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
                    .addMigrations(
                        AppDatabaseMigrations.MIGRATION_1_2,
                        AppDatabaseMigrations.MIGRATION_2_3,
                        AppDatabaseMigrations.MIGRATION_3_4,
                        AppDatabaseMigrations.MIGRATION_4_5,
                        AppDatabaseMigrations.MIGRATION_5_6,
                        AppDatabaseMigrations.MIGRATION_6_7,
                        AppDatabaseMigrations.MIGRATION_7_8,
                        AppDatabaseMigrations.MIGRATION_8_9,
                        AppDatabaseMigrations.MIGRATION_9_10,
                        AppDatabaseMigrations.MIGRATION_10_11,
                        AppDatabaseMigrations.MIGRATION_11_12
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}


