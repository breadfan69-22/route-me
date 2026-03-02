package com.routeme.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ImportResult(
    val clients: List<Client>,
    val message: String
)

object ClientImportParser {
    fun parse(context: Context, uri: Uri): ImportResult {
        val fileName = getDisplayName(context, uri).lowercase(Locale.US)
        return when {
            fileName.endsWith(".xlsx") || fileName.endsWith(".xls") -> parseXlsx(context, uri)
            fileName.endsWith(".csv") -> parseCsv(context, uri)
            fileName.endsWith(".html") || fileName.endsWith(".htm") -> parseHtml(context, uri)
            fileName.endsWith(".pdf") -> ImportResult(
                clients = emptyList(),
                message = "PDF import is not supported yet. Export as XLSX or CSV first."
            )
            else -> ImportResult(
                clients = emptyList(),
                message = "Unsupported file type. Use XLSX, CSV, or HTML."
            )
        }
    }

    // ── XLSX import (pure ZIP + XML, no Apache POI) ────────────────────

    private fun parseXlsx(context: Context, uri: Uri): ImportResult {
        val input = context.contentResolver.openInputStream(uri)
            ?: return ImportResult(emptyList(), "Unable to read selected file.")

        return try {
            // XLSX is a ZIP of XML files. We need:
            //   xl/sharedStrings.xml  – string table
            //   xl/worksheets/sheet1.xml – first worksheet data
            var sharedStringsXml: String? = null
            var sheet1Xml: String? = null

            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name.equals("xl/sharedStrings.xml", ignoreCase = true) ->
                            sharedStringsXml = zip.bufferedReader().readText()
                        name.equals("xl/worksheets/sheet1.xml", ignoreCase = true) ->
                            sheet1Xml = zip.bufferedReader().readText()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            if (sheet1Xml == null) {
                return ImportResult(emptyList(), "Could not find sheet1.xml in XLSX file.")
            }

            // Parse shared strings table
            val sharedStrings = mutableListOf<String>()
            if (sharedStringsXml != null) {
                val dbf = DocumentBuilderFactory.newInstance()
                val doc = dbf.newDocumentBuilder().parse(sharedStringsXml!!.byteInputStream())
                val siNodes = doc.getElementsByTagName("si")
                for (i in 0 until siNodes.length) {
                    val si = siNodes.item(i)
                    // Collect all <t> text nodes under this <si>
                    val tNodes = (si as org.w3c.dom.Element).getElementsByTagName("t")
                    val sb = StringBuilder()
                    for (j in 0 until tNodes.length) {
                        sb.append(tNodes.item(j).textContent)
                    }
                    sharedStrings.add(sb.toString())
                }
            }

            // Parse sheet1 rows
            val dbf = DocumentBuilderFactory.newInstance()
            val doc = dbf.newDocumentBuilder().parse(sheet1Xml!!.byteInputStream())
            val rowNodes = doc.getElementsByTagName("row")

            // Read all rows as lists of string values
            val allRows = mutableListOf<List<String>>()
            for (i in 0 until rowNodes.length) {
                val row = rowNodes.item(i) as org.w3c.dom.Element
                val cells = row.getElementsByTagName("c")
                val maxCol = 26 // support up to column Z
                val values = Array(maxCol) { "" }

                for (j in 0 until cells.length) {
                    val cell = cells.item(j) as org.w3c.dom.Element
                    val ref = cell.getAttribute("r") // e.g. "A1", "B1", "AA1"
                    val colIndex = cellRefToIndex(ref)
                    if (colIndex < 0 || colIndex >= maxCol) continue

                    val type = cell.getAttribute("t") // "s" = shared string, "inlineStr", else number
                    val vNodes = cell.getElementsByTagName("v")
                    val rawValue = if (vNodes.length > 0) vNodes.item(0).textContent else ""

                    values[colIndex] = when (type) {
                        "s" -> {
                            val idx = rawValue.toIntOrNull()
                            if (idx != null && idx < sharedStrings.size) sharedStrings[idx] else rawValue
                        }
                        "inlineStr" -> {
                            val isNodes = cell.getElementsByTagName("t")
                            if (isNodes.length > 0) isNodes.item(0).textContent else rawValue
                        }
                        else -> rawValue
                    }
                }
                allRows.add(values.toList())
            }

            if (allRows.isEmpty()) {
                return ImportResult(emptyList(), "XLSX sheet is empty.")
            }

            // First row = headers
            val headers = allRows.first().map { it.removePrefix("√").removePrefix("✓").trim().lowercase(Locale.US) }
            val yearForDates = Calendar.getInstance().get(Calendar.YEAR)
            val clients = mutableListOf<Client>()

            for (rowValues in allRows.drop(1)) {
                val map = mutableMapOf<String, String>()
                headers.forEachIndexed { index, header ->
                    if (header.isNotBlank() && index < rowValues.size) {
                        map[header] = rowValues[index].trim()
                    }
                }

                val name = map["name"]?.takeIf { it.isNotBlank() }?.take(100) ?: continue
                val address = map["address"]?.takeIf { it.isNotBlank() }?.take(200) ?: continue

                val subscribedSteps = mutableSetOf<Int>()
                val records = mutableListOf<ServiceRecord>()

                for (step in 1..6) {
                    val cellValue = map["step $step"] ?: ""
                    if (cellValue.isNotBlank()) {
                        subscribedSteps.add(step)
                        val completionDate = parseStepDate(cellValue, yearForDates)
                        if (completionDate != null) {
                            val serviceType = ServiceType.forStep(step)
                            if (serviceType != null) {
                                records.add(ServiceRecord(serviceType = serviceType, completedAtMillis = completionDate, durationMinutes = 0, lat = null, lng = null))
                            }
                        }
                    }
                }

                val grubValue = map["grub"] ?: ""
                val hasGrub = grubValue.isNotBlank()
                if (hasGrub) {
                    val grubDate = parseStepDate(grubValue, yearForDates)
                    if (grubDate != null) {
                        records.add(ServiceRecord(serviceType = ServiceType.GRUB, completedAtMillis = grubDate, durationMinutes = 0, lat = null, lng = null))
                    }
                }

                val mowDay = parseDayOfWeek(map["mow"] ?: "")
                val zone = (map["zone"] ?: "").take(50)
                val notes = (map["notes"] ?: "").take(500)
                val cuSpringPending = isCuPending(map["cu spring"] ?: map["cu_spring"] ?: map["cuspring"] ?: "")
                val cuFallPending = isCuPending(map["cu fall"] ?: map["cu_fall"] ?: map["cufall"] ?: "")

                clients.add(Client(
                    id = "IMP-${UUID.randomUUID().toString().take(8)}",
                    name = name, address = address,
                    zone = zone, notes = notes,
                    subscribedSteps = subscribedSteps, hasGrub = hasGrub,
                    mowDayOfWeek = mowDay,
                    lawnSizeSqFt = 0, sunShade = "", terrain = "", windExposure = "",
                    cuSpringPending = cuSpringPending,
                    cuFallPending = cuFallPending,
                    latitude = null, longitude = null,
                    records = records
                ))
            }

            ImportResult(clients, "Imported ${clients.size} client(s) from XLSX.")
        } catch (e: Exception) {
            ImportResult(emptyList(), "XLSX parse error: ${e.message}")
        }
    }

