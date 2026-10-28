package co.edu.uniandes.unieat.ui.profile

import co.edu.uniandes.unieat.core.decision.Coordinate
import co.edu.uniandes.unieat.core.model.AdminUser
import co.edu.uniandes.unieat.core.model.EstablishmentMembership
import co.edu.uniandes.unieat.core.model.EstablishmentRequestBody
import co.edu.uniandes.unieat.core.model.MeResponse
import co.edu.uniandes.unieat.core.model.OwnEstablishment
import co.edu.uniandes.unieat.core.model.PendingMembership
import co.edu.uniandes.unieat.data.local.MemoryOfflineStore
import co.edu.uniandes.unieat.data.location.AddressGeocoder
import co.edu.uniandes.unieat.data.location.LocationRepository
import co.edu.uniandes.unieat.data.location.UserLocation
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.AdminRepository
import co.edu.uniandes.unieat.data.repository.EstablishmentRepository
import co.edu.uniandes.unieat.data.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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

/** Eventual connectivity of "Mis establecimientos" and the admin sections of Perfil. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileOfflineTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = MemoryOfflineStore()
    private val server = Server()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    // --- Mis establecimientos ---

    @Test
    fun offlineShowsTheSavedEstablishments() = runTest(dispatcher) {
        establishments()
        advanceUntilIdle()
        server.offline = true

        val vm = establishments()
        advanceUntilIdle()

        assertEquals("Ajonjolí", vm.state.value.memberships.single().establishmentName)
        assertNotNull(vm.state.value.savedAt)
        assertTrue(vm.state.value.offline)
    }

    @Test
    fun offlineWithoutACopySaysWhatWillHappen() = runTest(dispatcher) {
        server.offline = true
        val vm = establishments()
        advanceUntilIdle()

        assertEquals(EstablishmentsViewModel.NO_SAVED_DATA_MESSAGE, vm.state.value.message)
    }

    @Test
    fun anUnsentRequestComesBackWithTheFormOpen() = runTest(dispatcher) {
        val first = establishments()
        advanceUntilIdle()
        first.updateForm { it.copy(name = "Hua Wok", address = "Calle 19 #1-20") }
        first.movePin(Coordinate(4.6, -74.07))
        advanceUntilIdle()

        val reopened = establishments()
        advanceUntilIdle()

        val state = reopened.state.value
        assertTrue(state.formOpen)
        assertEquals("Hua Wok", state.form.name)
        assertEquals(Coordinate(4.6, -74.07), state.form.pin)
        assertEquals("Recuperamos tu solicitud sin enviar.", state.message)
    }

    @Test
    fun submittingOfflineSendsNothingAndKeepsTheDraft() = runTest(dispatcher) {
        val vm = establishments(online = MutableStateFlow(false))
        advanceUntilIdle()
        vm.updateForm { it.copy(name = "Hua Wok", address = "Calle 19 #1-20") }
        advanceUntilIdle()

        vm.submit()
        advanceUntilIdle()

        assertEquals(emptyList<EstablishmentRequestBody>(), server.requests)
        assertEquals(EstablishmentsViewModel.SUBMIT_OFFLINE_MESSAGE, vm.state.value.message)
        assertNotNull(store.files[EstablishmentsViewModel.DRAFT_KEY])
    }

    @Test
    fun offlineTheAddressSearchPointsToTheGps() = runTest(dispatcher) {
        val vm = establishments(online = MutableStateFlow(false))
        advanceUntilIdle()
        vm.setAddress("Calle 19 #1-20")

        vm.findAddress()
        advanceUntilIdle()

        assertEquals(EstablishmentsViewModel.GEOCODE_OFFLINE_MESSAGE, vm.state.value.pinMessage)
        assertNull(vm.state.value.form.pin)
    }

    @Test
    fun backOnlineTheRequestIsSentAndTheDraftDeleted() = runTest(dispatcher) {
        val online = MutableStateFlow(false)
        val vm = establishments(online)
        advanceUntilIdle()
        vm.updateForm { it.copy(name = "Hua Wok", address = "Calle 19 #1-20") }
        advanceUntilIdle()

        online.value = true
        advanceUntilIdle()
        vm.submit()
        advanceUntilIdle()

        assertEquals("Hua Wok", server.requests.single().name)
        assertFalse(vm.state.value.offline)
        assertNull(store.files[EstablishmentsViewModel.DRAFT_KEY])
    }

    // --- Admin ---

    @Test
    fun offlineTheAdminSeesTheSavedListsButCannotApprove() = runTest(dispatcher) {
        AdminViewModel(server, store)
        advanceUntilIdle()
        server.offline = true

        val vm = AdminViewModel(server, store, MutableStateFlow(false))
        advanceUntilIdle()
        vm.approve(vm.state.value.requests.single())
        vm.askRoleChange(vm.state.value.users.first())
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Hua Wok", state.requests.single().establishmentName)
        assertEquals(2, state.users.size)
        assertNotNull(state.requestsSavedAt)
        assertNotNull(state.usersSavedAt)
        assertEquals(emptyList<String>(), server.approved)
        assertEquals(AdminViewModel.APPROVE_OFFLINE_MESSAGE, state.requestsMessage)
        assertNull(state.confirming)
        assertEquals(AdminViewModel.ROLE_OFFLINE_MESSAGE, state.usersMessage)
    }

    @Test
    fun backOnlineTheAdminListsReload() = runTest(dispatcher) {
        AdminViewModel(server, store)
        advanceUntilIdle()
        server.offline = true
        val online = MutableStateFlow(false)
        val vm = AdminViewModel(server, store, online)
        advanceUntilIdle()

        server.offline = false
        server.pending += PendingMembership("e2", "Doña Blanca", "Centro", "u3", "Otra dueña")
        online.value = true
        advanceUntilIdle()

        assertEquals(2, vm.state.value.requests.size)
        assertNull(vm.state.value.requestsSavedAt)
        assertFalse(vm.state.value.offline)
    }

    private fun establishments(online: Flow<Boolean> = emptyFlow()) = EstablishmentsViewModel(
        server, server, NoGps(), NoGeocoder(), store = store, online = online,
    )

    /** One fake server for the three repositories; [offline] makes every call fail like a phone without signal. */
    private class Server : ProfileRepository, EstablishmentRepository, AdminRepository {
        var offline = false
        val requests = mutableListOf<EstablishmentRequestBody>()
        val approved = mutableListOf<String>()
        val pending = mutableListOf(PendingMembership("e1", "Hua Wok", "Centro", "u2", "Dueño"))

        private fun check() {
            if (offline) throw ApiException.offline()
        }

        override suspend fun me(): MeResponse {
            check()
            return MeResponse(
                "u1", "Dueña", "restaurant",
                listOf(EstablishmentMembership("e1", "Ajonjolí", "Centro", "owner", approved = true)),
            )
        }

        override suspend fun mine(): List<OwnEstablishment> = error("not used")

        override suspend fun request(body: EstablishmentRequestBody): String {
            check()
            requests += body
            return "Solicitud registrada."
        }

        override suspend fun pendingMemberships(): List<PendingMembership> {
            check()
            return pending.toList()
        }

        override suspend fun approve(membership: PendingMembership) {
            check()
            approved += membership.key
        }

        override suspend fun users(): List<AdminUser> {
            check()
            return listOf(AdminUser("u1", "Yo", "admin"), AdminUser("u2", "Dueño", "student"))
        }

        override suspend fun setAdmin(userId: String, admin: Boolean): AdminUser = error("not used")
    }

    private class NoGps : LocationRepository {
        override fun locationEnabled(): Flow<Boolean> = flowOf(false)

        override fun locationUpdates(precise: Boolean): Flow<UserLocation> = emptyFlow()
    }

    private class NoGeocoder : AddressGeocoder {
        override suspend fun find(address: String): Coordinate? = null
    }
}
