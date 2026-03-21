package com.routeme.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ClientPropertyDao {
    @Query("SELECT * FROM client_properties WHERE clientId = :clientId LIMIT 1")
    suspend fun getPropertyForClient(clientId: String): ClientPropertyEntity?

    @Query("SELECT * FROM client_properties WHERE clientId IN (:clientIds)")
    suspend fun getPropertiesForClients(clientIds: List<String>): List<ClientPropertyEntity>

    @Query("SELECT * FROM client_properties")
    suspend fun getAllProperties(): List<ClientPropertyEntity>

    @Query("DELETE FROM client_properties")
    suspend fun deleteAllProperties()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProperty(entity: ClientPropertyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProperties(entities: List<ClientPropertyEntity>)
}