    /** Convert cell reference like "A1" -> 0, "B1" -> 1, "Z1" -> 25 */
    private fun cellRefToIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch.isLetter()) {
                col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else break
        }
        return col - 1
    }

    /**
     * Parses step cell values like:
     *   "√"        -> subscribed only, no date
     *   "√6.12"    -> completed June 12
     *   "√xx"      -> subscribed, skipped/cancelled, no date
     *   "√10.3"    -> completed October 3
     */
    private fun parseStepDate(cellValue: String, year: Int): Long? {
        val cleaned = cellValue
            .replace("√", "")
            .replace("✓", "")
            .trim()

        if (cleaned.isBlank() || cleaned.equals("xx", ignoreCase = true)) {
            return null
        }

        // Expected format: M.D  (e.g. 6.12 = June 12, 10.3 = October 3)
        val parts = cleaned.split(".")
        if (parts.size == 2) {
            val month = parts[0].trim().toIntOrNull()
            val day = parts[1].trim().toIntOrNull()
            if (month != null && day != null && month in 1..12 && day in 1..31) {
                val cal = Calendar.getInstance()
                cal.set(year, month - 1, day, 12, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
        }
        return null
    }

    // ── CSV import ───────────────────────────────────────────────────────

    private fun parseCsv(context: Context, uri: Uri): ImportResult {
        val input = context.contentResolver.openInputStream(uri)
            ?: return ImportResult(emptyList(), "Unable to read selected file.")

        val lines = BufferedReader(InputStreamReader(input)).readLines()
        if (lines.isEmpty()) {
            return ImportResult(emptyList(), "CSV is empty.")
        }

        val headers = lines.first().split(",").map { it.trim().removePrefix("√").trim().lowercase(Locale.US) }
        val clients = mutableListOf<Client>()

        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach

            val cols = line.split(",").map { it.trim() }
            val map = headers.mapIndexedNotNull { index, header ->
                if (index < cols.size) header to cols[index] else null
            }.toMap()

            val client = createClientFromMap(map)
            if (client != null) clients.add(client)
        }

        return ImportResult(clients, "Imported ${clients.size} client(s) from CSV.")
    }

    // ── HTML import ──────────────────────────────────────────────────────

    private fun parseHtml(context: Context, uri: Uri): ImportResult {
        val input = context.contentResolver.openInputStream(uri)
            ?: return ImportResult(emptyList(), "Unable to read selected file.")
        val html = input.bufferedReader().use { it.readText() }

        val doc = Jsoup.parse(html)
        val rows = doc.select("table tr")
        if (rows.isEmpty()) return ImportResult(emptyList(), "No HTML table rows found.")

        val headerRow = rows.first()
        val headers = headerRow?.select("th,td")
            ?.map { it.text().trim().removePrefix("√").trim().lowercase(Locale.US) }
            ?: return ImportResult(emptyList(), "No header row found.")

        val clients = mutableListOf<Client>()

        rows.drop(1).forEach { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEach

            val map = headers.mapIndexedNotNull { index, header ->
                if (index < cells.size) header to cells[index].text().trim() else null
            }.toMap()

            val client = createClientFromMap(map)
            if (client != null) clients.add(client)
        }

        return ImportResult(clients, "Imported ${clients.size} client(s) from HTML table.")
    }

    // ── Shared helpers ───────────────────────────────────────────────────

    private fun createClientFromMap(map: Map<String, String>): Client? {
        val name = (map["name"] ?: map["client"])?.take(100) ?: return null
        val address = map["address"]?.take(200) ?: return null

        val yearForDates = Calendar.getInstance().get(Calendar.YEAR)

        val subscribedSteps = mutableSetOf<Int>()
        val records = mutableListOf<ServiceRecord>()

        for (step in 1..6) {
            val cellValue = map["step $step"] ?: ""
            if (cellValue.isNotBlank()) {
                subscribedSteps.add(step)
                val completionDate = parseStepDate(cellValue, yearForDates)
                if (completionDate != null) {
                    val serviceType = ServiceType.forStep(step)
                    if (serviceType != null) {
                        records.add(ServiceRecord(serviceType = serviceType, completedAtMillis = completionDate, durationMinutes = 0, lat = null, lng = null))
                    }
                }
            }
        }

        val grubValue = map["grub"] ?: ""
        val hasGrub = grubValue.isNotBlank()
        if (hasGrub) {
            val grubDate = parseStepDate(grubValue, yearForDates)
            if (grubDate != null) {
                records.add(ServiceRecord(serviceType = ServiceType.GRUB, completedAtMillis = grubDate, durationMinutes = 0, lat = null, lng = null))
            }
        }

        val mowDay = parseDayOfWeek(map["mow"] ?: map["mowday"] ?: map["mow_day"] ?: "")
        val lat = (map["lat"]?.toDoubleOrNull() ?: map["latitude"]?.toDoubleOrNull())?.takeIf { it in -90.0..90.0 }
        val lng = (map["lng"]?.toDoubleOrNull() ?: map["longitude"]?.toDoubleOrNull())?.takeIf { it in -180.0..180.0 }
        val cuSpringPending = isCuPending(map["cu spring"] ?: map["cu_spring"] ?: map["cuspring"] ?: "")
        val cuFallPending = isCuPending(map["cu fall"] ?: map["cu_fall"] ?: map["cufall"] ?: "")

        return Client(
            id = map["id"] ?: "IMP-${UUID.randomUUID().toString().take(8)}",
            name = name,
            address = address,
            zone = (map["zone"] ?: map["county"] ?: "").take(50),
            notes = (map["notes"] ?: "").take(500),
            subscribedSteps = subscribedSteps,
            hasGrub = hasGrub,
            mowDayOfWeek = mowDay,
            lawnSizeSqFt = map["lawnsize"]?.toIntOrNull() ?: map["lawn_size"]?.toIntOrNull() ?: 0,
            sunShade = map["sunshade"] ?: map["sun_shade"] ?: "",
            terrain = map["terrain"] ?: "",
            windExposure = map["windexposure"] ?: map["wind_exposure"] ?: "",
            cuSpringPending = cuSpringPending,
            cuFallPending = cuFallPending,
            latitude = lat,
            longitude = lng,
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

    private fun parseDayOfWeek(value: String): Int {
        return when (value.trim().lowercase(Locale.US)) {
            "sun", "sunday" -> Calendar.SUNDAY
            "mon", "monday" -> Calendar.MONDAY
            "tue", "tues", "tuesday" -> Calendar.TUESDAY
            "wed", "wednesday" -> Calendar.WEDNESDAY
            "thu", "thur", "thurs", "thursday" -> Calendar.THURSDAY
            "fri", "friday" -> Calendar.FRIDAY
            "sat", "saturday" -> Calendar.SATURDAY
            else -> 0 // unknown / not specified
        }
    }

    private fun getDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex) ?: ""
            }
        }
        return ""
    }
}
