package com.routeme.app

const val LBS_PER_BAG = 50.0

data class TruckInventory(
    val productType: ProductType,
    val currentStock: Double,
    val capacity: Double,
    val unit: String,
    val pctRemaining: Int
) {
    val isLow: Boolean get() = pctRemaining < 20

    val bagsRemaining: Int
        get() = currentStock.toInt()

    val lbsRemaining: Int
        get() = (currentStock * LBS_PER_BAG).toInt()
}
