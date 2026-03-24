package com.routeme.app

// ─── Fixed spray application method rates ──────────────────
/** Spray Hose: 1 gallon covers 1,000 sqft. */
const val HOSE_SQFT_PER_GAL = 1_000.0

/** Perma-Green (ride-on): 1 gallon covers 5,500 sqft. */
const val PG_SQFT_PER_GAL = 5_500.0

/** Granular application default: pounds per 1,000 sqft. */
const val GRANULAR_LBS_PER_1000_SQFT = 3.5

/** Granular application default: bags per 1,000 sqft (50 lb bags). */
const val GRANULAR_BAGS_PER_1000_SQFT = GRANULAR_LBS_PER_1000_SQFT / LBS_PER_BAG

/** Fallback lot size when property sqft is missing. */
const val DEFAULT_SQFT_ESTIMATE = 10_000

/** Whether a service type is a spray (liquid) step. */
val ServiceType.isSpray: Boolean
    get() = this == ServiceType.ROUND_2 || this == ServiceType.ROUND_5

/** Unit label for the service type's product measurement. */
val ServiceType.unitLabel: String
    get() = if (isSpray) "gal" else "lbs"

/**
 * Estimate lawn sqft from spray method usage.
 * Both methods can be used on one jobsite simultaneously.
 * amountUsed = Hose gallons, amountUsed2 = PG gallons.
 */
fun estimateSpraySqFt(hoseGal: Double?, pgGal: Double?): Int? {
    val hose = (hoseGal ?: 0.0) * HOSE_SQFT_PER_GAL
    val pg = (pgGal ?: 0.0) * PG_SQFT_PER_GAL
    val total = hose + pg
    return if (total > 0.0) total.toInt() else null
}

/**
 * Estimate lawn sqft from granular product usage.
 * amountUsed = lbs used, rate = lbs/1000sqft for that product.
 */
fun estimateGranularSqFt(lbsUsed: Double?, rateLbsPerThousand: Double): Int? {
    if (lbsUsed == null || lbsUsed <= 0.0 || rateLbsPerThousand <= 0.0) return null
    return ((lbsUsed / rateLbsPerThousand) * 1000.0).toInt()
}

/** Estimate granular consumption in 50-lb bags using the default application rate. */
fun estimateGranularConsumptionBags(sqFt: Int): Double =
    (sqFt / 1000.0) * GRANULAR_BAGS_PER_1000_SQFT

/** Estimate granular consumption in 50-lb bags using a per-product rate (lbs/1000sqft). */
fun estimateGranularConsumptionBags(sqFt: Int, rateLbsPerThousand: Double): Double =
    (sqFt / 1000.0) * (rateLbsPerThousand / LBS_PER_BAG)

// ─── Property stats collected on-site ──────────────────────
data class PropertyInput(
    val sunShade: String = "",
    val windExposure: String = "",
    val steepSlopes: String = "",
    val irrigation: String = "",
) {
    val hasAnyData: Boolean
        get() = sunShade.isNotEmpty() || windExposure.isNotEmpty() ||
                steepSlopes.isNotEmpty() || irrigation.isNotEmpty()

    companion object {
        val SUN_SHADE_OPTIONS = listOf("", "Full Sun", "Partial Shade", "Full Shade")
        val WIND_OPTIONS = listOf("", "Exposed", "Sheltered", "Mixed")
        val YES_NO_OPTIONS = listOf("", "Yes", "No")
    }
}
