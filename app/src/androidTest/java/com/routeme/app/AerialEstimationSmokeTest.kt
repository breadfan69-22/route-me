package com.routeme.app

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.routeme.app.domain.PropertyEstimator
import com.routeme.app.network.AerialImageService
import com.routeme.app.network.ParcelService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * On-device integration test for the aerial estimation pipeline.
 * Hits real ArcGIS endpoints — requires network.
 * Uses hardcoded coordinates to avoid geocoding API key dependency.
 *
 * Run with:
 *   .\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.routeme.app.AerialEstimationSmokeTest"
 *
 * Watch results:
 *   adb logcat -d -s AerialSmoke:I
 */
@RunWith(AndroidJUnit4::class)
class AerialEstimationSmokeTest {

    companion object {
        private const val TAG = "AerialSmoke"
    }

    data class TestProperty(
        val label: String,
        val lat: Double,
        val lng: Double,
        val expectedDifficulty: String
    )

    // Coordinates looked up for each test address:
    //   Geary Yost        — 2288 W G Ave, Kalamazoo, MI
    //   Georgia McWilliams — 2744 W AB Ave, Plainwell, MI
    //   Tom Kayser         — 2132 Rambling Rd, Kalamazoo, MI
    //   WMU Oakland        — 1000 Oakland Dr, Kalamazoo, MI
    private val testProperties = listOf(
        TestProperty(
            label = "Geary Yost (2288 W G Ave)",
            lat = 42.24167, lng = -85.67580,
            expectedDifficulty = "HARD: heavy tree cover + adjacent farmfields"
        ),
        TestProperty(
            label = "Georgia McWilliams (2744 W AB Ave)",
            lat = 42.44099, lng = -85.71295,
            expectedDifficulty = "HARD: trees + large lot, turf smaller than parcel"
        ),
        TestProperty(
            label = "Tom Kayser (2132 Rambling Rd)",
            lat = 42.270101966835355, lng = -85.62577708418695,
            expectedDifficulty = "EASY: square residential lot, no trees, no farms"
        ),
        TestProperty(
            label = "Steve Vandersteldt (6362 Heather Ridge)",
            lat = 42.35288, lng = -85.63330,
            expectedDifficulty = "MEDIUM: residential subdivision lot"
        ),
        TestProperty(
            label = "Kathy Sullivan (6851 Natalie St)",
            lat = 42.3288257, lng = -85.4949301,
            expectedDifficulty = "MEDIUM: trees and wooded areas on lot"
        ),
        TestProperty(
            label = "Brandy Stiver (4016 Rockwood Dr)",
            lat = 42.3302114, lng = -85.6371748,
            expectedDifficulty = "MEDIUM: trees and wooded areas on lot"
        ),
        TestProperty(
            label = "Melissa Noseworthy (1133 Grand Pre Ave)",
            lat = 42.3019140, lng = -85.6228441,
            expectedDifficulty = "MEDIUM: trees and wooded areas on lot"
        ),
        TestProperty(
            label = "Boss's House (10581 N 16th St, Plainwell)",
            lat = 42.4149281, lng = -85.6113214,
            expectedDifficulty = "HARD: known wooded area, 1+ acre lot"
        ),
        TestProperty(
            label = "Martin Hill (78 11th St, Plainwell)",
            lat = 42.4265793, lng = -85.6482200,
            expectedDifficulty = "HARD: heavily shaded, turf under trees"
        ),
        TestProperty(
            label = "CJ Herwig (9974 Shephard Ridge, Kzoo)",
            lat = 42.2204728, lng = -85.7448488,
            expectedDifficulty = "HARD: heavily shaded, turf under trees"
        )
    )

    // ──────────────────────────────────────────────
    // Step 1: Parcel lookup only
    // ──────────────────────────────────────────────

    @Test
    fun step1_parcelLookup_allProperties() {
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  STEP 1: PARCEL LOOKUP")
        Log.i(TAG, "═══════════════════════════════════════")

        for (prop in testProperties) {
            val parcel = ParcelService.queryByLocation(prop.lat, prop.lng)

            Log.i(TAG, "──── ${prop.label} ────")
            Log.i(TAG, "  Coords: ${prop.lat}, ${prop.lng}")
            if (parcel != null) {
                Log.i(TAG, "  Source: ${parcel.sourceName}")
                Log.i(TAG, "  Parcel ID: ${parcel.parcelId}")
                Log.i(TAG, "  Parcel addr: ${parcel.address}")
                Log.i(TAG, "  Assessed acres: ${parcel.assessedAcres}")
                Log.i(TAG, "  GIS acres: ${parcel.gisAcres}")
                Log.i(TAG, "  Bbox: [${parcel.boundingBox.xmin}, ${parcel.boundingBox.ymin}] → " +
                    "[${parcel.boundingBox.xmax}, ${parcel.boundingBox.ymax}]")
                Log.i(TAG, "  Ring points: ${parcel.polygonRings.sumOf { it.size }}")
            } else {
                Log.w(TAG, "  ⚠ NO PARCEL FOUND (outside coverage area)")
            }

            Thread.sleep(200)  // throttle
        }
    }

