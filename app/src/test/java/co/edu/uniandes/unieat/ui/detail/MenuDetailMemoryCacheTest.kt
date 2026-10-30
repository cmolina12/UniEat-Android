package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.data.cache.MenuDetailCache
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

/** The detail with the LruCache: cache then network. */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuDetailMemoryCacheTest {
    private val dispatcher = StandardTestDispatcher()
    private val serverNow = Instant.parse("2026-10-28T17:00:00Z")
    private val cache = MenuDetailCache { serverNow }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aMenuOpenedBeforeShowsAtOnceWithoutASpinner() = runTest(dispatcher) {
        cache.put("m", detail("Tazón"))

        val vm = viewModel { detail("Tazón") }

        // Before the request runs: already the content, not Loading.
        val shown = vm.state.value as MenuDetailUiState.Content
        assertEquals("Tazón", shown.menu.title)
        assertNull(shown.savedAt)
    }

    @Test
    fun theNetworkThenReplacesItAndRefreshesTheCache() = runTest(dispatcher) {
        cache.put("m", detail("Tazón"))
        val vm = viewModel { detail("Tazón de pollo") } // the owner changed the menu meanwhile

        advanceUntilIdle()

        assertEquals("Tazón de pollo", (vm.state.value as MenuDetailUiState.Content).menu.title)
        assertEquals("Tazón de pollo", cache["m"]!!.response.menu.title)
    }

    @Test
    fun aFirstOpenStillShowsLoadingAndFillsTheCache() = runTest(dispatcher) {
        val vm = viewModel { detail("Tazón") }
        assertEquals(MenuDetailUiState.Loading, vm.state.value)

        advanceUntilIdle()

        assertEquals("Tazón", cache["m"]!!.response.menu.title)
    }

    @Test
    fun offlineTheCachedDetailStaysAndSaysHowOldItIs() = runTest(dispatcher) {
        cache.put("m", detail("Tazón"))
        val vm = viewModel { throw ApiException.offline() }

        advanceUntilIdle()

        val shown = vm.state.value as MenuDetailUiState.Content
        assertEquals("Tazón", shown.menu.title)
        assertEquals(serverNow, shown.savedAt)
    }

    @Test
    fun aClosedMenuIsDroppedFromTheCache() = runTest(dispatcher) {
        cache.put("m", detail("Tazón"))
        val vm = viewModel { throw ApiException(ApiError(ApiException.GONE, "Ya no está disponible."), 410) }

        advanceUntilIdle()

        assertTrue(vm.state.value is MenuDetailUiState.Gone)
        assertNull(cache["m"])
    }

    @Test
    fun losingTheSignalAfterLoadingShowsTheDetailAsASavedCopy() = runTest(dispatcher) {
        val connection = MutableStateFlow(true)
        val vm = MenuDetailViewModel(
            "m", MenuDetailViewModelTest.StubRepository { detail("Tazón") }, StubLocation(), RecordingTracker(), StubReports(),
            memoryCache = cache, online = connection,
        ) { serverNow }
        advanceUntilIdle()
        assertNull((vm.state.value as MenuDetailUiState.Content).savedAt)

        connection.value = false
        advanceUntilIdle()

        val shown = vm.state.value as MenuDetailUiState.Content
        assertEquals("Tazón", shown.menu.title)
        assertEquals(serverNow, shown.savedAt)
    }

    private fun viewModel(detail: suspend () -> MenuDetailResponse) = MenuDetailViewModel(
        "m", MenuDetailViewModelTest.StubRepository(detail), StubLocation(), RecordingTracker(), StubReports(),
        memoryCache = cache,
    ) { serverNow }

    private fun detail(title: String) = MenuDetailResponse(
        serverNow = serverNow, fetchedAt = serverNow,
        menu = DailyMenu(
            id = "m", title = title, validUntil = serverNow + Duration.ofHours(1), publishedAt = serverNow,
            establishmentId = "e", establishmentName = "Bowls", area = "Centro", lowestPriceCop = 12_000,
        ),
    )
}
