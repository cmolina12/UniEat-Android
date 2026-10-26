package co.edu.uniandes.unieat.ui.publish

import co.edu.uniandes.unieat.core.model.CloseResponse
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.core.model.MenuDish
import co.edu.uniandes.unieat.core.model.MenuStatus
import co.edu.uniandes.unieat.core.model.OwnEstablishment
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.EstablishmentRepository
import co.edu.uniandes.unieat.data.repository.MenuRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PublishViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-10-26T15:00:00Z")

    private val pending = OwnEstablishment("p", "Pendiente", "Norte", approved = false)
    private val ajonjoli = OwnEstablishment(
        "e1", "Ajonjolí", "Centro", "Carrera 1 #20-41", "Pasillo de baldosas", listOf("Efectivo"), approved = true,
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadFillsTheFormFromTheFirstApprovedEstablishment() = runTest(dispatcher) {
        val vm = viewModel(StubMenus(), listOf(pending, ajonjoli))
        advanceUntilIdle()

        val form = vm.state.value.form
        assertEquals("e1", form.establishmentId)
        assertEquals("Ajonjolí", form.establishmentName)
        assertEquals("Carrera 1 #20-41", form.address)
        assertEquals(setOf("Efectivo"), form.paymentMethods)
        assertEquals(listOf(ajonjoli), vm.state.value.approved)
    }

    @Test
    fun withoutApprovedEstablishmentsMyMenusForbiddenIsJustAnEmptyList() = runTest(dispatcher) {
        val menus = StubMenus(mine = { throw ApiException(ApiError(ApiException.FORBIDDEN, "No"), 403) })
        val vm = viewModel(menus, listOf(pending))
        advanceUntilIdle()

        assertEquals(emptyList<DailyMenu>(), vm.state.value.myMenus)
        assertNull(vm.state.value.form.establishmentId)
        assertNull(vm.state.value.message)
    }

    @Test
    fun dietIsOnlySentWhenConfirmedAndVeganAlsoCountsAsVegetarian() {
        assertEquals(emptyList<String>(), DishDraft(0, vegan = true, dietaryKnown = false).toBody().dietaryTags)
        assertEquals(listOf("vegetarian", "vegan"), DishDraft(0, vegan = true, dietaryKnown = true).toBody().dietaryTags)
        assertEquals(listOf("vegetarian"), DishDraft(0, vegetarian = true, dietaryKnown = true).toBody().dietaryTags)
        assertEquals(emptyList<String>(), DishDraft(0, dietaryKnown = true).toBody().dietaryTags)
    }

    @Test
    fun validityMustBeInTheFutureAndAtMost24Hours() = runTest(dispatcher) {
        val vm = viewModel(StubMenus(), listOf(ajonjoli))
        advanceUntilIdle()
        fillOneDish(vm)

        vm.updateForm { it.copy(validUntil = now + Duration.ofHours(25)) }
        assertFalse(vm.state.value.form.isValid(now))
        vm.updateForm { it.copy(validUntil = now - Duration.ofMinutes(1)) }
        assertFalse(vm.state.value.form.isValid(now))
        vm.updateForm { it.copy(validUntil = now + Duration.ofHours(24)) }
        assertTrue(vm.state.value.form.isValid(now))
    }

    @Test
    fun publishSendsTheFormAndStartsANewEmptyDish() = runTest(dispatcher) {
        val menus = StubMenus()
        val vm = viewModel(menus, listOf(ajonjoli))
        advanceUntilIdle()
        fillOneDish(vm)
        vm.togglePayment("Nequi")

        vm.publish()
        advanceUntilIdle()

        val body = menus.published.single()
        assertEquals("e1", body.establishmentId)
        assertEquals("Almuerzo completo", body.title)
        // Catalog order, not the order they were tapped.
        assertEquals(listOf("Nequi", "Efectivo"), body.paymentMethods)
        assertEquals(listOf(MenuBody.Dish("Bandeja paisa", priceCop = 22_000)), body.dishes)
        assertNull(body.expectedVersion)
        assertEquals(1, vm.state.value.form.dishes.size)
        assertEquals("", vm.state.value.form.dishes.single().name)
        assertEquals("Menú publicado. Ya es visible para los estudiantes.", vm.state.value.message)
    }

    @Test
    fun editingSavesANewVersionOfTheSameMenu() = runTest(dispatcher) {
        val menus = StubMenus()
        val vm = viewModel(menus, listOf(ajonjoli))
        advanceUntilIdle()

        vm.edit(menu(version = 3))
        assertEquals("Bandeja paisa", vm.state.value.form.dishes.single().name)
        vm.publish()
        advanceUntilIdle()

        assertEquals("m1", menus.revised.single().first)
        assertEquals(3, menus.revised.single().second.expectedVersion)
        assertTrue(menus.published.isEmpty())
        assertNull(vm.state.value.editing)
    }

    @Test
    fun aVersionConflictDropsTheEditAndAsksToReapplyChanges() = runTest(dispatcher) {
        val menus = StubMenus(revise = { throw ApiException(ApiError(ApiException.VERSION_CONFLICT, "Conflicto"), 409) })
        val vm = viewModel(menus, listOf(ajonjoli))
        advanceUntilIdle()

        vm.edit(menu(version = 3))
        vm.publish()
        advanceUntilIdle()

        assertNull(vm.state.value.editing)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.message!!.startsWith("Otra persona editó este menú."))
    }

    @Test
    fun closingAMenuReloadsTheOwnerList() = runTest(dispatcher) {
        val menus = StubMenus()
        val vm = viewModel(menus, listOf(ajonjoli))
        advanceUntilIdle()

        vm.close(menu())
        advanceUntilIdle()

        assertEquals(listOf("m1"), menus.closed)
        assertEquals("Publicación cerrada. El historial se conserva.", vm.state.value.message)
    }

    private fun viewModel(menus: StubMenus, establishments: List<OwnEstablishment>) =
        PublishViewModel(menus, StubEstablishments(establishments)) { now }

    private fun fillOneDish(vm: PublishViewModel) {
        val key = vm.state.value.form.dishes.single().key
        vm.updateDish(key) { it.copy(name = "Bandeja paisa", priceText = "22000") }
    }

    private fun menu(version: Int = 1) = DailyMenu(
        id = "m1",
        title = "Almuerzo casero",
        version = version,
        validUntil = now + Duration.ofHours(3),
        publishedAt = now - Duration.ofHours(1),
        establishmentId = "e1",
        establishmentName = "Ajonjolí",
        area = "Centro",
        paymentMethods = listOf("Efectivo"),
        items = listOf(MenuDish("d1", "Bandeja paisa", priceCop = 22_000)),
        lowestPriceCop = 22_000,
    )
}

