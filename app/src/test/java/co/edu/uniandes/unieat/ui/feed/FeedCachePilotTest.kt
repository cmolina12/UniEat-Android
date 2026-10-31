package co.edu.uniandes.unieat.ui.feed

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.data.pilot.ContentSource
import co.edu.uniandes.unieat.data.pilot.FeedPilot
import co.edu.uniandes.unieat.data.pilot.PilotVariant
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.CachedFeed
import co.edu.uniandes.unieat.data.repository.FeedCache
import co.edu.uniandes.unieat.data.repository.FeedCachePolicy
import co.edu.uniandes.unieat.ui.detail.RecordingTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.time.Duration
import java.time.Instant

/** Samuel — the feed's caching strategy per BQ-13 group, its 24 h TTL and what the pilot is told. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedCachePilotTest {
    private val dispatcher = StandardTestDispatcher()
    private val serverNow = Instant.parse("2026-10-30T17:00:00Z")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** Records every call, like RecordingTracker does for analytics events. */
    private class FakePilot(var group: PilotVariant?) : FeedPilot {
        val calls = mutableListOf<String>()
        override suspend fun variant() = group
        override suspend fun onLoadStarted(token: String, variant: PilotVariant, startedAtMillis: Long, cacheAgeMs: Long?) {
            calls += "start ${variant.wireName} age=$cacheAgeMs"
        }
        override suspend fun onContentDrawn(token: String, source: ContentSource, atMillis: Long) {
            calls += "drawn ${source.wireName}"
        }
        override suspend fun onLoadFailed(token: String, showedSavedCopy: Boolean) {
            calls += "failed copy=$showedSavedCopy"
        }
    }

    private class OneCopy(private val saved: CachedFeed?) : FeedCache {
        override suspend fun save(entry: CachedFeed) = Unit
        override suspend fun load(filters: FeedFilters) = saved
        override suspend fun findMenu(id: String) = null
        override suspend fun clear() = Unit
    }

    private fun menu(id: String) = DailyMenu(
        id = id, title = "Menú $id", validUntil = serverNow + Duration.ofHours(2),
        publishedAt = serverNow - Duration.ofHours(1), establishmentId = "e",
        establishmentName = "Local $id", area = "Centro", lowestPriceCop = 12_000,
    )

    private fun response(vararg ids: String) =
        FeedResponse(serverNow = serverNow, fetchedAt = serverNow, menus = ids.map(::menu), resultCount = ids.size)

    private fun copy(savedAt: Instant) = CachedFeed(FeedFilters(), response("saved"), savedAt = savedAt)

    private fun viewModel(
        pilot: FeedPilot?,
        saved: CachedFeed?,
        recordsPilot: Boolean = true,
        deviceNow: Instant = serverNow,
        feed: suspend (FeedFilters) -> FeedResponse,
    ) = FeedViewModel(
        FeedViewModelTest.StubRepository(feed), RecordingTracker(), MutableStateFlow(FeedFilters()), { deviceNow },
        cache = OneCopy(saved), pilot = pilot, recordsPilot = recordsPilot,
    )

    @Test
    fun cacheEnabledDrawsTheCopyWhileTheRequestRuns() = runTest(dispatcher) {
        val vm = viewModel(FakePilot(PilotVariant.CACHE_ENABLED), copy(serverNow - Duration.ofMinutes(10))) {
            delay(5_000); response("fresh")
        }
        runCurrent()

        val meanwhile = vm.state.value as FeedUiState.Content
        assertTrue(meanwhile.refreshing)
        assertEquals("saved", meanwhile.menus.single().id)
        advanceUntilIdle()
        assertEquals("fresh", (vm.state.value as FeedUiState.Content).menus.single().id)
    }

    @Test
    fun onlineOnlyWaitsForTheServerEvenWithACopy() = runTest(dispatcher) {
        val vm = viewModel(FakePilot(PilotVariant.ONLINE_ONLY), copy(serverNow - Duration.ofMinutes(10))) {
            delay(5_000); response("fresh")
        }
        runCurrent()
        assertEquals(FeedUiState.Loading, vm.state.value)

        advanceUntilIdle()
        assertEquals("fresh", (vm.state.value as FeedUiState.Content).menus.single().id)
    }

    @Test
    fun onlineOnlyOfflineExplainsThePilotInsteadOfShowingTheCopy() = runTest(dispatcher) {
        val vm = viewModel(FakePilot(PilotVariant.ONLINE_ONLY), copy(serverNow - Duration.ofMinutes(10))) {
            throw ApiException.offline()
        }
        advanceUntilIdle()

        assertEquals(FeedUiState.Error(FeedViewModel.PILOT_OFFLINE_MESSAGE, ApiException.OFFLINE), vm.state.value)
    }

    @Test
    fun outsideThePilotTheFeedKeepsItsCache() = runTest(dispatcher) {
        val vm = viewModel(FakePilot(group = null), copy(serverNow - Duration.ofMinutes(10))) { throw ApiException.offline() }
        advanceUntilIdle()

        val state = vm.state.value as FeedUiState.Content
        assertEquals(serverNow - Duration.ofMinutes(10), state.savedAt)
    }

    @Test
    fun aCopyOlderThanADayIsNotUsed() = runTest(dispatcher) {
        val old = copy(serverNow - FeedCachePolicy.MAX_COPY_AGE - Duration.ofMinutes(1))
        val vm = viewModel(pilot = null, saved = old) { throw ApiException.offline() }
        advanceUntilIdle()

        assertEquals(FeedUiState.Error(FeedViewModel.OFFLINE_MESSAGE, ApiException.OFFLINE), vm.state.value)
    }

    @Test
    fun thePilotHearsTheStartTheCopyAndTheFreshMenus() = runTest(dispatcher) {
        val pilot = FakePilot(PilotVariant.CACHE_ENABLED)
        val vm = viewModel(pilot, copy(serverNow - Duration.ofMinutes(10))) { delay(1_000); response("fresh") }
        runCurrent()
        vm.onContentDrawn(vm.state.value as FeedUiState.Content) // what FeedScreen does after the frame
        advanceUntilIdle()
        vm.onContentDrawn(vm.state.value as FeedUiState.Content)
        advanceUntilIdle()

        assertEquals(listOf("start cache_enabled age=600000", "drawn cache", "drawn network"), pilot.calls)
    }

    @Test
    fun aFailedRequestIsReportedWithWhatStayedOnScreen() = runTest(dispatcher) {
        val withCopy = FakePilot(PilotVariant.CACHE_ENABLED)
        viewModel(withCopy, copy(serverNow)) { throw ApiException.offline() }
        val withoutCopy = FakePilot(PilotVariant.ONLINE_ONLY)
        viewModel(withoutCopy, copy(serverNow)) { throw ApiException.offline() }
        advanceUntilIdle()

        assertEquals("failed copy=true", withCopy.calls.last())
        assertEquals("failed copy=false", withoutCopy.calls.last())
    }

    @Test
    fun noCopyMeansNoCacheAgeForThePilot() = runTest(dispatcher) {
        val pilot = FakePilot(PilotVariant.ONLINE_ONLY)
        viewModel(pilot, saved = null) { response("fresh") }
        advanceUntilIdle()

        assertEquals("start online_only age=null", pilot.calls.first())
    }

    @Test
    fun theRecommendationTabFollowsThePolicyButOpensNoVisits() = runTest(dispatcher) {
        val pilot = FakePilot(PilotVariant.ONLINE_ONLY)
        val vm = viewModel(pilot, copy(serverNow), recordsPilot = false) { delay(1_000); response("fresh") }
        runCurrent()
        assertEquals(FeedUiState.Loading, vm.state.value)

        advanceUntilIdle()
        vm.onContentDrawn(vm.state.value as FeedUiState.Content)
        advanceUntilIdle()
        assertTrue(pilot.calls.isEmpty())
    }

    @Test
    fun withoutAPilotNothingIsReported() = runTest(dispatcher) {
        val vm = viewModel(pilot = null, saved = null) { response("fresh") }
        advanceUntilIdle()
        vm.onContentDrawn(vm.state.value as FeedUiState.Content) // must not crash
        assertNull((vm.state.value as FeedUiState.Content).savedAt)
    }
}
