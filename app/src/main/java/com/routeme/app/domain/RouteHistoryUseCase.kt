package com.routeme.app.domain

import com.routeme.app.ClientStopRow
import com.routeme.app.ClientStopStatus
import com.routeme.app.DailyRecordRow
import com.routeme.app.NonClientStop
import com.routeme.app.SHOP_LAT
import com.routeme.app.SHOP_LNG
import com.routeme.app.data.ClientRepository
import com.routeme.app.data.WeatherRepository
import com.routeme.app.model.DailyWeather
import java.util.Calendar

class RouteHistoryUseCase(
    private val clientRepository: ClientRepository,
    private val weatherRepository: WeatherRepository,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val DAY_MILLIS = 86_400_000L
    }

    data class DayData(
        val dateMillis: Long,
        val rows: List<ClientStopRow>,
        val nonClientStops: List<NonClientStop>,
        val hasPrevDay: Boolean,
        val hasNextDay: Boolean,
        /** Calendar days between this day and next newer recorded day (0 if consecutive or no next). */
        val gapDaysToNewer: Int = 0,
        /** Calendar days between this day and next older recorded day (0 if consecutive or no prev). */
        val gapDaysToOlder: Int = 0,
        val weather: DailyWeather? = null
    )

    sealed interface DailySummaryResult {
        data class Success(
            val rows: List<ClientStopRow>,
            val nonClientStops: List<NonClientStop>,
            val weather: DailyWeather? = null
        ) : DailySummaryResult

        data object Empty : DailySummaryResult
        data class Error(val message: String) : DailySummaryResult
    }

    sealed interface HistoryResult {
        data class Success(val dayData: DayData) : HistoryResult
        data object NoHistory : HistoryResult
        data object NoRecordsForRequestedDate : HistoryResult
        data class NoRecordsForDate(val dateMillis: Long) : HistoryResult
        data object NavigationUnavailable : HistoryResult
        data class Error(val message: String) : HistoryResult
    }

    data class WeekDay(
        val dateMillis: Long,
        val rows: List<ClientStopRow>,
        val nonClientStops: List<NonClientStop>
    )

    data class WeekData(
        val startMillis: Long,
        val endMillis: Long,
        val days: List<WeekDay>
    )

    sealed interface WeekResult {
        data class Success(val weekData: WeekData) : WeekResult
        data object NoHistory : WeekResult
        data class Error(val message: String) : WeekResult
    }

    private var historyDates: List<Long> = emptyList()

    suspend fun loadDailySummary(): DailySummaryResult {
        val startMillis = localDayStartMillis(nowProvider())
        val endMillis = startMillis + DAY_MILLIS

        return try {
            val rows = getClientStopsForRange(startMillis, endMillis)
            val nonClientStops = clientRepository.getNonClientStops(startMillis, endMillis)
            if (rows.isEmpty() && nonClientStops.isEmpty()) {
                DailySummaryResult.Empty
            } else {
                val (cLat, cLng) = stopsCentroid(rows, nonClientStops)
                val weather = runCatching { weatherRepository.getWeatherForDay(startMillis, cLat, cLng) }.getOrNull()
                DailySummaryResult.Success(rows, nonClientStops, weather)
            }
        } catch (e: Exception) {
            DailySummaryResult.Error("Summary failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun loadRouteHistoryStart(): HistoryResult {
        return try {
            val clientStopDates = clientRepository.getDistinctClientStopDates()
            val serviceDates = clientRepository.getDistinctServiceDates()
            val stopDates = clientRepository.getDistinctNonClientStopDates()
            historyDates = (clientStopDates + serviceDates + stopDates).distinct().sortedDescending()
            if (historyDates.isEmpty()) {
                HistoryResult.NoHistory
            } else {
                val nowMillis = nowProvider()
                val preferredIndex = historyDates.indexOfFirst { it <= nowMillis }
                loadHistoryForIndex(if (preferredIndex >= 0) preferredIndex else 0)
            }
        } catch (e: Exception) {
            HistoryResult.Error("History failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun loadRouteHistoryForDate(dateMillis: Long): HistoryResult {
        return try {
            val clientStopDates = clientRepository.getDistinctClientStopDates()
            val serviceDates = clientRepository.getDistinctServiceDates()
            val stopDates = clientRepository.getDistinctNonClientStopDates()
            historyDates = (clientStopDates + serviceDates + stopDates).distinct().sortedDescending()
            val index = historyDates.indexOf(dateMillis)
            if (index == -1) {
                HistoryResult.NoRecordsForRequestedDate
            } else {
                loadHistoryForIndex(index)
            }
        } catch (e: Exception) {
            HistoryResult.Error("History failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun navigateHistory(currentDateMillis: Long, delta: Int): HistoryResult {
        val currentIndex = historyDates.indexOf(currentDateMillis)
        if (currentIndex == -1) return HistoryResult.NavigationUnavailable

        val newIndex = currentIndex + delta
        if (newIndex !in historyDates.indices) return HistoryResult.NavigationUnavailable

        return loadHistoryForIndex(newIndex)
    }

    private suspend fun loadHistoryForIndex(index: Int): HistoryResult {
        val dateMillis = historyDates[index]
        val startMillis = dateMillis
        val endMillis = dateMillis + DAY_MILLIS

        val rows = getClientStopsForRange(startMillis, endMillis)
        val nonClientStops = clientRepository.getNonClientStops(startMillis, endMillis)

        if (rows.isEmpty() && nonClientStops.isEmpty()) {
            return HistoryResult.NoRecordsForDate(dateMillis)
        }

        val (cLat, cLng) = stopsCentroid(rows, nonClientStops)
        val weather = runCatching { weatherRepository.getWeatherForDay(dateMillis, cLat, cLng) }.getOrNull()

        val hasPrev = index < historyDates.size - 1
        val hasNext = index > 0

        // Gap = calendar days between adjacent recorded days minus 1
        val gapToNewer = if (hasNext) {
            ((historyDates[index - 1] - dateMillis) / DAY_MILLIS).toInt() - 1
        } else 0
        val gapToOlder = if (hasPrev) {
            ((dateMillis - historyDates[index + 1]) / DAY_MILLIS).toInt() - 1
        } else 0

        return HistoryResult.Success(
            DayData(
                dateMillis = dateMillis,
                rows = rows,
                nonClientStops = nonClientStops,
                hasPrevDay = hasPrev,
                hasNextDay = hasNext,
                gapDaysToNewer = gapToNewer.coerceAtLeast(0),
                gapDaysToOlder = gapToOlder.coerceAtLeast(0),
                weather = weather
            )
        )
    }

    private suspend fun getClientStopsForRange(startMillis: Long, endMillis: Long): List<ClientStopRow> {
        val rows = clientRepository.getClientStops(startMillis, endMillis)
        if (rows.isNotEmpty()) return rows

        val legacyRows = clientRepository.getDailyRecords(startMillis, endMillis)
        return legacyRows.map { legacy ->
            ClientStopRow(
                clientId = legacy.clientId,
                clientName = legacy.clientName,
                arrivedAtMillis = legacy.arrivedAtMillis,
                endedAtMillis = legacy.completedAtMillis,
                durationMinutes = legacy.durationMinutes,
                status = ClientStopStatus.DONE.name,
                serviceTypes = legacy.serviceType,
                cancelReason = null,
                notes = legacy.notes
            )
        }
    }

    /**
     * Load a week summary anchored to [anchorMillis].
     * Computes the Monday-start week containing the anchor date.
     */
    suspend fun loadWeekSummary(anchorMillis: Long): WeekResult {
        return try {
            val cal = Calendar.getInstance()
            cal.timeInMillis = anchorMillis
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            // Roll back to Monday
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val daysFromMon = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
            cal.add(Calendar.DAY_OF_YEAR, -daysFromMon)
            val weekStartMillis = cal.timeInMillis

            val days = (0 until 7).map { offset ->
                val dayStart = weekStartMillis + offset * DAY_MILLIS
                val dayEnd = dayStart + DAY_MILLIS
                val rows = getClientStopsForRange(dayStart, dayEnd)
                val stops = clientRepository.getNonClientStops(dayStart, dayEnd)
                WeekDay(dateMillis = dayStart, rows = rows, nonClientStops = stops)
            }

            if (days.all { it.rows.isEmpty() && it.nonClientStops.isEmpty() }) {
                WeekResult.NoHistory
            } else {
                WeekResult.Success(
                    WeekData(
                        startMillis = weekStartMillis,
                        endMillis = weekStartMillis + 7 * DAY_MILLIS,
                        days = days
                    )
                )
            }
        } catch (e: Exception) {
            WeekResult.Error("Week summary failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun localDayStartMillis(timestampMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Compute the geographic centroid from client stops and non-client stops.
     * Falls back to [SHOP_LAT]/[SHOP_LNG] when no coordinates are available.
     */
    private fun stopsCentroid(
        rows: List<ClientStopRow>,
        nonClientStops: List<NonClientStop>
    ): Pair<Double, Double> {
        val lats = mutableListOf<Double>()
        val lngs = mutableListOf<Double>()

        rows.forEach { row ->
            if (row.lat != null && row.lng != null) {
                lats += row.lat
                lngs += row.lng
            }
        }
        nonClientStops.forEach { stop ->
            lats += stop.lat
            lngs += stop.lng
        }

        return if (lats.isNotEmpty()) {
            lats.average() to lngs.average()
        } else {
            SHOP_LAT to SHOP_LNG
        }
    }
}
