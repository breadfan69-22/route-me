package com.routeme.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ImportResult(
    val clients: List<Client>,
    val message: String
)

object ClientImportParser {
    private val XLSX_CLIENT_BUILD_CONFIG = CsvParsingUtils.ClientBuildConfig(
        idPrefix = "IMP",
        useSourceId = false,
        nameKeys = listOf("name"),
        addressKeys = listOf("address"),
        zoneKeys = listOf("zone"),
        notesKeys = listOf("notes"),
        mowKeys = listOf("mow"),
        latitudeKeys = emptyList(),
        longitudeKeys = emptyList(),
        lawnSizeKeys = emptyList(),
        sunShadeKeys = emptyList(),
        terrainKeys = emptyList(),
        windExposureKeys = emptyList(),
        requireNonBlankName = true,
        requireNonBlankAddress = true,
        parseEmbeddedStepDate = false,
        skipStatusKeywordsInStepDate = false
    )

    private val IMPORT_CLIENT_BUILD_CONFIG = CsvParsingUtils.ClientBuildConfig(
        idPrefix = "IMP",
        useSourceId = true,
        nameKeys = listOf("name", "client"),
        addressKeys = listOf("address"),
        zoneKeys = listOf("zone", "county"),
        notesKeys = listOf("notes"),
        mowKeys = listOf("mow", "mowday", "mow_day"),
        latitudeKeys = listOf("lat", "latitude"),
        longitudeKeys = listOf("lng", "longitude"),
        lawnSizeKeys = listOf("lawnsize", "lawn_size"),
        sunShadeKeys = listOf("sunshade", "sun_shade"),
        terrainKeys = listOf("terrain"),
        windExposureKeys = listOf("windexposure", "wind_exposure"),
        requireNonBlankName = false,
        requireNonBlankAddress = false,
        parseEmbeddedStepDate = false,
        skipStatusKeywordsInStepDate = false
    )

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

                val client = CsvParsingUtils.createClientFromMap(
                    map = map,
                    yearForDates = yearForDates,
                    config = XLSX_CLIENT_BUILD_CONFIG
                )
                if (client != null) {
                    clients.add(client)
                }
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

    // ── CSV import ───────────────────────────────────────────────────────

    private fun parseCsv(context: Context, uri: Uri): ImportResult {
        val input = context.contentResolver.openInputStream(uri)
            ?: return ImportResult(emptyList(), "Unable to read selected file.")

        val lines = BufferedReader(InputStreamReader(input)).readLines()
        if (lines.isEmpty()) {
            return ImportResult(emptyList(), "CSV is empty.")
        }

        val csvRecords = CsvParsingUtils.reassembleCsvRecords(lines)
        val headers = CsvParsingUtils.parseCsvLine(csvRecords.first())
            .map { it.trim().removePrefix("√").trim().lowercase(Locale.US) }
        val yearForDates = Calendar.getInstance().get(Calendar.YEAR)
        val clients = mutableListOf<Client>()

        csvRecords.drop(1).forEach { record ->
            if (record.isBlank()) return@forEach

            val cols = CsvParsingUtils.parseCsvLine(record).map { it.trim() }
            val map = headers.mapIndexedNotNull { index, header ->
                if (index < cols.size) header to cols[index] else null
            }.toMap()

            val client = CsvParsingUtils.createClientFromMap(map, yearForDates, IMPORT_CLIENT_BUILD_CONFIG)
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

        val yearForDates = Calendar.getInstance().get(Calendar.YEAR)
        val clients = mutableListOf<Client>()

        rows.drop(1).forEach { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@forEach

            val map = headers.mapIndexedNotNull { index, header ->
                if (index < cells.size) header to cells[index].text().trim() else null
            }.toMap()

            val client = CsvParsingUtils.createClientFromMap(map, yearForDates, IMPORT_CLIENT_BUILD_CONFIG)
            if (client != null) clients.add(client)
        }

        return ImportResult(clients, "Imported ${clients.size} client(s) from HTML table.")
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
