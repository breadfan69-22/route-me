package com.routeme.app.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.util.Log
import com.routeme.app.network.AerialEstimationConfig
import com.routeme.app.network.ParcelService

/**
 * Classifies pixels in an aerial image as turf, tree canopy, building, hardscape, or other
 * using HSV color-space thresholds and local texture variance.
 */
object TurfClassifier {

    private const val TAG = "TurfClassifier"

    data class ClassificationResult(
        val turfPixels: Int,
        val treePixels: Int,
        val buildingPixels: Int,
        val hardscapePixels: Int,
        val otherPixels: Int,
        val totalParcelPixels: Int,
        val sqFtPerPixel: Double
    )

    /**
     * Classify all pixels within the parcel polygon.
     * The bitmap must correspond to the parcel's bounding box (same coordinate space).
     */
    fun classify(
        bitmap: Bitmap,
        polygonRings: List<List<DoubleArray>>,
        bbox: ParcelService.BoundingBox,
        metersPerPixel: Double
    ): ClassificationResult {
        val w = bitmap.width
        val h = bitmap.height

        // Build a parcel mask: true = inside parcel polygon
        val mask = buildParcelMask(w, h, polygonRings, bbox)

        // Read all pixels once
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Convert all pixels to HSV once
        val hsvArray = Array(w * h) { FloatArray(3) }
        for (i in pixels.indices) {
            Color.colorToHSV(pixels[i], hsvArray[i])
        }

        // Compute local brightness variance (texture) and hue variance (species diversity)
        val variance = computeLocalVariance(hsvArray, w, h)
        val hueVariance = computeLocalHueVariance(hsvArray, w, h)

        var turf = 0; var tree = 0; var building = 0; var hardscape = 0; var other = 0; var total = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!mask[idx]) continue
                total++

                val hsv = hsvArray[idx]
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                val localVar = variance[idx]
                val hueVar = hueVariance[idx]

