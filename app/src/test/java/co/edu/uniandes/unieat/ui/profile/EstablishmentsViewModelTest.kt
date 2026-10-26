package co.edu.uniandes.unieat.ui.profile

import co.edu.uniandes.unieat.core.decision.Coordinate
import co.edu.uniandes.unieat.core.model.EstablishmentMembership
import co.edu.uniandes.unieat.core.model.EstablishmentRequestBody
import co.edu.uniandes.unieat.core.model.MeResponse
import co.edu.uniandes.unieat.core.model.OwnEstablishment
import co.edu.uniandes.unieat.data.location.AddressGeocoder
import co.edu.uniandes.unieat.data.location.LocationRepository
import co.edu.uniandes.unieat.data.location.UserLocation
import co.edu.uniandes.unieat.data.repository.EstablishmentRepository
import co.edu.uniandes.unieat.data.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class EstablishmentsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fromAddress = Coordinate(4.6035, -74.0661)
    private val fromGps = Coordinate(4.6040, -74.0656)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsTheMembershipsFromTheAccount() = runTest(dispatcher) {
        val profiles = StubProfiles(me(role = "student", approved = false))
        val vm = viewModel(profiles)
        advanceUntilIdle()

        assertEquals("Ajonjolí", vm.state.value.memberships.single().establishmentName)
        assertEquals("student", vm.state.value.role)
    }

    @Test
    fun refreshingAfterAnApprovalSaysTheAccountCanPublishAndRefreshesTheApp() = runTest(dispatcher) {
        val profiles = StubProfiles(me(role = "student", approved = false))
        var accountChanges = 0
        val vm = viewModel(profiles, onAccountChanged = { accountChanges++ })
        advanceUntilIdle()

        profiles.current = me(role = "restaurant", approved = true)
        vm.refreshStatus()
        advanceUntilIdle()

        assertEquals("Tu cuenta ya puede publicar menús.", vm.state.value.message)
        assertTrue(vm.state.value.memberships.single().approved)
        assertEquals(1, accountChanges)
    }

    @Test
    fun withoutAPinTheRequestUsesTheAddressAndThenClearsTheForm() = runTest(dispatcher) {
        val requests = StubEstablishments()
        val vm = viewModel(establishments = requests)
        advanceUntilIdle()
        vm.updateForm { it.copy(name = " Ajonjolí ", photoUrl = "https://example.com/a.jpg") }
        vm.setAddress("Carrera 1 #20-41")
        vm.togglePayment("Nequi")

        vm.submit()
        advanceUntilIdle()

        val body = requests.sent.single()
        assertEquals("Ajonjolí", body.name)
        assertEquals(listOf("Nequi", "Efectivo"), body.paymentMethods)
        assertEquals(fromAddress.latitude, body.latitude!!, 0.0)
        assertEquals("https://example.com/a.jpg", body.photoUrl)
        assertEquals("", vm.state.value.form.name)
        assertFalse(vm.state.value.formOpen)
        assertEquals("Solicitud enviada. Podrás publicar cuando un administrador la apruebe.", vm.state.value.message)
    }

    @Test
    fun changingTheAddressDropsAnAddressPinButKeepsAGpsPin() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setAddress("Carrera 1 #20-41")
        vm.findAddress()
        advanceUntilIdle()
        assertEquals(fromAddress, vm.state.value.form.pin)
        vm.setAddress("Carrera 1 #20-45")
        assertNull(vm.state.value.form.pin)

        vm.useMyLocation()
        advanceUntilIdle()
        assertEquals(fromGps, vm.state.value.form.pin)
        assertEquals("Pin tomado de tu ubicación actual (±12 m). Úsalo estando en el local.", vm.state.value.pinMessage)
        vm.setAddress("Otra dirección")
        assertEquals(fromGps, vm.state.value.form.pin)
    }

    @Test
    fun photoMustBeAnHttpsLinkWithoutCredentials() {
        assertTrue(isValidPhotoUrl(""))
        assertTrue(isValidPhotoUrl("https://driicwyujkdhnzmmuaye.supabase.co/storage/v1/object/public/establecimientos/ajonjoli.jpg"))
        assertFalse(isValidPhotoUrl("http://example.com/a.jpg"))
        assertFalse(isValidPhotoUrl("https://user:pass@example.com/a.jpg"))
        assertFalse(isValidPhotoUrl("no es un enlace"))
    }

    private fun viewModel(
        profiles: StubProfiles = StubProfiles(me(role = "student", approved = false)),
        establishments: StubEstablishments = StubEstablishments(),
        onAccountChanged: () -> Unit = {},
    ) = EstablishmentsViewModel(
        profiles,
        establishments,
        StubLocation(UserLocation(fromGps, 12f)),
        StubGeocoder(fromAddress),
        onAccountChanged,
    )

    private fun me(role: String, approved: Boolean) = MeResponse(
        id = "u1",
        displayName = "Dueña",
        role = role,
        establishments = listOf(EstablishmentMembership("e1", "Ajonjolí", "Centro", "owner", approved)),
    )
}

private class StubProfiles(var current: MeResponse) : ProfileRepository {
    override suspend fun me(): MeResponse = current
}

private class StubEstablishments : EstablishmentRepository {
    val sent = mutableListOf<EstablishmentRequestBody>()

    override suspend fun mine(): List<OwnEstablishment> = emptyList()

    override suspend fun request(body: EstablishmentRequestBody): String {
        sent += body
        return "Solicitud registrada."
    }
}

private class StubLocation(private val here: UserLocation) : LocationRepository {
    override fun locationEnabled(): Flow<Boolean> = flowOf(true)

    override fun locationUpdates(precise: Boolean): Flow<UserLocation> = flowOf(here)
}

private class StubGeocoder(private val found: Coordinate?) : AddressGeocoder {
    override suspend fun find(address: String): Coordinate? = found
}
