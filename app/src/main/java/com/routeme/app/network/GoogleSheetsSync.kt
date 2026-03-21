package com.routeme.app.network

import com.routeme.app.Client
import com.routeme.app.CsvParsingUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

/**
 * Fetches a Google Sheet published as CSV and parses it into Client objects.
 *
 * How to get the URL:
 *   1. Open the Google Sheet in a browser
 *   2. File → Share → Publish to web
 *   3. Choose the sheet tab, format = CSV
 *   4. Copy the generated URL
 *
 * The URL looks like:
 *   https://docs.google.com/spreadsheets/d/e/<ID>/pub?output=csv
 */
object GoogleSheetsSync {
    private val GOOGLE_SHEETS_CLIENT_BUILD_CONFIG = CsvParsingUtils.ClientBuildConfig(
        idPrefix = "GS",
        nameKeys = listOf("name"),
        addressKeys = listOf("address"),
        zoneKeys = listOf("zone"),
        notesKeys = listOf("notes"),
        mowKeys = listOf("mow", "mowday"),
        latitudeKeys = listOf("lat"),
        longitudeKeys = listOf("lng"),
        lawnSizeKeys = listOf("lawn size", "lawnsize", "lawn_size", "sqft"),
        sunShadeKeys = listOf("sun/shade", "sun shade", "sunshade", "sun_shade"),
        terrainKeys = listOf("terrain", "slope", "slopes"),
        windExposureKeys = listOf("wind exposure", "windexposure", "wind_exposure"),
        parseEmbeddedStepDate = true,
        skipStatusKeywordsInStepDate = true
    )

    data class SyncResult(
        val clients: List<Client>,
        val message: String
    )

    /**
     * Converts any Google Sheets URL to a CSV export URL.
     * Accepts:
     *   - Regular edit URL: https://docs.google.com/spreadsheets/d/SHEET_ID/edit...
     *   - Published URL: https://docs.google.com/spreadsheets/d/e/ENCODED_ID/pub?output=csv
     *   - Export URL: https://docs.google.com/spreadsheets/d/SHEET_ID/export?format=csv
     */
    fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()

        // Already a CSV export or published URL
        if (trimmed.contains("/pub?") && trimmed.contains("output=csv")) return trimmed
        if (trimmed.contains("/export?") && trimmed.contains("format=csv")) return trimmed

        // Extract gid if present (e.g. #gid=1477351516 or ?gid=1477351516)
        val gidRegex = Regex("gid=(\\d+)")
        val gidMatch = gidRegex.find(trimmed)
        val gidParam = if (gidMatch != null) "&gid=${gidMatch.groupValues[1]}" else ""

        // Extract spreadsheet ID from /d/XXXXX/ pattern
        val idRegex = Regex("/spreadsheets/d/([^/]+)")
        val match = idRegex.find(trimmed)
        if (match != null) {
            val sheetId = match.groupValues[1]
            // /d/e/ENCODED means published link — convert to pub CSV
            if (sheetId == "e") {
                val pubIdRegex = Regex("/d/e/([^/]+)")
                val pubMatch = pubIdRegex.find(trimmed)
                if (pubMatch != null) {
                    return "https://docs.google.com/spreadsheets/d/e/${pubMatch.groupValues[1]}/pub?output=csv$gidParam"
                }
            }
            return "https://docs.google.com/spreadsheets/d/$sheetId/export?format=csv$gidParam"
        }

        // Can't parse — return as-is and let the fetch fail with a clear message
        return trimmed
    }

    /**
     * Fetches and parses the published Google Sheet CSV.
     * Must be called from a background thread.
     * Accepts any Google Sheets URL — auto-converts to CSV export.
     */
    fun fetch(rawUrl: String): SyncResult {
        return try {
            val csvUrl = normalizeUrl(rawUrl)
            if (!csvUrl.startsWith("https://docs.google.com/")) {
                return SyncResult(emptyList(), "Invalid URL: must be a Google Sheets link (docs.google.com).")
            }
            val url = URL(csvUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: "" } catch (_: Exception) { "" }
                return SyncResult(emptyList(), "Sheets HTTP $responseCode. URL: ${csvUrl.take(60)}… $errorBody")
            }

            val lines = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).readLines()
            conn.disconnect()

            if (lines.isEmpty()) {
                return SyncResult(emptyList(), "Google Sheet is empty (0 lines returned).")
            }

            // Reassemble multi-line CSV records (addresses with newlines in quoted fields)
            val csvRecords = CsvParsingUtils.reassembleCsvRecords(lines)

            // De-duplicate headers: if "Step 5" appears twice, second becomes "Step 6"
            val rawHeaders = CsvParsingUtils.parseCsvLine(csvRecords.first())
            val seenHeaders = mutableMapOf<String, Int>()
            val headers = rawHeaders.map { raw ->
                val h = raw.removePrefix("\u221A").removePrefix("\u2713").trim().lowercase(Locale.US)
                val count = seenHeaders.getOrDefault(h, 0)
                seenHeaders[h] = count + 1
                if (count == 0) h else {
                    if (h.startsWith("step ")) {
                        val num = h.removePrefix("step ").toIntOrNull()
                        if (num != null) "step ${num + count}" else "${h}_$count"
                    } else "${h}_$count"
                }
            }

            val headerSummary = headers.filter { it.isNotBlank() }.take(12).joinToString(", ")

            val yearForDates = Calendar.getInstance().get(Calendar.YEAR)
            val clients = mutableListOf<Client>()

            for (record in csvRecords.drop(1)) {
                if (record.isBlank()) continue
                val cols = CsvParsingUtils.parseCsvLine(record)
                val map = headers.mapIndexedNotNull { index, header ->
                    if (index < cols.size && header.isNotBlank()) header to cols[index].trim() else null
                }.toMap()

                val client = CsvParsingUtils.createClientFromMap(
                    map = map,
                    yearForDates = yearForDates,
                    config = GOOGLE_SHEETS_CLIENT_BUILD_CONFIG
                )
                if (client != null) clients.add(client)
            }

            if (clients.isEmpty()) {
                SyncResult(emptyList(), "0 clients parsed. ${lines.size} CSV rows, headers: $headerSummary. Need 'Name' & 'Address' columns.")
            } else {
                SyncResult(clients, "Synced ${clients.size} client(s) from Google Sheets. (${lines.size} rows, headers: $headerSummary)")
            }
        } catch (e: Exception) {
            SyncResult(emptyList(), "Sheets sync error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
