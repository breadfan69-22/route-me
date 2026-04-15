package com.routeme.app

import java.util.Calendar
import java.util.Locale
import java.util.UUID

object CsvParsingUtils {
    data class ClientBuildConfig(
        val idPrefix: String,
        val useSourceId: Boolean = true,
        val nameKeys: List<String> = listOf("name"),
        val addressKeys: List<String> = listOf("address"),
        val zoneKeys: List<String> = listOf("zone"),
        val notesKeys: List<String> = listOf("notes"),
        val mowKeys: List<String> = listOf("mow"),
        val latitudeKeys: List<String> = emptyList(),
        val longitudeKeys: List<String> = emptyList(),
        val lawnSizeKeys: List<String> = emptyList(),
        val sunShadeKeys: List<String> = emptyList(),
        val terrainKeys: List<String> = emptyList(),
        val windExposureKeys: List<String> = emptyList(),
        val irrigationKeys: List<String> = emptyList(),
        val requireNonBlankName: Boolean = true,
        val requireNonBlankAddress: Boolean = true,
        val parseEmbeddedStepDate: Boolean = false,
        val skipStatusKeywordsInStepDate: Boolean = false
    )

    fun reassembleCsvRecords(lines: List<String>): List<String> {
        val records = mutableListOf<String>()
        val current = StringBuilder()
        var openQuotes = false

        for (line in lines) {
            if (openQuotes) {
                current.append(" ")
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

    fun parseCsvLine(line: String): List<String> {
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

    fun parseStepDate(
        cellValue: String,
        year: Int,
        allowEmbeddedDate: Boolean = false,
        skipStatusKeywords: Boolean = false
    ): Long? {
        val cleaned = cellValue
            .replace("√", "")
            .replace("✓", "")
            .trim()

        if (cleaned.isBlank() || cleaned.equals("xx", ignoreCase = true)) {
            return null
        }

        if (skipStatusKeywords) {
            val lower = cleaned.lowercase(Locale.US)
            if (lower.contains("sold") || lower.contains("deceased") || lower.contains("cancel")) {
                return null
            }
        }

        val monthDay = if (allowEmbeddedDate) {
            val dateMatch = Regex("(\\d{1,2})\\.(\\d{1,2})").find(cleaned) ?: return null
            Pair(dateMatch.groupValues[1], dateMatch.groupValues[2])
        } else {
            val parts = cleaned.split(".")
            if (parts.size != 2) return null
            Pair(parts[0].trim(), parts[1].trim())
        }

        val month = monthDay.first.toIntOrNull()
        val day = monthDay.second.toIntOrNull()
        if (month == null || day == null || month !in 1..12 || day !in 1..31) {
            return null
        }

        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, 12, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Sentinel for "is a mow client but day-of-week not yet assigned". */
    const val MOW_YES_NO_DAY = -1

    fun parseDayOfWeek(value: String): Int {
        return when (value.trim().lowercase(Locale.US)) {
            "sun", "sunday" -> Calendar.SUNDAY
            "mon", "monday" -> Calendar.MONDAY
            "tue", "tues", "tuesday" -> Calendar.TUESDAY
            "wed", "wednesday" -> Calendar.WEDNESDAY
            "thu", "thur", "thurs", "thursday" -> Calendar.THURSDAY
            "fri", "friday" -> Calendar.FRIDAY
            "sat", "saturday" -> Calendar.SATURDAY
            "yes", "y" -> MOW_YES_NO_DAY
            else -> 0
        }
    }

    fun isCuPending(value: String): Boolean {
        val text = value.trim()
        if (text.isBlank() || !text.contains("*")) return false

        val withoutAsterisk = text.replace("*", "").trim()
        val hasDate = Regex("\\b\\d{1,2}[./-]\\d{1,2}([./-]\\d{2,4})?\\b").containsMatchIn(withoutAsterisk)
        return !hasDate
    }

    fun createClientFromMap(
        map: Map<String, String>,
        yearForDates: Int,
        config: ClientBuildConfig
    ): Client? {
        val nameRaw = firstValue(map, config.nameKeys) ?: return null
        val addressRaw = firstValue(map, config.addressKeys) ?: return null

        if (config.requireNonBlankName && nameRaw.isBlank()) return null
        if (config.requireNonBlankAddress && addressRaw.isBlank()) return null

        val subscribedSteps = mutableSetOf<Int>()
        val records = mutableListOf<ServiceRecord>()

        for (step in 1..6) {
            val cellValue = map["step $step"] ?: ""
            if (cellValue.isBlank()) continue

            val lower = cellValue.lowercase(Locale.US)
            if (lower.contains("sold") || lower.contains("deceased") || lower.contains("cancel")) continue

            subscribedSteps.add(step)
            val completionDate = parseStepDate(
                cellValue = cellValue,
                year = yearForDates,
                allowEmbeddedDate = config.parseEmbeddedStepDate,
                skipStatusKeywords = config.skipStatusKeywordsInStepDate
            )
            if (completionDate != null) {
                val serviceType = ServiceType.forStep(step)
                if (serviceType != null) {
                    records.add(
                        ServiceRecord(
                            serviceType = serviceType,
                            completedAtMillis = completionDate,
                            durationMinutes = 0,
                            lat = null,
                            lng = null
                        )
                    )
                }
            }
        }

        val grubValue = map["grub"] ?: ""
        val hasGrub = grubValue.isNotBlank()
        if (hasGrub) {
            val grubDate = parseStepDate(
                cellValue = grubValue,
                year = yearForDates,
                allowEmbeddedDate = config.parseEmbeddedStepDate,
                skipStatusKeywords = config.skipStatusKeywordsInStepDate
            )
            if (grubDate != null) {
                records.add(
                    ServiceRecord(
                        serviceType = ServiceType.GRUB,
                        completedAtMillis = grubDate,
                        durationMinutes = 0,
                        lat = null,
                        lng = null
                    )
                )
            }
        }

        val mowDay = parseDayOfWeek(firstValue(map, config.mowKeys) ?: "")
        val cuSpringPending = isCuPending(firstValue(map, listOf("cu spring", "cu_spring", "cuspring")) ?: "")
        val cuFallPending = isCuPending(firstValue(map, listOf("cu fall", "cu_fall", "cufall")) ?: "")

        val latitude = firstDouble(map, config.latitudeKeys)?.takeIf { it in -90.0..90.0 }
        val longitude = firstDouble(map, config.longitudeKeys)?.takeIf { it in -180.0..180.0 }

        return Client(
            id = if (config.useSourceId) {
                map["id"] ?: "${config.idPrefix}-${UUID.randomUUID().toString().take(8)}"
            } else {
                "${config.idPrefix}-${UUID.randomUUID().toString().take(8)}"
            },
            name = nameRaw.take(100),
            address = addressRaw.take(200),
            zone = (firstValue(map, config.zoneKeys) ?: "").take(50),
            notes = (firstValue(map, config.notesKeys) ?: "").take(500),
            subscribedSteps = subscribedSteps,
            hasGrub = hasGrub,
            mowDayOfWeek = mowDay,
            lawnSizeSqFt = firstInt(map, config.lawnSizeKeys) ?: 0,
            sunShade = firstValue(map, config.sunShadeKeys) ?: "",
            terrain = firstValue(map, config.terrainKeys) ?: "",
            windExposure = firstValue(map, config.windExposureKeys) ?: "",
            irrigation = firstValue(map, config.irrigationKeys) ?: "",
            cuSpringPending = cuSpringPending,
            cuFallPending = cuFallPending,
            latitude = latitude,
            longitude = longitude,
            records = records
        )
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

    private fun firstValue(map: Map<String, String>, keys: List<String>): String? {
        for (key in keys) {
            val value = map[key]
            if (value != null) return value
        }
        return null
    }

    private fun firstInt(map: Map<String, String>, keys: List<String>): Int? {
        for (key in keys) {
            val value = map[key]?.toIntOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun firstDouble(map: Map<String, String>, keys: List<String>): Double? {
        for (key in keys) {
            val value = map[key]?.toDoubleOrNull()
            if (value != null) return value
        }
        return null
    }
}
