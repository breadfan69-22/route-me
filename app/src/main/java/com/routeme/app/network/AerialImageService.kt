package com.routeme.app.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp

/**
 * Fetches aerial imagery tiles and source metadata from ArcGIS World Imagery.
 * Must be called on a background thread.
 */
object AerialImageService {

    private const val TAG = "AerialImageService"

    data class AerialImageResult(
        val bitmap: Bitmap,
        val metersPerPixel: Double,
        val imageryDate: Int?,       // SRC_DATE (yyyyMMdd) or null
        val imagerySource: String?,  // SRC_DESC e.g. "WV02"
        val actualBbox: ParcelService.BoundingBox // padded bbox used for the image request
    )

    /**
     * Fetch a 512x512 aerial image covering the given bounding box (Web Mercator / EPSG:3857).
     * Also queries imagery metadata for the center of the bbox.
     */
    fun fetchImagery(bbox: ParcelService.BoundingBox): AerialImageResult? {
        val paddedBbox = padBbox(bbox)
        val bitmap = fetchTile(paddedBbox) ?: return null
        val mpp = computeMetersPerPixel(paddedBbox)
        val (date, source) = fetchImageryMetadata(paddedBbox)

        Log.d(TAG, "Aerial image fetched: ${bitmap.width}x${bitmap.height}, " +
            "mpp=%.3f, date=$date, source=$source".format(mpp))

        return AerialImageResult(
            bitmap = bitmap,
            metersPerPixel = mpp,
            imageryDate = date,
            imagerySource = source,
            actualBbox = paddedBbox
        )
    }

    private fun padBbox(bbox: ParcelService.BoundingBox): ParcelService.BoundingBox {
        val minSpan = AerialEstimationConfig.MIN_BBOX_SPAN_METERS
        val maxSpan = AerialEstimationConfig.MAX_BBOX_SPAN_METERS
        val dx = bbox.xmax - bbox.xmin
        val dy = bbox.ymax - bbox.ymin
        val cx = (bbox.xmin + bbox.xmax) / 2.0
        val cy = (bbox.ymin + bbox.ymax) / 2.0

        // Clamp to max first (large parcels like WMU campus)
        val clampedDx = dx.coerceIn(minSpan, maxSpan)
        val clampedDy = dy.coerceIn(minSpan, maxSpan)

        if (clampedDx == dx && clampedDy == dy) return bbox

        val action = if (clampedDx < dx || clampedDy < dy) "Clamping" else "Padding"
        Log.d(TAG, "$action bbox: ${dx.toInt()}x${dy.toInt()}m → ${clampedDx.toInt()}x${clampedDy.toInt()}m")

        return ParcelService.BoundingBox(
            xmin = cx - clampedDx / 2.0,
            ymin = cy - clampedDy / 2.0,
            xmax = cx + clampedDx / 2.0,
            ymax = cy + clampedDy / 2.0
        )
    }

    private fun fetchTile(bbox: ParcelService.BoundingBox): Bitmap? {
        return try {
            val size = AerialEstimationConfig.IMAGE_SIZE
            val url = buildString {
                append(AerialEstimationConfig.WORLD_IMAGERY_EXPORT)
                append("?bbox=${bbox.xmin},${bbox.ymin},${bbox.xmax},${bbox.ymax}")
                append("&bboxSR=${AerialEstimationConfig.IMAGERY_SPATIAL_REF}")
                append("&size=$size,$size")
                append("&imageSR=${AerialEstimationConfig.IMAGERY_SPATIAL_REF}")
                append("&format=png32")
                append("&f=pjson")
            }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = AerialEstimationConfig.HTTP_CONNECT_TIMEOUT_MS
            connection.readTimeout = AerialEstimationConfig.HTTP_READ_TIMEOUT_MS

            if (connection.responseCode != 200) {
                Log.w(TAG, "HTTP ${connection.responseCode} from imagery export")
                connection.disconnect()
                return null
            }

            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(body)
            val href = json.optString("href", "")
            if (href.isBlank()) {
                Log.w(TAG, "No href in imagery export response. Body: ${body.take(500)}")
                return null
            }

            // Download the actual PNG
            val imgConnection = URL(href).openConnection() as HttpURLConnection
            imgConnection.connectTimeout = AerialEstimationConfig.HTTP_CONNECT_TIMEOUT_MS
            imgConnection.readTimeout = AerialEstimationConfig.HTTP_READ_TIMEOUT_MS

            if (imgConnection.responseCode != 200) {
                Log.w(TAG, "HTTP ${imgConnection.responseCode} downloading imagery PNG")
                imgConnection.disconnect()
                return null
            }

            val bitmap = BitmapFactory.decodeStream(imgConnection.inputStream)
            imgConnection.disconnect()

            if (bitmap == null) {
                Log.w(TAG, "Failed to decode imagery PNG")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Imagery fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Meters per pixel corrected for Web Mercator latitude distortion.
     * Web Mercator inflates distances by sec(φ) at latitude φ; we apply cos(φ)
     * to recover true ground distance.
     */
    private fun computeMetersPerPixel(bbox: ParcelService.BoundingBox): Double {
        val widthMercator = bbox.xmax - bbox.xmin
        // Convert Web Mercator center Y to latitude (radians)
        val centerY = (bbox.ymin + bbox.ymax) / 2.0
        val latRad = 2.0 * atan(exp(centerY / 6378137.0)) - Math.PI / 2.0
        val cosLat = cos(latRad)
        return widthMercator * cosLat / AerialEstimationConfig.IMAGE_SIZE
    }

    /**
     * Query World Imagery layer 10 for source metadata at the center of the bbox.
     * Returns (SRC_DATE, SRC_DESC) or (null, null) on failure.
     */
    private fun fetchImageryMetadata(bbox: ParcelService.BoundingBox): Pair<Int?, String?> {
        return try {
            val cx = (bbox.xmin + bbox.xmax) / 2
            val cy = (bbox.ymin + bbox.ymax) / 2

            val url = buildString {
                append(AerialEstimationConfig.WORLD_IMAGERY_METADATA_LAYER)
                append("/query?geometry=$cx,$cy")
                append("&geometryType=esriGeometryPoint")
                append("&inSR=${AerialEstimationConfig.IMAGERY_SPATIAL_REF}")
                append("&spatialRel=esriSpatialRelIntersects")
                append("&outFields=SRC_DATE,SRC_ACC,SRC_DESC")
                append("&returnGeometry=false")
                append("&f=json")
            }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = AerialEstimationConfig.HTTP_CONNECT_TIMEOUT_MS
            connection.readTimeout = AerialEstimationConfig.HTTP_READ_TIMEOUT_MS

            if (connection.responseCode != 200) {
                connection.disconnect()
                return Pair(null, null)
            }

            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(body)
            val features = json.optJSONArray("features")
            if (features == null || features.length() == 0) return Pair(null, null)

            val attrs = features.getJSONObject(0).getJSONObject("attributes")
            val srcDate = attrs.optInt("SRC_DATE", 0).let { if (it > 0) it else null }
            val srcDesc = attrs.optString("SRC_DESC", "").ifBlank { null }

            Pair(srcDate, srcDesc)
        } catch (e: Exception) {
            Log.w(TAG, "Imagery metadata query failed: ${e.message}")
            Pair(null, null)
        }
    }
}
