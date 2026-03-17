package com.routeme.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.UUID

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
            val csvRecords = reassembleCsvRecords(lines)

            // De-duplicate headers: if "Step 5" appears twice, second becomes "Step 6"
            val rawHeaders = parseCsvLine(csvRecords.first())
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
                val cols = parseCsvLine(record)
                val map = headers.mapIndexedNotNull { index, header ->
                    if (index < cols.size && header.isNotBlank()) header to cols[index].trim() else null
                }.toMap()

                val client = buildClient(map, yearForDates)
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

    /**
     * Reassemble CSV lines that were split by newlines inside quoted fields.
     * E.g. `"123 Main St,\nPortage"` becomes one record.
     */
    private fun reassembleCsvRecords(lines: List<String>): List<String> {
        val records = mutableListOf<String>()
        val current = StringBuilder()
        var openQuotes = false

        for (line in lines) {
            if (openQuotes) {
                current.append(" ")  // replace newline with space
                current.append(line)
            } else {
                if (current.isNotEmpty()) records.add(current.toString())
                current.clear()
                current.append(line)
            }
            openQuotes = updateQuotedState(line, openQuotes)
        }
        if (current.isNotEmpty()) records.add(current.toString())
        return records
    }

    private fun updateQuotedState(line: String, initialState: Boolean): Boolean {
        var inQuotes = initialState
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            if (ch == '"') {
                val nextIsQuote = index + 1 < line.length && line[index + 1] == '"'
                if (nextIsQuote) {
                    index++
                } else {
                    inQuotes = !inQuotes
                }
            }
            index++
        }
        return inQuotes
    }

    /** Simple CSV line parser that handles quoted fields with commas */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' -> {
                    val nextIsQuote = index + 1 < line.length && line[index + 1] == '"'
                    if (nextIsQuote) {
                        current.append('"')
                        index++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            index++
        }
        result.add(current.toString())
        return result
    }

    private fun buildClient(map: Map<String, String>, year: Int): Client? {
        val name = map["name"]?.takeIf { it.isNotBlank() }?.take(100) ?: return null
        val address = map["address"]?.takeIf { it.isNotBlank() }?.take(200) ?: return null

        val subscribedSteps = mutableSetOf<Int>()
        val records = mutableListOf<ServiceRecord>()

        for (step in 1..6) {
            val cellValue = map["step $step"] ?: ""
            if (cellValue.isNotBlank()) {
                val lower = cellValue.lowercase(Locale.US)
                // Skip sold/deceased/cancelled clients for this step
                if (lower.contains("sold") || lower.contains("deceased") || lower.contains("cancel")) continue
                // Subscribed: checkmarks (√/✓), Google Sheets checkboxes
                // (TRUE/FALSE — presence means subscribed, state is irrelevant),
                // or dates (already serviced). Empty cell = not subscribed.
                subscribedSteps.add(step)
                val date = parseStepDate(cellValue, year)
                if (date != null) {
                    val type = ServiceType.forStep(step)
                    if (type != null) records.add(ServiceRecord(serviceType = type, completedAtMillis = date, durationMinutes = 0, lat = null, lng = null))
                }
            }
        }

        // Grub subscription: checkmark, checkbox (TRUE/FALSE), or date = subscribed
        val grubValue = map["grub"] ?: ""
        val hasGrub = grubValue.isNotBlank()
        if (hasGrub) {
            val date = parseStepDate(grubValue, year)
            if (date != null) records.add(ServiceRecord(serviceType = ServiceType.GRUB, completedAtMillis = date, durationMinutes = 0, lat = null, lng = null))
        }

        val mowDay = parseDayOfWeek(map["mow"] ?: map["mowday"] ?: "")
        val cuSpringPending = isCuPending(map["cu spring"] ?: map["cu_spring"] ?: map["cuspring"] ?: "")
        val cuFallPending = isCuPending(map["cu fall"] ?: map["cu_fall"] ?: map["cufall"] ?: "")

        return Client(
            id = map["id"] ?: "GS-${UUID.randomUUID().toString().take(8)}",
            name = name,
            address = address,
            zone = (map["zone"] ?: "").take(50),
            notes = (map["notes"] ?: "").take(500),
            subscribedSteps = subscribedSteps,
            hasGrub = hasGrub,
            mowDayOfWeek = mowDay,
            lawnSizeSqFt = 0, sunShade = "", terrain = "", windExposure = "",
            cuSpringPending = cuSpringPending,
            cuFallPending = cuFallPending,
            latitude = map["lat"]?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 },
            longitude = map["lng"]?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 },
            records = records
        )
    }

    private fun isCuPending(value: String): Boolean {
        val text = value.trim()
        if (text.isBlank() || !text.contains("*")) return false
        val withoutAsterisk = text.replace("*", "").trim()
        val hasDate = Regex("\\b\\d{1,2}[./-]\\d{1,2}([./-]\\d{2,4})?\\b").containsMatchIn(withoutAsterisk)
        return !hasDate
    }

    private fun parseStepDate(cellValue: String, year: Int): Long? {
        val cleaned = cellValue.replace("√", "").replace("✓", "").trim()
        if (cleaned.isBlank() || cleaned.equals("xx", ignoreCase = true)) return null
        // Skip sold/deceased/cancelled entries
        if (cleaned.lowercase(Locale.US).let { it.contains("sold") || it.contains("deceased") || it.contains("cancel") }) return null

        // Handle formats like "2nd 5.29" — extract the M.D part
        val dateRegex = Regex("(\\d{1,2})\\.(\\d{1,2})")
        val dateMatch = dateRegex.find(cleaned) ?: return null
        val month = dateMatch.groupValues[1].toIntOrNull() ?: return null
        val day = dateMatch.groupValues[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null

        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, 12, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun parseDayOfWeek(value: String): Int {
        return when (value.trim().lowercase(Locale.US)) {
            "sun", "sunday" -> Calendar.SUNDAY
            "mon", "monday" -> Calendar.MONDAY
            "tue", "tues", "tuesday" -> Calendar.TUESDAY
            "wed", "wednesday" -> Calendar.WEDNESDAY
            "thu", "thur", "thurs", "thursday" -> Calendar.THURSDAY
            "fri", "friday" -> Calendar.FRIDAY
            "sat", "saturday" -> Calendar.SATURDAY
            else -> 0
        }
    }
}
