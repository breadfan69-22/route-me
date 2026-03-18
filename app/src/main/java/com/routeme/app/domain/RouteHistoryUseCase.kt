package com.routeme.app.domain

import com.routeme.app.DailyRecordRow
import com.routeme.app.NonClientStop
import com.routeme.app.data.ClientRepository
import java.util.Calendar

class RouteHistoryUseCase(
    private val clientRepository: ClientRepository
) {
    companion object {
        private const val DAY_MILLIS = 86_400_000L
    }

    data class DayData(
        val dateMillis: Long,
        val rows: List<DailyRecordRow>,
        val nonClientStops: List<NonClientStop>,
        val hasPrevDay: Boolean,
        val hasNextDay: Boolean
    )

    sealed interface DailySummaryResult {
        data class Success(
            val rows: List<DailyRecordRow>,
            val nonClientStops: List<NonClientStop>
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

    private var historyDates: List<Long> = emptyList()

    suspend fun loadDailySummary(): DailySummaryResult {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMillis = cal.timeInMillis
        val endMillis = startMillis + DAY_MILLIS

        return try {
            val rows = clientRepository.getDailyRecords(startMillis, endMillis)
            val nonClientStops = clientRepository.getNonClientStops(startMillis, endMillis)
            if (rows.isEmpty() && nonClientStops.isEmpty()) {
                DailySummaryResult.Empty
            } else {
                DailySummaryResult.Success(rows, nonClientStops)
            }
        } catch (e: Exception) {
            DailySummaryResult.Error("Summary failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun loadRouteHistoryStart(): HistoryResult {
        return try {
            val serviceDates = clientRepository.getDistinctServiceDates()
            val stopDates = clientRepository.getDistinctNonClientStopDates()
            historyDates = (serviceDates + stopDates).distinct().sortedDescending()
            if (historyDates.isEmpty()) {
                HistoryResult.NoHistory
            } else {
                loadHistoryForIndex(0)
            }
        } catch (e: Exception) {
            HistoryResult.Error("History failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun loadRouteHistoryForDate(dateMillis: Long): HistoryResult {
        return try {
            historyDates = clientRepository.getDistinctServiceDates()
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

        val rows = clientRepository.getDailyRecords(startMillis, endMillis)
        val nonClientStops = clientRepository.getNonClientStops(startMillis, endMillis)

        if (rows.isEmpty() && nonClientStops.isEmpty()) {
            return HistoryResult.NoRecordsForDate(dateMillis)
        }

        return HistoryResult.Success(
            DayData(
                dateMillis = dateMillis,
                rows = rows,
                nonClientStops = nonClientStops,
                hasPrevDay = index < historyDates.size - 1,
                hasNextDay = index > 0
            )
        )
    }
}
