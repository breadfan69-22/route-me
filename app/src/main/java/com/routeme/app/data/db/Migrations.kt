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

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6
    )
}
