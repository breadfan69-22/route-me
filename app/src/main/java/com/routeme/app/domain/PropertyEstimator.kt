package com.routeme.app.domain

import android.util.Log
import com.routeme.app.network.AerialEstimationConfig
import com.routeme.app.network.AerialImageService
import com.routeme.app.network.ParcelService
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Orchestrates the aerial estimation pipeline for a single property:
 * parcel lookup → aerial image fetch → pixel classification → result.
 *
 * Must be called on a background thread (all network I/O is synchronous).
 */
object PropertyEstimator {

    private const val TAG = "PropertyEstimator"

    enum class SunShade { FULL_SUN, PARTIAL_SHADE, FULL_SHADE }
    enum class Confidence { HIGH, MEDIUM, LOW }

    data class EstimationResult(
        val lawnSizeSqFt: Int,
        val sunShade: SunShade,
        val confidence: Confidence,
        val notes: String,
        val parcelId: String?,
        val assessedAcres: Double?,
        val imageryDateStr: String?,
        // Raw classification data for debug/inspection
        val turfPixels: Int = 0,
        val treePixels: Int = 0,
        val buildingPixels: Int = 0,
        val hardscapePixels: Int = 0,
        val otherPixels: Int = 0,
        val totalParcelPixels: Int = 0
    )