    // ──────────────────────────────────────────────
    // Step 2: Imagery fetch + SAVE to disk for visual inspection
    // ──────────────────────────────────────────────

    @Test
    fun step2_imageryFetch_allProperties() {
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  STEP 2: IMAGERY FETCH + SAVE")
        Log.i(TAG, "═══════════════════════════════════════")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(ctx.getExternalFilesDir(null), "aerial_debug")
        outDir.mkdirs()
        Log.i(TAG, "Saving images to: ${outDir.absolutePath}")

        val failures = mutableListOf<String>()
        for (prop in testProperties) {
            val parcel = ParcelService.queryByLocation(prop.lat, prop.lng) ?: continue

            val aerial = AerialImageService.fetchImagery(parcel.boundingBox)

            Log.i(TAG, "──── ${prop.label} ────")
            if (aerial != null) {
                Log.i(TAG, "  Image: ${aerial.bitmap.width}x${aerial.bitmap.height}")
                Log.i(TAG, "  Meters/pixel: %.4f".format(aerial.metersPerPixel))
                Log.i(TAG, "  Imagery date: ${aerial.imageryDate}")
                Log.i(TAG, "  Imagery source: ${aerial.imagerySource}")

                // Save bitmap to device storage for visual inspection
                val safeName = prop.label.replace(Regex("[^a-zA-Z0-9]"), "_")
                val file = File(outDir, "${safeName}.png")
                FileOutputStream(file).use { fos ->
                    aerial.bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                Log.i(TAG, "  Saved: ${file.absolutePath}")

                aerial.bitmap.recycle()
            } else {
                Log.w(TAG, "  ⚠ IMAGERY FETCH FAILED")
                failures.add(prop.label)
            }

            Thread.sleep(500)
        }
        assertTrue("Imagery failed for: $failures", failures.isEmpty())
    }

    // ──────────────────────────────────────────────
    // Step 3: Full pipeline end-to-end
    // ──────────────────────────────────────────────

    @Test
    fun step3_fullEstimation_allProperties() {
        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  STEP 3: FULL ESTIMATION PIPELINE")
        Log.i(TAG, "═══════════════════════════════════════")

        for (prop in testProperties) {
            val result = PropertyEstimator.estimate(prop.lat, prop.lng, prop.label)

            Log.i(TAG, "")
            Log.i(TAG, "┌──── ${prop.label} ────")
            Log.i(TAG, "│ Difficulty: ${prop.expectedDifficulty}")
            Log.i(TAG, "│ Coords: ${prop.lat}, ${prop.lng}")
            Log.i(TAG, "│")
            Log.i(TAG, "│ RESULT:")
            Log.i(TAG, "│   Lawn size: ${result.lawnSizeSqFt} sqft")
            Log.i(TAG, "│   Sun/Shade: ${result.sunShade}")
            Log.i(TAG, "│   Confidence: ${result.confidence}")
            Log.i(TAG, "│   Parcel ID: ${result.parcelId ?: "none"}")
            Log.i(TAG, "│   Assessed acres: ${result.assessedAcres ?: "n/a"}")
            Log.i(TAG, "│   Imagery date: ${result.imageryDateStr ?: "unknown"}")
            Log.i(TAG, "│")
            Log.i(TAG, "│ PIXEL BREAKDOWN:")
            Log.i(TAG, "│   Turf:      ${result.turfPixels}")
            Log.i(TAG, "│   Tree:      ${result.treePixels}")
            Log.i(TAG, "│   Building:  ${result.buildingPixels}")
            Log.i(TAG, "│   Hardscape: ${result.hardscapePixels}")
            Log.i(TAG, "│   Other:     ${result.otherPixels}")
            Log.i(TAG, "│   Total:     ${result.totalParcelPixels}")
            if (result.totalParcelPixels > 0) {
                val turfPct = result.turfPixels * 100 / result.totalParcelPixels
                val treePct = result.treePixels * 100 / result.totalParcelPixels
                val bldgPct = result.buildingPixels * 100 / result.totalParcelPixels
                val hardPct = result.hardscapePixels * 100 / result.totalParcelPixels
                val otherPct = result.otherPixels * 100 / result.totalParcelPixels
                Log.i(TAG, "│   Ratios: turf=$turfPct% tree=$treePct% bldg=$bldgPct% hard=$hardPct% other=$otherPct%")
            }
            Log.i(TAG, "│")
            Log.i(TAG, "│ NOTES: ${result.notes}")
            Log.i(TAG, "└────────────────────────────────")

            assertTrue("Lawn sqft should be non-negative for ${prop.label}", result.lawnSizeSqFt >= 0)
            Thread.sleep(500)  // throttle between properties
        }
    }

}
