package co.edu.uniandes.unieat.ui.publish

import co.edu.uniandes.unieat.core.model.CloseResponse
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.EstablishmentRequestBody
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.core.model.OwnEstablishment
import co.edu.uniandes.unieat.data.local.MemoryOfflineStore
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.EstablishmentRepository
import co.edu.uniandes.unieat.data.repository.MenuRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Eventual connectivity of "Publicar": saved lists, drafts on disk and network-only actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class PublishOfflineTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-10-27T15:00:00Z")
    private val ajonjoli = OwnEstablishment(
        "e1", "Ajonjolí", "Centro", "Carrera 1 #20-41", "Pasillo de baldosas", listOf("Efectivo"), approved = true,
    )
    private val store = MemoryOfflineStore()
    private val server = OfflineMenus()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aSuccessfulLoadIsSavedForLater() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()

        assertNotNull(store.files[PublishViewModel.PUBLISH_DATA_KEY])
    }

    @Test
    fun offlineShowsTheSavedListsWithTheirTime() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()
        server.offline = true

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(ajonjoli), state.approved)
        assertEquals("Almuerzo casero", state.myMenus.single().title)
        assertNotNull(state.savedAt)
        assertTrue(state.offline)
        assertEquals("e1", state.form.establishmentId) // the form still starts filled in
    }

    @Test
    fun offlineWithoutACopySaysWhatWillHappen() = runTest(dispatcher) {
        server.offline = true
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(PublishViewModel.NO_SAVED_DATA_MESSAGE, vm.state.value.message)
        assertTrue(vm.state.value.offline)
    }

    @Test
    fun theDraftSurvivesClosingTheApp() = runTest(dispatcher) {
        val first = viewModel()
        advanceUntilIdle()
        fillOneDish(first)
        advanceUntilIdle() // past the 500 ms debounce

        val reopened = viewModel()
        advanceUntilIdle()

        assertEquals("Bandeja paisa", reopened.state.value.form.dishes.single().name)
        assertEquals("Recuperamos el borrador que no alcanzaste a publicar.", reopened.state.value.message)
    }

    @Test
    fun publishingOfflineSendsNothingAndKeepsTheDraft() = runTest(dispatcher) {
        val vm = viewModel(online = MutableStateFlow(false))
        advanceUntilIdle()
        fillOneDish(vm)
        advanceUntilIdle()

        vm.publish()
        advanceUntilIdle()

        assertEquals(emptyList<MenuBody>(), server.published)
        assertEquals(PublishViewModel.PUBLISH_OFFLINE_MESSAGE, vm.state.value.message)
        assertNotNull(store.files[PublishViewModel.DRAFT_KEY])
    }

    @Test
    fun losingTheSignalWhilePublishingIsNotQueued() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        fillOneDish(vm)
        server.offline = true

        vm.publish()
        advanceUntilIdle()

        assertEquals(PublishViewModel.PUBLISH_OFFLINE_MESSAGE, vm.state.value.message)
        assertTrue(vm.state.value.offline)
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun closingOfflineIsBlocked() = runTest(dispatcher) {
        val vm = viewModel(online = MutableStateFlow(false))
        advanceUntilIdle()

        vm.close(vm.state.value.myMenus.single())
        advanceUntilIdle()

        assertEquals(emptyList<String>(), server.closed)
        assertEquals(PublishViewModel.CLOSE_OFFLINE_MESSAGE, vm.state.value.message)
    }

    @Test
    fun backOnlineReloadsAndPublishesAgain() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()
        server.offline = true
        val online = MutableStateFlow(false)
        val vm = viewModel(online)
        advanceUntilIdle()
        assertNotNull(vm.state.value.savedAt)

        server.offline = false
        online.value = true
        advanceUntilIdle()

        assertFalse(vm.state.value.offline)
        assertNull(vm.state.value.savedAt)
        fillOneDish(vm)
        vm.publish()
        advanceUntilIdle()
        assertEquals(1, server.published.size)
        assertNull(store.files[PublishViewModel.DRAFT_KEY]) // published: the draft is gone
    }

    private fun viewModel(online: Flow<Boolean> = emptyFlow()) =
        PublishViewModel(server, OfflineEstablishments(server), { now }, store, online)

    private fun fillOneDish(vm: PublishViewModel) {
        val key = vm.state.value.form.dishes.single().key
        vm.updateDish(key) { it.copy(name = "Bandeja paisa", priceText = "22000") }
    }

    private inner class OfflineEstablishments(private val menus: OfflineMenus) : EstablishmentRepository {
        override suspend fun mine(): List<OwnEstablishment> {
            if (menus.offline) throw ApiException.offline()
            return listOf(ajonjoli)
        }

        override suspend fun request(body: EstablishmentRequestBody): String = error("not used")
    }

    inner class OfflineMenus : MenuRepository {
        var offline = false
        val published = mutableListOf<MenuBody>()
        val closed = mutableListOf<String>()

        private fun check() {
            if (offline) throw ApiException.offline()
        }

        override suspend fun feed(filters: FeedFilters): FeedResponse = error("not used")

        override suspend fun menu(id: String, automatic: Boolean): MenuDetailResponse = error("not used")

        override suspend fun myMenus(): List<DailyMenu> {
            check()
            return listOf(menu())
        }

        override suspend fun publish(body: MenuBody): DailyMenu {
            check()
            published += body
            return menu()
        }

        override suspend fun revise(id: String, body: MenuBody): DailyMenu = error("not used")

        override suspend fun close(id: String, expectedVersion: Int?): CloseResponse {
            check()
            closed += id
            error("not reached in these tests")
        }

        private fun menu() = DailyMenu(
            id = "m1", title = "Almuerzo casero", validUntil = now + Duration.ofHours(3), publishedAt = now,
            establishmentId = "e1", establishmentName = "Ajonjolí", area = "Centro", lowestPriceCop = 22_000,
        )
    }
}