                when {
                    // Deep shadow: too dark to classify regardless of color
                    value < AerialEstimationConfig.DARK_SHADOW_VAL_MAX -> other++

                    // Hardscape (driveways, parking lots, concrete): very low saturation, uniform
                    sat < AerialEstimationConfig.HARDSCAPE_SAT_MAX &&
                        localVar < 0.02f -> hardscape++

                    // Building (rooftops): low saturation, low variance
                    sat < AerialEstimationConfig.BUILDING_SAT_MAX &&
                        localVar < 0.02f -> building++

                    // Tree canopy: green hue + high texture variance, OR high hue
                    // diversity WITH some brightness texture (rules out sparse
                    // ornamental trees over flat lawn)
                    hue in AerialEstimationConfig.TREE_HUE_RANGE &&
                        sat >= AerialEstimationConfig.TREE_SAT_MIN &&
                        (localVar >= AerialEstimationConfig.TREE_VARIANCE_MIN ||
                         (hueVar >= AerialEstimationConfig.TREE_HUE_VARIANCE_MIN &&
                          localVar >= AerialEstimationConfig.TREE_HUE_BRIGHTNESS_FLOOR)) -> tree++

                    // Turf (lawn): green hue, moderate saturation & brightness, smooth texture
                    hue in AerialEstimationConfig.TURF_HUE_RANGE &&
                        sat >= AerialEstimationConfig.TURF_SAT_MIN &&
                        value >= AerialEstimationConfig.TURF_VAL_MIN -> turf++

                    else -> other++
                }
            }
        }

        val sqFtPerPixel = metersPerPixel * metersPerPixel * 10.7639

        // Diagnostic: log hue-variance stats for turf vs tree pixels
        var turfHueVarSum = 0.0; var treeHueVarSum = 0.0; var treeBrightCount = 0; var treeHueCount = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!mask[idx]) continue
                val hsv = hsvArray[idx]
                if (hsv[0] in AerialEstimationConfig.TREE_HUE_RANGE && hsv[1] >= AerialEstimationConfig.TREE_SAT_MIN) {
                    val lv = variance[idx]; val hv = hueVariance[idx]
                    if (lv >= AerialEstimationConfig.TREE_VARIANCE_MIN) treeBrightCount++
                    if (hv >= AerialEstimationConfig.TREE_HUE_VARIANCE_MIN &&
                        lv >= AerialEstimationConfig.TREE_HUE_BRIGHTNESS_FLOOR) treeHueCount++
                }
            }
        }
        Log.d(TAG, "Tree detection breakdown: byBrightness=$treeBrightCount byHueDiversity=$treeHueCount total=$tree")

        Log.d(TAG, "Classification: turf=$turf tree=$tree building=$building " +
            "hardscape=$hardscape other=$other total=$total sqFtPerPx=%.2f".format(sqFtPerPixel))

        return ClassificationResult(
            turfPixels = turf,
            treePixels = tree,
            buildingPixels = building,
            hardscapePixels = hardscape,
            otherPixels = other,
            totalParcelPixels = total,
            sqFtPerPixel = sqFtPerPixel
        )
    }

    /**
     * Build a boolean mask from parcel polygon rings.
     * Converts Web Mercator ring coordinates to pixel coordinates relative to the bbox,
     * then uses Android Path/Canvas to rasterize the polygon.
     */
    private fun buildParcelMask(
        width: Int,
        height: Int,
        rings: List<List<DoubleArray>>,
        bbox: ParcelService.BoundingBox
    ): BooleanArray {
        val bboxW = bbox.xmax - bbox.xmin
        val bboxH = bbox.ymax - bbox.ymin

        // Use ARGB_8888 — ALPHA_8 has unreliable getPixels() on many devices
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        val path = Path()

        for (ring in rings) {
            if (ring.isEmpty()) continue
            val first = ring[0]
            path.moveTo(
                ((first[0] - bbox.xmin) / bboxW * width).toFloat(),
                // Y is inverted: Web Mercator Y increases upward, pixel Y increases downward
                ((bbox.ymax - first[1]) / bboxH * height).toFloat()
            )
            for (i in 1 until ring.size) {
                val pt = ring[i]
                path.lineTo(
                    ((pt[0] - bbox.xmin) / bboxW * width).toFloat(),
                    ((bbox.ymax - pt[1]) / bboxH * height).toFloat()
                )
            }
            path.close()
        }

        val paint = android.graphics.Paint().apply {
            color = Color.WHITE  // 0xFFFFFFFF — fully opaque white
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = false
        }
        canvas.drawPath(path, paint)

        val maskPixels = IntArray(width * height)
        maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)
        maskBitmap.recycle()

        val insideCount = maskPixels.count { it != 0 }
        Log.d(TAG, "Parcel mask: ${insideCount} of ${maskPixels.size} pixels inside polygon " +
            "(${insideCount * 100 / maskPixels.size.coerceAtLeast(1)}%)")

        return BooleanArray(maskPixels.size) { maskPixels[it] != 0 }
    }

    /**
     * Compute local hue variance among green-saturated neighbors in a larger kernel.
     * Manicured lawns have uniform green hue; woods have mixed greens from different species.
     * Only counts neighbors that are green & saturated to avoid noise from buildings/roads.
     */
    private fun computeLocalHueVariance(hsvArray: Array<FloatArray>, w: Int, h: Int): FloatArray {
        val result = FloatArray(w * h)
        val k = AerialEstimationConfig.HUE_VARIANCE_KERNEL_SIZE / 2
        val hueRange = AerialEstimationConfig.TURF_HUE_RANGE
        val satMin = AerialEstimationConfig.TURF_SAT_MIN
        val minNeighbors = AerialEstimationConfig.HUE_VARIANCE_MIN_NEIGHBORS

        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var sumSq = 0f
                var count = 0

                val yStart = maxOf(0, y - k)
                val yEnd = minOf(h - 1, y + k)
                val xStart = maxOf(0, x - k)
                val xEnd = minOf(w - 1, x + k)

                for (ny in yStart..yEnd) {
                    for (nx in xStart..xEnd) {
                        val nHsv = hsvArray[ny * w + nx]
                        // Only count neighbors with meaningful green hue
                        if (nHsv[0] in hueRange && nHsv[1] >= satMin) {
                            val hue = nHsv[0]
                            sum += hue
                            sumSq += hue * hue
                            count++
                        }
                    }
                }

                if (count >= minNeighbors) {
                    val mean = sum / count
                    result[y * w + x] = (sumSq / count) - (mean * mean)
                }
                // else leave at 0 — not enough green context to judge
            }
        }
        return result
    }

    /**
     * Compute local brightness variance in a small kernel around each pixel.
     * Uses the Value (brightness) channel of HSV to detect texture.
     */
    private fun computeLocalVariance(hsvArray: Array<FloatArray>, w: Int, h: Int): FloatArray {
        val result = FloatArray(w * h)
        val k = AerialEstimationConfig.TEXTURE_KERNEL_SIZE / 2  // radius

        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var sumSq = 0f
                var count = 0

                val yStart = maxOf(0, y - k)
                val yEnd = minOf(h - 1, y + k)
                val xStart = maxOf(0, x - k)
                val xEnd = minOf(w - 1, x + k)

                for (ny in yStart..yEnd) {
                    for (nx in xStart..xEnd) {
                        val v = hsvArray[ny * w + nx][2] // Value channel
                        sum += v
                        sumSq += v * v
                        count++
                    }
                }

                val mean = sum / count
                result[y * w + x] = (sumSq / count) - (mean * mean)
            }
        }
        return result
    }
}
