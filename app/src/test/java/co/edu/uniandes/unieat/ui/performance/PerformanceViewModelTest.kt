package co.edu.uniandes.unieat.ui.performance

import co.edu.uniandes.unieat.core.model.BatchResponse
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.core.model.RemoteEvent
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startsLoadingThenShowsTheSummary() = runTest(dispatcher) {
        val seenDays = mutableListOf<Int>()
        val vm = PerformanceViewModel(StubAnalytics { days ->
            seenDays += days
            summary(days)
        })
        assertEquals(PerformanceUiState.Loading, vm.state.value)

        advanceUntilIdle()

        assertEquals(PerformanceUiState.Content(summary(7)), vm.state.value)
        assertEquals(listOf(7), seenDays) // default period
    }

    @Test
    fun selectDaysReloadsOnlyWhenThePeriodChanges() = runTest(dispatcher) {
        val seenDays = mutableListOf<Int>()
        val vm = PerformanceViewModel(StubAnalytics { days ->
            seenDays += days
            summary(days)
        })
        advanceUntilIdle()

        vm.selectDays(28)
        advanceUntilIdle()
        vm.selectDays(28) // same period again: no extra request
        advanceUntilIdle()

        assertEquals(listOf(7, 28), seenDays)
        assertEquals(28, vm.days.value)
        assertEquals(PerformanceUiState.Content(summary(28)), vm.state.value)
    }

    @Test
    fun forbiddenBecomesRestricted() = runTest(dispatcher) {
        val vm = PerformanceViewModel(StubAnalytics {
            throw ApiException(ApiError(ApiException.FORBIDDEN, "Solo para restaurantes."), 403)
        })
        advanceUntilIdle()
        assertEquals(PerformanceUiState.Restricted("Solo para restaurantes."), vm.state.value)
    }

    @Test
    fun authRequiredBecomesSessionExpired() = runTest(dispatcher) {
        val vm = PerformanceViewModel(StubAnalytics { throw ApiException.authRequired() })
        advanceUntilIdle()
        assertEquals(PerformanceUiState.SessionExpired, vm.state.value)
    }

    @Test
    fun failureBecomesErrorAndRetryReloads() = runTest(dispatcher) {
        var calls = 0
        val vm = PerformanceViewModel(StubAnalytics { days ->
            calls++
            if (calls == 1) throw ApiException.offline() else summary(days)
        })
        advanceUntilIdle()
        assertEquals(ApiException.OFFLINE, (vm.state.value as PerformanceUiState.Error).code)

        vm.load()
        advanceUntilIdle()

        assertEquals(PerformanceUiState.Content(summary(7)), vm.state.value)
    }

    @Test
    fun insufficientDataReachesTheScreenAsIs() = runTest(dispatcher) {
        val sparse = summary(7).copy(sampleSize = 2, insufficientData = true)
        val vm = PerformanceViewModel(StubAnalytics { sparse })
        advanceUntilIdle()

        val content = vm.state.value as PerformanceUiState.Content
        assertEquals(true, content.summary.insufficientData) // the backend decides, we only show it
    }

    private fun summary(days: Int) = PerformanceSummary(
        periodDays = days, impressions = 48, detailOpens = 21, selections = 9,
        reportedArrivals = 5, sampleSize = 48, insufficientData = false,
    )

    private class StubAnalytics(
        private val onPerformance: suspend (Int) -> PerformanceSummary,
    ) : AnalyticsRepository {
        override suspend fun sendBatch(events: List<RemoteEvent>): BatchResponse = error("unused")
        override suspend fun performance(days: Int): PerformanceSummary = onPerformance(days)
    }
}