private class StubEstablishments(private val establishments: List<OwnEstablishment>) : EstablishmentRepository {
    override suspend fun mine(): List<OwnEstablishment> = establishments
}

private class StubMenus(
    private val mine: suspend () -> List<DailyMenu> = { emptyList() },
    private val revise: suspend (MenuBody) -> Unit = {},
) : MenuRepository {
    val published = mutableListOf<MenuBody>()
    val revised = mutableListOf<Pair<String, MenuBody>>()
    val closed = mutableListOf<String>()

    override suspend fun feed(filters: FeedFilters): FeedResponse = error("not used")

    override suspend fun menu(id: String, automatic: Boolean): MenuDetailResponse = error("not used")

    override suspend fun myMenus(): List<DailyMenu> = mine()

    override suspend fun publish(body: MenuBody): DailyMenu {
        published += body
        return placeholder()
    }

    override suspend fun revise(id: String, body: MenuBody): DailyMenu {
        revise(body)
        revised += id to body
        return placeholder()
    }

    override suspend fun close(id: String, expectedVersion: Int?): CloseResponse {
        closed += id
        return CloseResponse(id, 1, MenuStatus.CLOSED, Instant.EPOCH)
    }

    private fun placeholder() = DailyMenu(
        id = "new", title = "t", validUntil = Instant.EPOCH, publishedAt = Instant.EPOCH,
        establishmentId = "e1", establishmentName = "n", area = "Centro", lowestPriceCop = 1,
    )
}
