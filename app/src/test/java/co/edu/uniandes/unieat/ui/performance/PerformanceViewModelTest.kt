package co.edu.uniandes.unieat.ui.performance

import co.edu.uniandes.unieat.core.model.BatchResponse
import co.edu.uniandes.unieat.core.model.LocationGuidanceSnapshot
import co.edu.uniandes.unieat.core.model.LocationSignals
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
import org.junit.Assert.assertNull
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

    @Test
    fun restaurantReadsItsOwnEstablishmentsEndpoint() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val analytics = StubAnalytics(
            onPerformance = { calls += "performance"; summary(it) },
            onRestaurant = { calls += "restaurant"; summary(it).copy(impressions = 3) },
        )
        val vm = PerformanceViewModel(analytics, role = "restaurant")
        advanceUntilIdle()

        assertEquals(listOf("restaurant"), calls)
        val content = vm.state.value as PerformanceUiState.Content
        assertEquals(3, content.summary.impressions)
        assertEquals(true, content.ownEstablishmentsOnly)
    }

    @Test
    fun adminGetsTheBq05LocationCard() = runTest(dispatcher) {
        val seenDays = mutableListOf<Int>()
        val analytics = StubAnalytics(onPerformance = { summary(it) }, onLocation = { seenDays += it; guidance(it) })
        val vm = PerformanceViewModel(analytics, role = "admin", showLocationGuidance = true)
        advanceUntilIdle()
        vm.selectDays(28)
        advanceUntilIdle()

        assertEquals(listOf(7, 28), seenDays)
        val content = vm.state.value as PerformanceUiState.Content
        assertEquals(guidance(28), content.locationGuidance)
        assertEquals(false, content.ownEstablishmentsOnly)
    }

    @Test
    fun restaurantGetsBq05ForItsOwnEstablishments() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val analytics = StubAnalytics(
            onRestaurant = { summary(it) },
            onLocation = { calls += "admin"; guidance(it) },
            onRestaurantLocation = { calls += "restaurant"; guidance(it).copy(android = LocationSignals(locationOpens = 1)) },
        ) { summary(it) }
        val vm = PerformanceViewModel(analytics, role = "restaurant", showLocationGuidance = true)
        advanceUntilIdle()

        assertEquals(listOf("restaurant"), calls)
        assertEquals(1, (vm.state.value as PerformanceUiState.Content).locationGuidance?.android?.locationOpens)
    }

    @Test
    fun bq05IsNotRequestedWhenHidden() = runTest(dispatcher) {
        var requested = false
        val vm = PerformanceViewModel(StubAnalytics(onRestaurant = { summary(it) }, onLocation = { requested = true; guidance(it) }) { summary(it) }, role = "restaurant")
        advanceUntilIdle()
        assertEquals(false, requested)
        assertNull((vm.state.value as PerformanceUiState.Content).locationGuidance)
    }

    @Test
    fun bq05FailureKeepsTheOtherMetrics() = runTest(dispatcher) {
        val analytics = StubAnalytics(onPerformance = { summary(it) }, onLocation = { throw ApiException.offline() })
        val vm = PerformanceViewModel(analytics, role = "admin", showLocationGuidance = true)
        advanceUntilIdle()
        assertEquals(PerformanceUiState.Content(summary(7)), vm.state.value)
    }

    private fun guidance(days: Int) = LocationGuidanceSnapshot(
        periodDays = days,
        ios = LocationSignals(locationOpens = 2),
        android = LocationSignals(locationOpens = 5, reportedArrivals = 1),
    )

    private fun summary(days: Int) = PerformanceSummary(
        periodDays = days, impressions = 48, detailOpens = 21, selections = 9,
        reportedArrivals = 5, sampleSize = 48, insufficientData = false,
    )

    private class StubAnalytics(
        private val onRestaurant: (suspend (Int) -> PerformanceSummary)? = null,
        private val onLocation: (suspend (Int) -> LocationGuidanceSnapshot?)? = null,
        private val onRestaurantLocation: (suspend (Int) -> LocationGuidanceSnapshot?)? = null,
        private val onPerformance: suspend (Int) -> PerformanceSummary,
    ) : AnalyticsRepository {
        override suspend fun sendBatch(events: List<RemoteEvent>): BatchResponse = error("unused")
        override suspend fun performance(days: Int): PerformanceSummary = onPerformance(days)
        override suspend fun restaurantPerformance(days: Int): PerformanceSummary =
            onRestaurant?.invoke(days) ?: error("restaurant endpoint not expected")
        override suspend fun locationGuidance(days: Int): LocationGuidanceSnapshot? = onLocation?.invoke(days)
        override suspend fun restaurantLocationGuidance(days: Int): LocationGuidanceSnapshot? = onRestaurantLocation?.invoke(days)
    }
}
