package com.routeme.app

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AppDatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE clients ADD COLUMN cuSpringPending INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE clients ADD COLUMN cuFallPending INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE service_records ADD COLUMN arrivedAtMillis INTEGER")
            db.execSQL("ALTER TABLE service_records ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pending_write_backs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    clientName TEXT NOT NULL,
                    `column` TEXT NOT NULL,
                    value TEXT NOT NULL,
                    createdAtMillis INTEGER NOT NULL,
                    retryCount INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS non_client_stops (
                    stopId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    lat REAL NOT NULL,
                    lng REAL NOT NULL,
                    address TEXT,
                    arrivedAtMillis INTEGER NOT NULL,
                    departedAtMillis INTEGER,
                    durationMinutes INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE non_client_stops ADD COLUMN label TEXT")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS client_stop_events (
                    stopEventId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    clientId TEXT NOT NULL,
                    clientName TEXT NOT NULL,
                    arrivedAtMillis INTEGER,
                    endedAtMillis INTEGER NOT NULL,
                    durationMinutes INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    serviceTypes TEXT NOT NULL DEFAULT '',
                    cancelReason TEXT,
                    notes TEXT NOT NULL DEFAULT '',
                    lat REAL,
                    lng REAL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_client_stop_events_endedAtMillis ON client_stop_events(endedAtMillis)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_client_stop_events_clientId ON client_stop_events(clientId)")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS daily_weather (
                    dateMillis INTEGER NOT NULL PRIMARY KEY,
                    highTempF INTEGER,
                    lowTempF INTEGER,
                    windSpeedMph INTEGER,
                    windGustMph INTEGER,
                    precipitationInches REAL,
                    description TEXT NOT NULL,
                    fetchedAtMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE client_stop_events ADD COLUMN weatherTempF INTEGER")
            db.execSQL("ALTER TABLE client_stop_events ADD COLUMN weatherWindMph INTEGER")
            db.execSQL("ALTER TABLE client_stop_events ADD COLUMN weatherDesc TEXT")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS geocode_cache (
                    addressKey TEXT NOT NULL PRIMARY KEY,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    updatedAtMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE service_records ADD COLUMN amountUsed REAL")
            db.execSQL("ALTER TABLE service_records ADD COLUMN amountUsed2 REAL")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE daily_weather ADD COLUMN windDirection TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12
    )
}
