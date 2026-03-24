package com.routeme.app.domain

import com.routeme.app.DEFAULT_SQFT_ESTIMATE
import com.routeme.app.LBS_PER_BAG
import com.routeme.app.ProductType
import com.routeme.app.ServiceType
import com.routeme.app.TruckInventory
import com.routeme.app.TruckInventoryDao
import com.routeme.app.TruckInventoryEntity
import com.routeme.app.estimateGranularConsumptionBags
import com.routeme.app.isSpray
import kotlin.math.roundToInt

class TruckInventoryUseCase(
    private val inventoryDao: TruckInventoryDao,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val GRANULAR_CAPACITY_BAGS = 50.0
        const val SPRAY_CONCENTRATE_CAPACITY_GAL = 200.0
    }

    suspend fun loadInventory(): Map<ProductType, TruckInventory> {
        ensureDefaults()
        return inventoryDao.getAll()
            .mapNotNull { entity ->
                val type = runCatching { ProductType.valueOf(entity.productType) }.getOrNull() ?: return@mapNotNull null
                type to entity.toDomain(type)
            }
            .toMap()
    }

    suspend fun deductForService(
        serviceType: ServiceType,
        amountUsed: Double?,
        amountUsed2: Double?,
        clientSqFt: Int? = null,
        granularRate: Double? = null
    ) {
        ensureDefaults()
        val now = nowProvider()
        if (serviceType.isSpray) {
            val totalSprayUsed = (amountUsed ?: 0.0) + (amountUsed2 ?: 0.0)
            if (totalSprayUsed > 0.0) {
                inventoryDao.deduct(ProductType.SPRAY_CONCENTRATE.name, totalSprayUsed, now)
            }
            return
        }

        // Priority 1: user typed actual lbs used → convert to bags
        // Priority 2: derive from client's recorded lawn size via the rate formula
        // Priority 3: hardcoded fallback sqft (should rarely trigger)
        val granularBagsUsed = when {
            amountUsed != null && amountUsed > 0.0 -> amountUsed / LBS_PER_BAG
            clientSqFt != null && clientSqFt > 0 && granularRate != null && granularRate > 0.0 ->
                estimateGranularConsumptionBags(clientSqFt, granularRate)
            clientSqFt != null && clientSqFt > 0 -> estimateGranularConsumptionBags(clientSqFt)
            granularRate != null && granularRate > 0.0 ->
                estimateGranularConsumptionBags(DEFAULT_SQFT_ESTIMATE, granularRate)
            else -> estimateGranularConsumptionBags(DEFAULT_SQFT_ESTIMATE)
        }

        if (granularBagsUsed > 0.0) {
            inventoryDao.deduct(ProductType.GRANULAR.name, granularBagsUsed, now)
        }
    }

    suspend fun addBags(productType: ProductType, bagsAdded: Int) {
        ensureDefaults()
        val amount = bagsAdded.toDouble().coerceAtLeast(0.0)
        inventoryDao.addStock(productType.name, amount, nowProvider())
    }

    suspend fun setStock(productType: ProductType, exactBags: Int) {
        ensureDefaults()
        val amount = exactBags.toDouble().coerceAtLeast(0.0)
        inventoryDao.setStock(productType.name, amount, nowProvider())
    }

    private suspend fun ensureDefaults() {
        val now = nowProvider()
        if (inventoryDao.getByType(ProductType.GRANULAR.name) == null) {
            inventoryDao.upsert(
                TruckInventoryEntity(
                    productType = ProductType.GRANULAR.name,
                    currentStock = GRANULAR_CAPACITY_BAGS,
                    capacity = GRANULAR_CAPACITY_BAGS,
                    unit = "bags",
                    lastUpdatedMillis = now
                )
            )
        }
        if (inventoryDao.getByType(ProductType.SPRAY_CONCENTRATE.name) == null) {
            inventoryDao.upsert(
                TruckInventoryEntity(
                    productType = ProductType.SPRAY_CONCENTRATE.name,
                    currentStock = SPRAY_CONCENTRATE_CAPACITY_GAL,
                    capacity = SPRAY_CONCENTRATE_CAPACITY_GAL,
                    unit = "gal",
                    lastUpdatedMillis = now
                )
            )
        }
    }

    private fun TruckInventoryEntity.toDomain(type: ProductType): TruckInventory {
        val pct = if (capacity <= 0.0) 0 else ((currentStock / capacity) * 100.0).roundToInt().coerceIn(0, 100)
        return TruckInventory(
            productType = type,
            currentStock = currentStock,
            capacity = capacity,
            unit = unit,
            pctRemaining = pct
        )
    }
}
