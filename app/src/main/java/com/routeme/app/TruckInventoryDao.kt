package com.routeme.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TruckInventoryDao {
    @Query("SELECT * FROM truck_inventory")
    suspend fun getAll(): List<TruckInventoryEntity>

    @Query("SELECT * FROM truck_inventory WHERE productType = :type")
    suspend fun getByType(type: String): TruckInventoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TruckInventoryEntity)

    @Query("UPDATE truck_inventory SET currentStock = MAX(0, currentStock - :amount), lastUpdatedMillis = :now WHERE productType = :type")
    suspend fun deduct(type: String, amount: Double, now: Long)

    @Query("UPDATE truck_inventory SET currentStock = MIN(capacity, currentStock + :bagsAdded), lastUpdatedMillis = :now WHERE productType = :type")
    suspend fun addStock(type: String, bagsAdded: Double, now: Long)

    @Query("UPDATE truck_inventory SET currentStock = MIN(capacity, MAX(0, :bags)), lastUpdatedMillis = :now WHERE productType = :type")
    suspend fun setStock(type: String, bags: Double, now: Long)
}
