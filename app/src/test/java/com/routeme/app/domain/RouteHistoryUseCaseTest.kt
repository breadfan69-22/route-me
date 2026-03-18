package com.routeme.app.domain

import com.routeme.app.ClientStopRow
import com.routeme.app.ClientStopStatus
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
        coEvery { repository.getClientStops(any(), any()) } returns emptyList()
        coEvery { repository.getDailyRecords(any(), any()) } returns emptyList()
        coEvery { repository.getNonClientStops(any(), any()) } returns emptyList()

        val result = useCase.loadDailySummary()

        assertTrue(result is RouteHistoryUseCase.DailySummaryResult.Empty)
    }

    @Test
    fun `loadDailySummary returns success payload when data exists`() = runTest {
        val useCase = RouteHistoryUseCase(repository)
        val rows = listOf(
            ClientStopRow(
                clientId = "1",
                clientName = "Client-1",
                arrivedAtMillis = 1_000L,
                endedAtMillis = 2_000L,
                durationMinutes = 10,
                status = ClientStopStatus.DONE.name,
                serviceTypes = "ROUND_1",
                cancelReason = null,
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

        coEvery { repository.getClientStops(any(), any()) } returns rows
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

        coEvery { repository.getDistinctClientStopDates() } returns emptyList()
        coEvery { repository.getDistinctServiceDates() } returns emptyList()
        coEvery { repository.getDistinctNonClientStopDates() } returns emptyList()

        val result = useCase.loadRouteHistoryStart()

        assertTrue(result is RouteHistoryUseCase.HistoryResult.NoHistory)
    }

    @Test
    fun `loadRouteHistoryStart returns latest day data with navigation flags`() = runTest {
        val useCase = RouteHistoryUseCase(repository)

        coEvery { repository.getDistinctClientStopDates() } returns listOf(2_000L, 1_000L)
        coEvery { repository.getDistinctServiceDates() } returns emptyList()
        coEvery { repository.getDistinctNonClientStopDates() } returns listOf(1_000L)
        coEvery { repository.getClientStops(2_000L, 86_402_000L) } returns listOf(
            ClientStopRow(clientId = "1", clientName = "Client-1", arrivedAtMillis = 1L, endedAtMillis = 2L, durationMinutes = 8, status = ClientStopStatus.DONE.name, serviceTypes = "ROUND_1", cancelReason = null, notes = "")
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

        coEvery { repository.getDistinctClientStopDates() } returns listOf(1_000L)
        coEvery { repository.getDistinctServiceDates() } returns listOf(1_000L)
        coEvery { repository.getDistinctNonClientStopDates() } returns emptyList()

        val result = useCase.loadRouteHistoryForDate(2_000L)

        assertTrue(result is RouteHistoryUseCase.HistoryResult.NoRecordsForRequestedDate)
    }

    @Test
    fun `navigateHistory returns adjacent day after loading history`() = runTest {
        val useCase = RouteHistoryUseCase(repository)

        coEvery { repository.getDistinctClientStopDates() } returns listOf(3_000L, 2_000L)
        coEvery { repository.getDistinctServiceDates() } returns emptyList()
        coEvery { repository.getDistinctNonClientStopDates() } returns emptyList()

        coEvery { repository.getClientStops(3_000L, 86_403_000L) } returns listOf(
            ClientStopRow(clientId = "1", clientName = "Client-1", arrivedAtMillis = 1L, endedAtMillis = 2L, durationMinutes = 8, status = ClientStopStatus.DONE.name, serviceTypes = "ROUND_1", cancelReason = null, notes = "")
        )
        coEvery { repository.getNonClientStops(3_000L, 86_403_000L) } returns emptyList()

        coEvery { repository.getClientStops(2_000L, 86_402_000L) } returns listOf(
            ClientStopRow(clientId = "2", clientName = "Client-2", arrivedAtMillis = 1L, endedAtMillis = 2L, durationMinutes = 8, status = ClientStopStatus.DONE.name, serviceTypes = "ROUND_1", cancelReason = null, notes = "")
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

    @Test
    fun `loadRouteHistoryStart prefers latest non-future day when future-dated records exist`() = runTest {
        val useCase = RouteHistoryUseCase(repository, nowProvider = { 2_500L })

        coEvery { repository.getDistinctClientStopDates() } returns listOf(9_000L, 2_000L, 1_000L)
        coEvery { repository.getDistinctServiceDates() } returns emptyList()
        coEvery { repository.getDistinctNonClientStopDates() } returns emptyList()

        coEvery { repository.getClientStops(2_000L, 86_402_000L) } returns listOf(
            ClientStopRow(clientId = "2", clientName = "Client-2", arrivedAtMillis = 1L, endedAtMillis = 2L, durationMinutes = 8, status = ClientStopStatus.DONE.name, serviceTypes = "ROUND_1", cancelReason = null, notes = "")
        )
        coEvery { repository.getNonClientStops(2_000L, 86_402_000L) } returns emptyList()

        val result = useCase.loadRouteHistoryStart()

        assertTrue(result is RouteHistoryUseCase.HistoryResult.Success)
        val success = result as RouteHistoryUseCase.HistoryResult.Success
        assertEquals(2_000L, success.dayData.dateMillis)
    }

    @Test
    fun `loadRouteHistoryForDate supports day that has only non-client stops`() = runTest {
        val useCase = RouteHistoryUseCase(repository)

        coEvery { repository.getDistinctClientStopDates() } returns emptyList()
        coEvery { repository.getDistinctServiceDates() } returns emptyList()
        coEvery { repository.getDistinctNonClientStopDates() } returns listOf(1_000L)
        coEvery { repository.getClientStops(1_000L, 86_401_000L) } returns emptyList()
        coEvery { repository.getDailyRecords(1_000L, 86_401_000L) } returns emptyList()
        coEvery { repository.getNonClientStops(1_000L, 86_401_000L) } returns listOf(
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

        val result = useCase.loadRouteHistoryForDate(1_000L)

        assertTrue(result is RouteHistoryUseCase.HistoryResult.Success)
        val success = result as RouteHistoryUseCase.HistoryResult.Success
        assertEquals(1_000L, success.dayData.dateMillis)
        assertEquals(0, success.dayData.rows.size)
        assertEquals(1, success.dayData.nonClientStops.size)
    }

    @Test
    fun `gap days are computed between adjacent recorded days`() = runTest {
        // Days at 0, 3*DAY -> gap of 2 days between them
        val day0 = 0L
        val day3 = 86_400_000L * 3

        val useCase = RouteHistoryUseCase(repository)

        coEvery { repository.getDistinctClientStopDates() } returns listOf(day3, day0)
        coEvery { repository.getDistinctServiceDates() } returns emptyList()
        coEvery { repository.getDistinctNonClientStopDates() } returns emptyList()

        coEvery { repository.getClientStops(day3, day3 + 86_400_000L) } returns listOf(
            ClientStopRow(clientId = "1", clientName = "C1", arrivedAtMillis = 1L, endedAtMillis = 2L, durationMinutes = 5, status = ClientStopStatus.DONE.name, serviceTypes = "ROUND_1", cancelReason = null, notes = "")
        )
        coEvery { repository.getNonClientStops(day3, day3 + 86_400_000L) } returns emptyList()

        val result = useCase.loadRouteHistoryStart()

        assertTrue(result is RouteHistoryUseCase.HistoryResult.Success)
        val success = result as RouteHistoryUseCase.HistoryResult.Success
        assertEquals(day3, success.dayData.dateMillis)
        assertEquals(2, success.dayData.gapDaysToOlder)
        assertEquals(0, success.dayData.gapDaysToNewer)
    }

    @Test
    fun `loadWeekSummary returns week data for anchor date`() = runTest {
        // Anchor at Thursday epoch+3 days
        val anchorMillis = 86_400_000L * 3
        val useCase = RouteHistoryUseCase(repository)

        // Return some data for any range queries
        coEvery { repository.getClientStops(any(), any()) } returns emptyList()
        coEvery { repository.getDailyRecords(any(), any()) } returns emptyList()
        coEvery { repository.getNonClientStops(any(), any()) } returns emptyList()

        // Seed one day with data so it's not empty
        coEvery { repository.getClientStops(match { it in 0L..86_400_000L * 7 }, any()) } returns listOf(
            ClientStopRow(clientId = "1", clientName = "C1", arrivedAtMillis = 1L, endedAtMillis = 2L, durationMinutes = 5, status = ClientStopStatus.DONE.name, serviceTypes = "ROUND_1", cancelReason = null, notes = "")
        )

        val result = useCase.loadWeekSummary(anchorMillis)

        assertTrue(result is RouteHistoryUseCase.WeekResult.Success)
        val success = result as RouteHistoryUseCase.WeekResult.Success
        assertEquals(7, success.weekData.days.size)
    }
}
