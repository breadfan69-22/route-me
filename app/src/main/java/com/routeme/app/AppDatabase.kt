package com.routeme.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// ─────────────────────────────────────────────────────────────
// Room Entities (database tables)
// ─────────────────────────────────────────────────────────────

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val zone: String,
    val notes: String,
    val subscribedSteps: String,  // stored as comma-separated: "1,2,3,4,5,6"
    val hasGrub: Boolean,
    val mowDayOfWeek: Int,
    val lawnSizeSqFt: Int,
    val sunShade: String,
    val terrain: String,
    val windExposure: String,
    val cuSpringPending: Boolean,
    val cuFallPending: Boolean,
    val latitude: Double?,
    val longitude: Double?
)

@Entity(
    tableName = "service_records",
    foreignKeys = [ForeignKey(
        entity = ClientEntity::class,
        parentColumns = ["id"],
        childColumns = ["clientId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("clientId")]
)
data class ServiceRecordEntity(
    @PrimaryKey(autoGenerate = true) val recordId: Long = 0,
    val clientId: String,
    val serviceType: String,  // stored as enum name: "ROUND_1", "GRUB", etc.
    val arrivedAtMillis: Long? = null,
    val completedAtMillis: Long,
    val durationMinutes: Long,
    val lat: Double?,
    val lng: Double?,
    val notes: String = ""
)

@Entity(tableName = "pending_write_backs")
data class PendingWriteBackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientName: String,
    val column: String,
    val value: String,
    val createdAtMillis: Long,
    val retryCount: Int = 0
)

@Entity(tableName = "non_client_stops")
data class NonClientStopEntity(
    @PrimaryKey(autoGenerate = true) val stopId: Long = 0,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val arrivedAtMillis: Long,
    val departedAtMillis: Long? = null,
    val durationMinutes: Long = 0,
    val label: String? = null
)

// ─────────────────────────────────────────────────────────────
// Data class with embedded records (for queries)
// ─────────────────────────────────────────────────────────────

data class ClientWithRecords(
    @Embedded val client: ClientEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "clientId"
    )
    val records: List<ServiceRecordEntity>
)

data class DailyRecordRow(
    val clientId: String,
    val clientName: String,
    val serviceType: String,
    val arrivedAtMillis: Long?,
    val completedAtMillis: Long,
    val durationMinutes: Long,
    val notes: String
)

// ─────────────────────────────────────────────────────────────
// DAO (Data Access Object)
// ─────────────────────────────────────────────────────────────

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
    @Query("SELECT sr.*, c.name AS clientName FROM service_records sr INNER JOIN clients c ON sr.clientId = c.id WHERE sr.completedAtMillis >= :startMillis AND sr.completedAtMillis < :endMillis ORDER BY sr.completedAtMillis")
    suspend fun getRecordsForDateRange(startMillis: Long, endMillis: Long): List<DailyRecordRow>

    @Query("SELECT DISTINCT (completedAtMillis / 86400000) * 86400000 AS dayMillis FROM service_records ORDER BY dayMillis DESC")
    suspend fun getDistinctServiceDates(): List<Long>

    @Query("SELECT id FROM clients")
    suspend fun getAllClientIds(): List<String>

    @Query("SELECT id FROM clients WHERE name = :name LIMIT 1")
    suspend fun getClientIdByName(name: String): String?

    @Query("DELETE FROM service_records WHERE clientId = :clientId AND completedAtMillis = :completedAtMillis")
    suspend fun deleteServiceRecord(clientId: String, completedAtMillis: Long)

    // ─── Pending write-back queue ───

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

    @Query("SELECT DISTINCT (arrivedAtMillis / 86400000) * 86400000 AS dayMillis FROM non_client_stops ORDER BY dayMillis DESC")
    suspend fun getDistinctStopDates(): List<Long>
}

// ─────────────────────────────────────────────────────────────
// Room Database
// ─────────────────────────────────────────────────────────────

@Database(
    entities = [ClientEntity::class, ServiceRecordEntity::class, PendingWriteBackEntity::class, NonClientStopEntity::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun nonClientStopDao(): NonClientStopDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clients ADD COLUMN cuSpringPending INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clients ADD COLUMN cuFallPending INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE service_records ADD COLUMN arrivedAtMillis INTEGER")
                db.execSQL("ALTER TABLE service_records ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_write_backs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        clientName TEXT NOT NULL,
                        `column` TEXT NOT NULL,
                        value TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        retryCount INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS non_client_stops (
                        stopId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        lat REAL NOT NULL,
                        lng REAL NOT NULL,
                        address TEXT,
                        arrivedAtMillis INTEGER NOT NULL,
                        departedAtMillis INTEGER,
                        durationMinutes INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE non_client_stops ADD COLUMN label TEXT")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "routeme_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Conversion helpers: Entity <-> Domain model
// ─────────────────────────────────────────────────────────────

fun Client.toEntity(): ClientEntity = ClientEntity(
    id = id,
    name = name,
    address = address,
    zone = zone,
    notes = notes,
    subscribedSteps = subscribedSteps.sorted().joinToString(","),
    hasGrub = hasGrub,
    mowDayOfWeek = mowDayOfWeek,
    lawnSizeSqFt = lawnSizeSqFt,
    sunShade = sunShade,
    terrain = terrain,
    windExposure = windExposure,
    cuSpringPending = cuSpringPending,
    cuFallPending = cuFallPending,
    latitude = latitude,
    longitude = longitude
)

fun ServiceRecord.toEntity(clientId: String): ServiceRecordEntity = ServiceRecordEntity(
    clientId = clientId,
    serviceType = serviceType.name,
    arrivedAtMillis = arrivedAtMillis,
    completedAtMillis = completedAtMillis,
    durationMinutes = durationMinutes,
    lat = lat,
    lng = lng,
    notes = notes
)

fun NonClientStopEntity.toDomain(): NonClientStop = NonClientStop(
    id = stopId,
    lat = lat,
    lng = lng,
    address = address,
    arrivedAtMillis = arrivedAtMillis,
    departedAtMillis = departedAtMillis,
    durationMinutes = durationMinutes,
    label = label
)

fun NonClientStop.toEntity(): NonClientStopEntity = NonClientStopEntity(
    stopId = id,
    lat = lat,
    lng = lng,
    address = address,
    arrivedAtMillis = arrivedAtMillis,
    departedAtMillis = departedAtMillis,
    durationMinutes = durationMinutes,
    label = label
)

fun ClientWithRecords.toDomain(): Client = Client(
    id = client.id,
    name = client.name,
    address = client.address,
    zone = client.zone,
    notes = client.notes,
    subscribedSteps = client.subscribedSteps
        .split(",")
        .filter { it.isNotBlank() }
        .mapNotNull { it.toIntOrNull() }
        .toSet(),
    hasGrub = client.hasGrub,
    mowDayOfWeek = client.mowDayOfWeek,
    lawnSizeSqFt = client.lawnSizeSqFt,
    sunShade = client.sunShade,
    terrain = client.terrain,
    windExposure = client.windExposure,
    cuSpringPending = client.cuSpringPending,
    cuFallPending = client.cuFallPending,
    latitude = client.latitude,
    longitude = client.longitude,
    records = records.map { rec ->
        ServiceRecord(
            serviceType = ServiceType.valueOf(rec.serviceType),
            arrivedAtMillis = rec.arrivedAtMillis,
            completedAtMillis = rec.completedAtMillis,
            durationMinutes = rec.durationMinutes,
            lat = rec.lat,
            lng = rec.lng,
            notes = rec.notes
        )
    }.toMutableList()
)


