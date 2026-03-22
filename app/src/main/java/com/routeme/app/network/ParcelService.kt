package com.routeme.app.network

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Queries multiple ArcGIS parcel services by lat/lng, trying each in order
 * until one returns a result (Kalamazoo County → Kalamazoo City → Barry County).
 * Must be called on a background thread.
 */
object ParcelService {

    private const val TAG = "ParcelService"

    data class BoundingBox(
        val xmin: Double, val ymin: Double,
        val xmax: Double, val ymax: Double
    )

    data class ParcelResult(
        val parcelId: String,
        val address: String,
        val assessedAcres: Double,
        val gisAcres: Double,
        val polygonRings: List<List<DoubleArray>>,
        val boundingBox: BoundingBox,
        val sourceName: String
    )

    /**
     * Query parcel services for the parcel containing the given WGS-84 coordinates.
     * Tries each configured source in order; returns the first successful hit or null.
     */
    fun queryByLocation(lat: Double, lng: Double): ParcelResult? {
        for (source in AerialEstimationConfig.PARCEL_SOURCES) {
            try {
                val result = querySource(source, lat, lng)
                if (result != null) return result
            } catch (e: Exception) {
                Log.w(TAG, "[${source.name}] failed: ${e.message}")
            }
        }
        Log.d(TAG, "No parcel found at $lat, $lng from any source")
        return null
    }

    private fun querySource(
        source: AerialEstimationConfig.ParcelSourceConfig,
        lat: Double,
        lng: Double
    ): ParcelResult? {
        val url = buildString {
            append(source.url)
            append("/query?geometry=$lng,$lat")
            append("&geometryType=esriGeometryPoint")
            append("&inSR=4326")
            append("&outSR=3857")
            append("&spatialRel=esriSpatialRelIntersects")
            append("&outFields=${source.outFields}")
            append("&returnGeometry=true")
            append("&f=json")
        }

        Log.d(TAG, "[${source.name}] Querying parcel at $lat, $lng")

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = AerialEstimationConfig.HTTP_CONNECT_TIMEOUT_MS
        connection.readTimeout = AerialEstimationConfig.HTTP_READ_TIMEOUT_MS

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            Log.w(TAG, "[${source.name}] HTTP $responseCode")
            connection.disconnect()
            return null
        }

        val body = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        return parseParcelResponse(source, body)
    }

    private fun parseParcelResponse(
        source: AerialEstimationConfig.ParcelSourceConfig,
        body: String
    ): ParcelResult? {
        val json = JSONObject(body)

        if (json.has("error")) {
            val error = json.getJSONObject("error")
            Log.w(TAG, "[${source.name}] ArcGIS error ${error.optInt("code")}: ${error.optString("message")}")
            return null
        }

        val features = json.optJSONArray("features")
        if (features == null || features.length() == 0) {
            Log.d(TAG, "[${source.name}] No parcel found")
            return null
        }

        val feature = features.getJSONObject(0)
        val attrs = feature.getJSONObject("attributes")
        val geometry = feature.getJSONObject("geometry")

        val parcelId = if (source.parcelIdField.isNotEmpty())
            attrs.optString(source.parcelIdField, "") else ""
        val address = if (source.addressField.isNotEmpty())
            attrs.optString(source.addressField, "") else ""
        val assessedAcres = if (source.assessedAcresField.isNotEmpty())
            attrs.optDouble(source.assessedAcresField, 0.0) else 0.0
        val gisAcres = if (source.gisAcresField.isNotEmpty())
            attrs.optDouble(source.gisAcresField, 0.0) else 0.0

        // Parse polygon rings (now always in Web Mercator 3857 via outSR)
        val ringsArray = geometry.optJSONArray("rings")
        if (ringsArray == null || ringsArray.length() == 0) {
            Log.w(TAG, "[${source.name}] Parcel $parcelId has no geometry rings")
            return null
        }

        val rings = mutableListOf<List<DoubleArray>>()
        var xmin = Double.MAX_VALUE
        var ymin = Double.MAX_VALUE
        var xmax = -Double.MAX_VALUE
        var ymax = -Double.MAX_VALUE

        for (r in 0 until ringsArray.length()) {
            val ring = ringsArray.getJSONArray(r)
            val points = mutableListOf<DoubleArray>()
            for (p in 0 until ring.length()) {
                val coord = ring.getJSONArray(p)
                val x = coord.getDouble(0)
                val y = coord.getDouble(1)
                points.add(doubleArrayOf(x, y))
                if (x < xmin) xmin = x
                if (y < ymin) ymin = y
                if (x > xmax) xmax = x
                if (y > ymax) ymax = y
            }
            rings.add(points)
        }

        val bbox = BoundingBox(xmin, ymin, xmax, ymax)

        Log.d(TAG, "[${source.name}] Found parcel $parcelId ($address) — " +
            "assessed=${assessedAcres}ac, gis=${gisAcres}ac, " +
            "bbox=[${bbox.xmin}, ${bbox.ymin}, ${bbox.xmax}, ${bbox.ymax}], " +
            "ring points=${rings.sumOf { it.size }}")

        return ParcelResult(
            parcelId = parcelId,
            address = address,
            assessedAcres = assessedAcres,
            gisAcres = gisAcres,
            polygonRings = rings,
            boundingBox = bbox,
            sourceName = source.name
        )
    }
}
