package com.routeme.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.routeme.app.domain.PropertyEstimator
import com.routeme.app.network.AerialEstimationConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Batch estimation runner — reads all clients from Room DB, runs the aerial
 * estimation pipeline for each, writes results back to client_properties,
 * and logs CSV output.
 *
 * Run with:
 *   .\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.routeme.app.BatchEstimationTest"
 *
 * Watch results:
 *   adb logcat -d -s BatchEstimate:I
 */
@RunWith(AndroidJUnit4::class)
class BatchEstimationTest {

    companion object {
        private const val TAG = "BatchEstimate"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 2000L
    }

    @Test
    fun runBatchEstimation() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(ctx)
        val clientDao = db.clientDao()
        val propertyDao = db.clientPropertyDao()

        val allClients = runBlocking { clientDao.getAllClients() }
        val withCoords = allClients.filter { it.latitude != null && it.longitude != null }

        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  BATCH AERIAL ESTIMATION")
        Log.i(TAG, "  Total clients: ${allClients.size}")
        Log.i(TAG, "  With coordinates: ${withCoords.size}")
        Log.i(TAG, "  Skipped (no coords): ${allClients.size - withCoords.size}")
        Log.i(TAG, "═══════════════════════════════════════")

        // Log CSV header
        Log.i(TAG, "CSV_HEADER: Name|Address|LawnSqFt|SunShade|Confidence|ParcelId|Acres|ImageryDate|Notes")

        val results = mutableListOf<ClientPropertyEntity>()
        var success = 0
        var failed = 0
        var skippedZero = 0

        for ((index, client) in withCoords.withIndex()) {
            val lat = client.latitude!!
            val lng = client.longitude!!

            Log.i(TAG, "")
            Log.i(TAG, "[${index + 1}/${withCoords.size}] ${client.name} — ${client.address}")

            var result: PropertyEstimator.EstimationResult? = null
            for (attempt in 1..MAX_RETRIES + 1) {
                result = PropertyEstimator.estimate(lat, lng, client.name)
                if (result.lawnSizeSqFt > 0 || result.confidence != PropertyEstimator.Confidence.LOW) {
                    break
                }
                if (attempt <= MAX_RETRIES) {
                    Log.w(TAG, "  Attempt $attempt returned 0 sqft / LOW — retrying in ${RETRY_DELAY_MS}ms...")
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
            result!!

            // Log detailed result
            Log.i(TAG, "  Lawn: ${result.lawnSizeSqFt} sqft | ${result.sunShade} | ${result.confidence}")
            Log.i(TAG, "  Parcel: ${result.parcelId ?: "none"} | Acres: ${result.assessedAcres ?: "n/a"}")
            Log.i(TAG, "  Pixels: turf=${result.turfPixels} tree=${result.treePixels} bldg=${result.buildingPixels} hard=${result.hardscapePixels} other=${result.otherPixels}")

            // Log CSV row
            val sunShadeStr = result.sunShade.name
            Log.i(TAG, "CSV: ${client.name}|${client.address}|${result.lawnSizeSqFt}|$sunShadeStr|${result.confidence}|${result.parcelId ?: ""}|${result.assessedAcres ?: ""}|${result.imageryDateStr ?: ""}|${result.notes}")

            if (result.lawnSizeSqFt > 0) {
                results.add(
                    ClientPropertyEntity(
                        clientId = client.id,
                        lawnSizeSqFt = result.lawnSizeSqFt,
                        sunShade = sunShadeStr,
                        windExposure = "",
                        hasSteepSlopes = false,
                        hasIrrigation = false,
                        propertyNotes = "Aerial est. ${result.confidence} — ${result.notes}",
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
                success++
            } else {
                skippedZero++
            }

            Thread.sleep(AerialEstimationConfig.BATCH_THROTTLE_MS)
        }

        // Batch upsert all results
        if (results.isNotEmpty()) {
            runBlocking { propertyDao.upsertProperties(results) }
            Log.i(TAG, "")
            Log.i(TAG, "═══ DB UPSERT: ${results.size} properties written ═══")
        }

        Log.i(TAG, "")
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  BATCH COMPLETE")
        Log.i(TAG, "  Estimated OK: $success")
        Log.i(TAG, "  Zero sqft (skipped DB write): $skippedZero")
        Log.i(TAG, "  Total processed: ${withCoords.size}")
        Log.i(TAG, "═══════════════════════════════════════")
    }
}
