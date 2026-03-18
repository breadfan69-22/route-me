package com.routeme.app.domain

import com.routeme.app.DailyRecordRow
import com.routeme.app.NonClientStop
import com.routeme.app.data.ClientRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RouteHistoryUseCaseTest {

    private lateinit var repository: ClientRepository

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
    }

    @Test
    fun `loadDailySummary returns empty when no records and no stops`() = runTest {
        val useCase = RouteHistoryUseCase(repository)
        coEvery { repository.getDailyRecords(any(), any()) } returns emptyList()
        coEvery { repository.getNonClientStops(any(), any()) } returns emptyList()

        val result = useCase.loadDailySummary()

        assertTrue(result is RouteHistoryUseCase.DailySummaryResult.Empty)
    }

    @Test
    fun `loadDailySummary returns success payload when data exists`() = runTest {
        val useCase = RouteHistoryUseCase(repository)
        val rows = listOf(
            DailyRecordRow(
                clientId = "1",
                clientName = "Client-1",
                serviceType = "ROUND_1",
                arrivedAtMillis = 1_000L,
                completedAtMillis = 2_000L,
                durationMinutes = 10,
                notes = "ok"
            )
        )
        val stops = listOf(
            NonClientStop(
                id = 1L,
                lat = 42.1,
                lng = -85.1,
                address = "Stop",
                arrivedAtMillis = 500L,
                departedAtMillis = 900L,
                durationMinutes = 6,
                label = null
            )
        )

        coEvery { repository.getDailyRecords(any(), any()) } returns rows
        coEvery { repository.getNonClientStops(any(), any()) } returns stops

        val result = useCase.loadDailySummary()

        assertTrue(result is RouteHistoryUseCase.DailySummaryResult.Success)
        val success = result as RouteHistoryUseCase.DailySummaryResult.Success
        assertEquals(rows, success.rows)
        assertEquals(stops, success.nonClientStops)
    }

    @Test
    fun `loadRouteHistoryStart returns no history when no dates`() = runTest {
        val useCase = RouteHistoryUseCase(repository)

        coEvery { repository.getDistinctServiceDates() } returns emptyList()
        coEvery { repository.getDistinctNonClientStopDates() } returns emptyList()

        val result = useCase.loadRouteHistoryStart()

        assertTrue(result is RouteHistoryUseCase.HistoryResult.NoHistory)
    }

    @Test
    fun `loadRouteHistoryStart returns latest day data with navigation flags`() = runTest {
        val useCase = RouteHistoryUseCase(repository)

        coEvery { repository.getDistinctServiceDates() } returns listOf(2_000L, 1_000L)
        coEvery { repository.getDistinctNonClientStopDates() } returns listOf(1_000L)
        coEvery { repository.getDailyRecords(2_000L, 86_402_000L) } returns listOf(
            DailyRecordRow("1", "Client-1", "ROUND_1", 1L, 2L, 8, "")
        )
        coEvery { repository.getNonClientStops(2_000L, 86_402_000L) } returns emptyList()

        val result = useCase.loadRouteHistoryStart()

        assertTrue(result is RouteHistoryUseCase.HistoryResult.Success)
        val success = result as RouteHistoryUseCase.HistoryResult.Success
        assertEquals(2_000L, success.dayData.dateMillis)
        assertTrue(success.dayData.hasPrevDay)
        assertTrue(!success.dayData.hasNextDay)
    }

    @Test
    fun `loadRouteHistoryForDate returns no records for requested date when date missing`() = runTest {
        val useCase = RouteHistoryUseCase(repository)

        coEvery { repository.getDistinctServiceDates() } returns listOf(1_000L)

        val result = useCase.loadRouteHistoryForDate(2_000L)

        assertTrue(result is RouteHistoryUseCase.HistoryResult.NoRecordsForRequestedDate)
    }

    @Test
    fun `navigateHistory returns adjacent day after loading history`() = runTest {
        val useCase = RouteHistoryUseCase(repository)

        coEvery { repository.getDistinctServiceDates() } returns listOf(3_000L, 2_000L)
        coEvery { repository.getDistinctNonClientStopDates() } returns emptyList()

        coEvery { repository.getDailyRecords(3_000L, 86_403_000L) } returns listOf(
            DailyRecordRow("1", "Client-1", "ROUND_1", 1L, 2L, 8, "")
        )
        coEvery { repository.getNonClientStops(3_000L, 86_403_000L) } returns emptyList()

        coEvery { repository.getDailyRecords(2_000L, 86_402_000L) } returns listOf(
            DailyRecordRow("2", "Client-2", "ROUND_1", 1L, 2L, 8, "")
        )
        coEvery { repository.getNonClientStops(2_000L, 86_402_000L) } returns emptyList()

        val start = useCase.loadRouteHistoryStart()
        assertTrue(start is RouteHistoryUseCase.HistoryResult.Success)

        val nav = useCase.navigateHistory(currentDateMillis = 3_000L, delta = 1)
        assertTrue(nav is RouteHistoryUseCase.HistoryResult.Success)
        val success = nav as RouteHistoryUseCase.HistoryResult.Success
        assertEquals(2_000L, success.dayData.dateMillis)
        assertTrue(!success.dayData.hasPrevDay)
        assertTrue(success.dayData.hasNextDay)
    }
}
