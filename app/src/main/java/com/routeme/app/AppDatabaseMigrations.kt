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

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS client_properties (
                    clientId TEXT NOT NULL PRIMARY KEY,
                    lawnSizeSqFt INTEGER NOT NULL DEFAULT 0,
                    sunShade TEXT NOT NULL DEFAULT 'UNKNOWN',
                    windExposure TEXT NOT NULL DEFAULT 'UNKNOWN',
                    hasSteepSlopes INTEGER NOT NULL DEFAULT 0,
                    hasIrrigation INTEGER NOT NULL DEFAULT 0,
                    propertyNotes TEXT NOT NULL DEFAULT '',
                    updatedAtMillis INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(clientId) REFERENCES clients(id)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                INSERT OR REPLACE INTO client_properties (
                    clientId,
                    lawnSizeSqFt,
                    sunShade,
                    windExposure,
                    hasSteepSlopes,
                    hasIrrigation,
                    propertyNotes,
                    updatedAtMillis
                )
                SELECT
                    id,
                    CASE WHEN lawnSizeSqFt > 0 THEN lawnSizeSqFt ELSE 0 END,
                    CASE
                        WHEN LOWER(TRIM(sunShade)) = 'full sun' THEN 'FULL_SUN'
                        WHEN LOWER(TRIM(sunShade)) = 'partial shade' THEN 'PARTIAL_SHADE'
                        WHEN LOWER(TRIM(sunShade)) = 'full shade' THEN 'FULL_SHADE'
                        ELSE 'UNKNOWN'
                    END,
                    CASE
                        WHEN LOWER(TRIM(windExposure)) = 'exposed' THEN 'EXPOSED'
                        WHEN LOWER(TRIM(windExposure)) = 'sheltered' THEN 'SHELTERED'
                        WHEN LOWER(TRIM(windExposure)) = 'mixed' THEN 'MIXED'
                        ELSE 'UNKNOWN'
                    END,
                    CASE WHEN TRIM(COALESCE(terrain, '')) <> '' THEN 1 ELSE 0 END,
                    0,
                    '',
                    0
                FROM clients
                WHERE lawnSizeSqFt > 0
                   OR TRIM(COALESCE(sunShade, '')) <> ''
                   OR TRIM(COALESCE(windExposure, '')) <> ''
                   OR TRIM(COALESCE(terrain, '')) <> ''
                """.trimIndent()
            )
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS forecast_days (
                    dateMillis INTEGER NOT NULL PRIMARY KEY,
                    highTempF INTEGER NOT NULL,
                    lowTempF INTEGER NOT NULL,
                    windSpeedMph INTEGER NOT NULL,
                    windGustMph INTEGER,
                    precipProbabilityPct INTEGER NOT NULL,
                    shortForecast TEXT NOT NULL,
                    detailedForecast TEXT NOT NULL,
                    fetchedAtMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS saved_week_plan (
                    id INTEGER NOT NULL PRIMARY KEY,
                    planJson TEXT NOT NULL,
                    generatedAtMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS truck_inventory (
                    productType TEXT NOT NULL PRIMARY KEY,
                    currentStock REAL NOT NULL DEFAULT 0,
                    capacity REAL NOT NULL DEFAULT 0,
                    unit TEXT NOT NULL DEFAULT 'bags',
                    lastUpdatedMillis INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                DELETE FROM client_stop_events
                WHERE arrivedAtMillis IS NOT NULL
                  AND EXISTS (
                      SELECT 1
                      FROM client_stop_events newer
                      WHERE newer.clientId = client_stop_events.clientId
                        AND newer.status = client_stop_events.status
                        AND newer.arrivedAtMillis = client_stop_events.arrivedAtMillis
                        AND (
                            newer.endedAtMillis > client_stop_events.endedAtMillis
                            OR (
                                newer.endedAtMillis = client_stop_events.endedAtMillis
                                AND newer.stopEventId > client_stop_events.stopEventId
                            )
                        )
                  )
                """.trimIndent()
            )
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
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17
    )
}