    /**
     * Run the full estimation pipeline for a single lat/lng.
     * Never throws — returns a LOW confidence result on any failure.
     */
    fun estimate(lat: Double, lng: Double, clientName: String = ""): EstimationResult {
        val label = clientName.ifBlank { "$lat,$lng" }
        Log.d(TAG, "Starting estimation for $label")

        // Step 1: Parcel lookup
        val parcel = ParcelService.queryByLocation(lat, lng)
        if (parcel == null) {
            Log.w(TAG, "No parcel found for $label")
            return EstimationResult(
                lawnSizeSqFt = 0,
                sunShade = SunShade.FULL_SUN,
                confidence = Confidence.LOW,
                notes = "No parcel found at coordinates.",
                parcelId = null,
                assessedAcres = null,
                imageryDateStr = null
            )
        }

        val effectiveAcres = if (parcel.gisAcres > 0.0) parcel.gisAcres else parcel.assessedAcres
        val isLargeParcel = effectiveAcres > AerialEstimationConfig.LARGE_PARCEL_YARD_CAP_ACRES

        // For very large parcels (farms), create a synthetic yard-sized polygon
        // centered on the house coordinates instead of using the full farm polygon.
        val imageryBbox: ParcelService.BoundingBox
        val classificationPolygon: List<List<DoubleArray>>
        val yardCapAcres: Double  // effective acreage cap for sqft computation

        if (isLargeParcel) {
            val latRad = lat * Math.PI / 180.0
            val wmX = lng * Math.PI / 180.0 * 6378137.0
            val wmY = ln(tan(Math.PI / 4.0 + latRad / 2.0)) * 6378137.0
            val cosLat = cos(latRad)
            val halfSide = AerialEstimationConfig.LARGE_PARCEL_YARD_SIDE_METERS / 2.0 / cosLat

            imageryBbox = ParcelService.BoundingBox(
                xmin = wmX - halfSide, ymin = wmY - halfSide,
                xmax = wmX + halfSide, ymax = wmY + halfSide
            )
            classificationPolygon = listOf(listOf(
                doubleArrayOf(wmX - halfSide, wmY - halfSide),
                doubleArrayOf(wmX + halfSide, wmY - halfSide),
                doubleArrayOf(wmX + halfSide, wmY + halfSide),
                doubleArrayOf(wmX - halfSide, wmY + halfSide),
                doubleArrayOf(wmX - halfSide, wmY - halfSide)
            ))
            val side = AerialEstimationConfig.LARGE_PARCEL_YARD_SIDE_METERS
            yardCapAcres = (side * side) / 4046.86
            Log.d(TAG, "Large parcel (${effectiveAcres}ac) — using ${side}m yard window (${"%.1f".format(yardCapAcres)}ac cap)")
        } else {
            imageryBbox = parcel.boundingBox
            classificationPolygon = parcel.polygonRings
            yardCapAcres = effectiveAcres
        }

        // Step 2: Aerial imagery
        val aerial = AerialImageService.fetchImagery(imageryBbox)
        if (aerial == null) {
            Log.w(TAG, "Imagery fetch failed for $label, falling back to assessed acreage")
            val fallbackSqFt = (yardCapAcres * 43560 * 0.30).toInt()
            return EstimationResult(
                lawnSizeSqFt = fallbackSqFt,
                sunShade = SunShade.FULL_SUN,
                confidence = Confidence.LOW,
                notes = "Imagery unavailable. Fallback: 30% of ${"%.2f".format(yardCapAcres)} acres " +
                    "(${parcel.assessedAcres} assessed). Parcel ${parcel.parcelId}.",
                parcelId = parcel.parcelId,
                assessedAcres = parcel.assessedAcres,
                imageryDateStr = null
            )
        }

        // Step 3: Classify
        val classification = TurfClassifier.classify(
            bitmap = aerial.bitmap,
            polygonRings = classificationPolygon,
            bbox = aerial.actualBbox,
            metersPerPixel = aerial.metersPerPixel
        )

        // Recycle bitmap now that classification is done
        aerial.bitmap.recycle()

        // Step 4: Derive results
        val rawLawnSqFt = (classification.turfPixels * classification.sqFtPerPixel).toInt()
        val capSqFt = (yardCapAcres * 43560).toInt()
        val lawnSqFt = rawLawnSqFt.coerceAtMost(capSqFt)
        if (rawLawnSqFt > capSqFt) {
            Log.w(TAG, "Lawn estimate $rawLawnSqFt sqft exceeded cap $capSqFt sqft — capped")
        }
        val totalPx = classification.totalParcelPixels.coerceAtLeast(1)
        val turfRatio = classification.turfPixels.toDouble() / totalPx
        val treeRatio = classification.treePixels.toDouble() / totalPx

        val sunShade = when {
            treeRatio < AerialEstimationConfig.FULL_SUN_TREE_RATIO -> SunShade.FULL_SUN
            treeRatio < AerialEstimationConfig.PARTIAL_SHADE_TREE_RATIO -> SunShade.PARTIAL_SHADE
            else -> SunShade.FULL_SHADE
        }

        // Imagery age
        val imageryDateStr = aerial.imageryDate?.let { d ->
            val s = d.toString()
            if (s.length == 8) "${s.substring(0,4)}-${s.substring(4,6)}-${s.substring(6,8)}" else null
        }
        val imageryAgeYears = aerial.imageryDate?.let { d ->
            val s = d.toString()
            if (s.length >= 4) {
                val year = s.substring(0, 4).toIntOrNull() ?: return@let null
                LocalDate.now().year - year
            } else null
        }

        // Confidence
        var confidence = Confidence.MEDIUM
        var confidenceNotes = mutableListOf<String>()

        if (imageryAgeYears != null && imageryAgeYears > AerialEstimationConfig.IMAGERY_AGE_LOW_CONFIDENCE_YEARS) {
            confidence = Confidence.LOW
            confidenceNotes.add("imagery ${imageryAgeYears}yr old")
        }
        if (turfRatio < AerialEstimationConfig.LOW_TURF_RATIO) {
            confidence = Confidence.LOW
            confidenceNotes.add("very little turf detected (${(turfRatio * 100).toInt()}%)")
        }
        if (turfRatio > AerialEstimationConfig.HIGH_TURF_RATIO &&
            confidence != Confidence.LOW) {
            confidence = Confidence.HIGH
        }

        // Large-lot guard: likely includes crop/field if huge green area on big lot
        if (turfRatio > AerialEstimationConfig.LARGE_LOT_TURF_RATIO &&
            effectiveAcres > AerialEstimationConfig.LARGE_LOT_ACRES_THRESHOLD) {
            if (confidence == Confidence.HIGH) confidence = Confidence.MEDIUM
            confidenceNotes.add("large green area on ${effectiveAcres}ac lot — may include non-turf vegetation")
        }

        val notes = buildString {
            append("Auto-estimated from aerial imagery")
            if (imageryDateStr != null) append(" ($imageryDateStr)")
            append(". Parcel ${parcel.parcelId}, ${parcel.assessedAcres} assessed acres. ")
            append("Detected ${lawnSqFt.formatWithCommas()} sqft turf (${(turfRatio * 100).toInt()}% of lot)")
            if (confidenceNotes.isNotEmpty()) {
                append(". Note: ${confidenceNotes.joinToString("; ")}")
            }
            append(".")
        }

        Log.d(TAG, "Estimation complete for $label: $lawnSqFt sqft, $sunShade, $confidence")

        return EstimationResult(
            lawnSizeSqFt = lawnSqFt,
            sunShade = sunShade,
            confidence = confidence,
            notes = notes,
            parcelId = parcel.parcelId,
            assessedAcres = parcel.assessedAcres,
            imageryDateStr = imageryDateStr,
            turfPixels = classification.turfPixels,
            treePixels = classification.treePixels,
            buildingPixels = classification.buildingPixels,
            hardscapePixels = classification.hardscapePixels,
            otherPixels = classification.otherPixels,
            totalParcelPixels = classification.totalParcelPixels
        )
    }

    private fun Int.formatWithCommas(): String =
        "%,d".format(this)
}
