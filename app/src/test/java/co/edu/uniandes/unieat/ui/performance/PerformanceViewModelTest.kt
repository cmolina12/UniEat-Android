package co.edu.uniandes.unieat.ui.performance

import co.edu.uniandes.unieat.core.model.BatchResponse
import co.edu.uniandes.unieat.core.model.CompletenessGroup
import co.edu.uniandes.unieat.core.model.CompletenessGroups
import co.edu.uniandes.unieat.core.model.LocationCompletenessSnapshot
import co.edu.uniandes.unieat.core.model.LocationGuidanceSnapshot
import co.edu.uniandes.unieat.core.model.LocationSignals
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.core.model.RemoteEvent
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.telemetry.FeedLoadRecord
import co.edu.uniandes.unieat.data.telemetry.FeedLoadReport
import co.edu.uniandes.unieat.data.telemetry.FeedLoadRepository
import kotlinx.coroutines.Dispatchers
import co.edu.uniandes.unieat.data.local.PerformancePreferences
import co.edu.uniandes.unieat.data.local.PerformanceSnapshots
import co.edu.uniandes.unieat.data.local.SavedPerformance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startsLoadingThenShowsTheSummary() = runTest(dispatcher) {
        val seenDays = mutableListOf<Int>()
        val vm = viewModelFor(StubAnalytics { days ->
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
        val vm = viewModelFor(StubAnalytics { days ->
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
    fun switchingBackToAPeriodShowsItsNumbersAtOnceThenRefreshes() = runTest(dispatcher) {
        var sevenDayCalls = 0
        val vm = viewModelFor(StubAnalytics { days ->
            if (days == 7) sevenDayCalls++
            // The second time, the 7-day numbers grew: new events arrived meanwhile.
            summary(days).copy(impressions = if (days == 7 && sevenDayCalls > 1) 50 else 48)
        })
        advanceUntilIdle()
        vm.selectDays(28)
        advanceUntilIdle()

        vm.selectDays(7)
        // Before the request runs: the 7-day result kept in the SparseArray, not a spinner.
        assertEquals(48, (vm.state.value as PerformanceUiState.Content).summary.impressions)

        advanceUntilIdle()
        assertEquals(50, (vm.state.value as PerformanceUiState.Content).summary.impressions)
    }

    @Test
    fun aPeriodNeverLoadedStillShowsLoading() = runTest(dispatcher) {
        val vm = viewModelFor(StubAnalytics { days -> summary(days) })
        advanceUntilIdle()

        vm.selectDays(28)

        assertEquals(PerformanceUiState.Loading, vm.state.value)
    }

    @Test
    fun aSlowAnswerForTheOldPeriodNeverReplacesTheNewOne() = runTest(dispatcher) {
        val vm = viewModelFor(StubAnalytics { days ->
            // The 7-day request is slow (weak signal); the 28-day one answers quickly.
            delay(if (days == 7) 5_000 else 100)
            summary(days)
        })
        runCurrent() // the 7-day request starts and waits for its slow answer
        vm.selectDays(28) // while the 7-day request is still in flight

        advanceUntilIdle()

        assertEquals(28, vm.days.value)
        assertEquals(28, (vm.state.value as PerformanceUiState.Content).summary.periodDays)
    }

    @Test
    fun bq01IsOnlyRequestedForAdmins() = runTest(dispatcher) {
        val loads = CountingFeedLoads()
        val restaurant = PerformanceViewModel(
            StubAnalytics(onRestaurant = { summary(it) }) { summary(it) }, loads, role = "restaurant", io = dispatcher,
        )
        advanceUntilIdle()
        assertNull((restaurant.state.value as PerformanceUiState.Content).feedLoadReport)
        assertEquals(0, loads.calls)

        val admin = PerformanceViewModel(StubAnalytics { summary(it) }, loads, role = "admin", io = dispatcher)
        advanceUntilIdle()
        assertEquals(12, (admin.state.value as PerformanceUiState.Content).feedLoadReport?.attempts)
        assertEquals(1, loads.calls)
    }

    private class CountingFeedLoads : FeedLoadRepository {
        var calls = 0
        override suspend fun upload(loads: List<FeedLoadRecord>) = Unit
        override suspend fun summary(days: Int): FeedLoadReport {
            calls++
            return FeedLoadReport(periodDays = days, attempts = 12, abandoned = 1)
        }
    }

    @Test
    fun forbiddenBecomesRestricted() = runTest(dispatcher) {
        val vm = viewModelFor(StubAnalytics {
            throw ApiException(ApiError(ApiException.FORBIDDEN, "Solo para restaurantes."), 403)
        })
        advanceUntilIdle()
        assertEquals(PerformanceUiState.Restricted("Solo para restaurantes."), vm.state.value)
    }

    @Test
    fun authRequiredBecomesSessionExpired() = runTest(dispatcher) {
        val vm = viewModelFor(StubAnalytics { throw ApiException.authRequired() })
        advanceUntilIdle()
        assertEquals(PerformanceUiState.SessionExpired, vm.state.value)
    }

    @Test
    fun failureBecomesErrorAndRetryReloads() = runTest(dispatcher) {
        var calls = 0
        val vm = viewModelFor(StubAnalytics { days ->
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
        val vm = viewModelFor(StubAnalytics { sparse })
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
        val vm = viewModelFor(analytics, role = "restaurant")
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
        val vm = viewModelFor(analytics, role = "admin", showLocationGuidance = true)
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
        val vm = viewModelFor(analytics, role = "restaurant", showLocationGuidance = true)
        advanceUntilIdle()

        assertEquals(listOf("restaurant"), calls)
        assertEquals(1, (vm.state.value as PerformanceUiState.Content).locationGuidance?.android?.locationOpens)
    }

    @Test
    fun bq05IsNotRequestedWhenHidden() = runTest(dispatcher) {
        var requested = false
        val vm = viewModelFor(StubAnalytics(onRestaurant = { summary(it) }, onLocation = { requested = true; guidance(it) }) { summary(it) }, role = "restaurant")
        advanceUntilIdle()
        assertEquals(false, requested)
        assertNull((vm.state.value as PerformanceUiState.Content).locationGuidance)
    }

    @Test
    fun bq05FailureKeepsTheOtherMetrics() = runTest(dispatcher) {
        val analytics = StubAnalytics(onPerformance = { summary(it) }, onLocation = { throw ApiException.offline() })
        val vm = viewModelFor(analytics, role = "admin", showLocationGuidance = true)
        advanceUntilIdle()
        assertEquals(PerformanceUiState.Content(summary(7)), vm.state.value)
    }

    @Test
    fun adminGetsTheBq10ComparisonForTheSamePeriod() = runTest(dispatcher) {
        val seenDays = mutableListOf<Int>()
        val analytics = StubAnalytics(onPerformance = { summary(it) }, onCompleteness = { seenDays += it; completeness(it) })
        val vm = viewModelFor(analytics, role = "admin", showLocationGuidance = true)
        advanceUntilIdle()
        vm.selectDays(28)
        advanceUntilIdle()

        assertEquals(listOf(7, 28), seenDays)
        assertEquals(completeness(28), (vm.state.value as PerformanceUiState.Content).locationCompleteness)
    }

    @Test
    fun bq10FailureKeepsTheOtherMetrics() = runTest(dispatcher) {
        val analytics = StubAnalytics(
            onLocation = { guidance(it) },
            onCompleteness = { throw ApiException.offline() },
        ) { summary(it) }
        val vm = viewModelFor(analytics, role = "admin", showLocationGuidance = true)
        advanceUntilIdle()

        val content = vm.state.value as PerformanceUiState.Content
        assertEquals(guidance(7), content.locationGuidance)
        assertNull(content.locationCompleteness)
    }

    @Test
    fun theCardsAreRequestedInParallelNotOneAfterTheOther() = runTest(dispatcher) {
        val analytics = StubAnalytics(
            onLocation = { delay(1_000); guidance(it) },
            onCompleteness = { delay(1_000); completeness(it) },
        ) { delay(1_000); summary(it) }
        viewModelFor(analytics, role = "admin", showLocationGuidance = true)
        advanceUntilIdle()

        // Three requests of one second each: in parallel the screen waits one second, not three.
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun opensWithThePeriodSavedLastTime() = runTest(dispatcher) {
        val seenDays = mutableListOf<Int>()
        val vm = viewModelFor(StubAnalytics { seenDays += it; summary(it) }, preferences = MemoryPreferences(28))
        advanceUntilIdle()

        assertEquals(28, vm.days.value)
        assertEquals("no request with the default 7 first", listOf(28), seenDays)
    }

    @Test
    fun choosingAPeriodSavesIt() = runTest(dispatcher) {
        val preferences = MemoryPreferences(7)
        val vm = viewModelFor(StubAnalytics { summary(it) }, preferences = preferences)
        advanceUntilIdle()

        vm.selectDays(28)
        advanceUntilIdle()

        assertEquals(28, preferences.saved.value)
    }

    @Test
    fun offlineShowsTheLastResultSavedForThatPeriod() = runTest(dispatcher) {
        var offline = false
        val snapshots = MemorySnapshots()
        val analytics = StubAnalytics(onLocation = { guidance(it) }, onCompleteness = { completeness(it) }) {
            if (offline) throw ApiException.offline() else summary(it)
        }
        val vm = viewModelFor(analytics, role = "admin", showLocationGuidance = true, snapshots = snapshots)
        advanceUntilIdle()
        assertNull((vm.state.value as PerformanceUiState.Content).savedAt)

        offline = true
        vm.load()
        advanceUntilIdle()

        val content = vm.state.value as PerformanceUiState.Content
        assertEquals(now, content.savedAt)
        assertEquals(summary(7), content.summary)
        assertEquals(completeness(7), content.locationCompleteness)
    }

    @Test
    fun offlineWithoutASavedCopyIsStillAnError() = runTest(dispatcher) {
        val vm = viewModelFor(StubAnalytics { throw ApiException.offline() }, snapshots = MemorySnapshots())
        advanceUntilIdle()

        assertEquals(ApiException.OFFLINE, (vm.state.value as PerformanceUiState.Error).code)
    }

    @Test
    fun aSavedCopyIsNeverShownToAnotherRole() = runTest(dispatcher) {
        val snapshots = MemorySnapshots()
        viewModelFor(StubAnalytics(onRestaurant = { summary(it) }) { summary(it) }, role = "admin", snapshots = snapshots)
        advanceUntilIdle()

        val vm = viewModelFor(StubAnalytics(onRestaurant = { throw ApiException.offline() }) { summary(it) }, role = "restaurant", snapshots = snapshots)
        advanceUntilIdle()

        assertTrue(vm.state.value is PerformanceUiState.Error)
    }

    @Test
    fun comingBackOnlineReplacesTheSavedCopy() = runTest(dispatcher) {
        var offline = false
        val online = MutableStateFlow(true)
        val vm = viewModelFor(
            StubAnalytics { if (offline) throw ApiException.offline() else summary(it) },
            snapshots = MemorySnapshots(),
            online = online,
        )
        advanceUntilIdle()
        offline = true
        online.value = false
        vm.load()
        advanceUntilIdle()
        assertEquals(now, (vm.state.value as PerformanceUiState.Content).savedAt)

        offline = false
        online.value = true
        advanceUntilIdle()

        assertNull((vm.state.value as PerformanceUiState.Content).savedAt)
    }

    private val now = Instant.parse("2026-10-27T20:00:00Z")

    private fun viewModelFor(
        repository: AnalyticsRepository,
        role: String? = null,
        showLocationGuidance: Boolean = false,
        preferences: PerformancePreferences? = null,
        snapshots: PerformanceSnapshots? = null,
        online: Flow<Boolean>? = null,
    ) = PerformanceViewModel(
        repository, role = role, showLocationGuidance = showLocationGuidance, io = dispatcher,
        preferences = preferences, snapshots = snapshots, online = online, clock = { now },
    )

    private class MemoryPreferences(initial: Int) : PerformancePreferences {
        val saved = MutableStateFlow(initial)
        override val days: Flow<Int> = saved

        override suspend fun setDays(days: Int) {
            saved.value = days
        }
    }

    private class MemorySnapshots : PerformanceSnapshots {
        private val saved = mutableMapOf<Pair<String, Int>, SavedPerformance>()

        override suspend fun save(snapshot: SavedPerformance) {
            saved[snapshot.role to snapshot.days] = snapshot
        }

        override suspend fun load(role: String, days: Int) = saved[role to days]

        override suspend fun clear() = saved.clear()
    }

    private fun completeness(days: Int) = LocationCompletenessSnapshot(
        periodDays = days,
        groups = CompletenessGroups(
            complete = CompletenessGroup(establishments = 6, arrivals = 9, notFoundReports = 1, notFoundShare = 0.1),
            incomplete = CompletenessGroup(establishments = 2, arrivals = 2, notFoundReports = 3, notFoundShare = 0.6),
        ),
    )

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
        private val onCompleteness: (suspend (Int) -> LocationCompletenessSnapshot?)? = null,
        private val onPerformance: suspend (Int) -> PerformanceSummary,
    ) : AnalyticsRepository {
        override suspend fun sendBatch(events: List<RemoteEvent>): BatchResponse = error("unused")
        override suspend fun performance(days: Int): PerformanceSummary = onPerformance(days)
        override suspend fun restaurantPerformance(days: Int): PerformanceSummary =
            onRestaurant?.invoke(days) ?: error("restaurant endpoint not expected")
        override suspend fun locationGuidance(days: Int): LocationGuidanceSnapshot? = onLocation?.invoke(days)
        override suspend fun restaurantLocationGuidance(days: Int): LocationGuidanceSnapshot? = onRestaurantLocation?.invoke(days)
        override suspend fun locationCompleteness(days: Int): LocationCompletenessSnapshot? = onCompleteness?.invoke(days)
    }
}
