package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.core.decision.Coordinate
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.data.location.UserLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Context-aware behaviour of [MenuDetailViewModel.distance]. */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuDetailDistanceTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-09-29T17:00:00Z")
    private val pin = Coordinate(4.6036, -74.0640)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(location: StubLocation, menu: DailyMenu = menu()): MenuDetailViewModel {
        val vm = MenuDetailViewModel(
            "m",
            MenuDetailViewModelTest.StubRepository { MenuDetailResponse(now, now, menu) },
            location,
        ) { now }
        backgroundScope.launch { vm.distance.collect {} } // like the screen collecting it
        advanceUntilIdle()
        return vm
    }

    @Test
    fun idleUntilThePermissionIsChecked() = runTest(dispatcher) {
        val vm = viewModel(StubLocation())
        assertEquals(DistanceStatus.Idle, vm.distance.value)
    }

    @Test
    fun withoutPermissionExplainsBeforeAsking() = runTest(dispatcher) {
        val location = StubLocation()
        val vm = viewModel(location)

        vm.onLocationPermissionChecked(LocationPermission.NONE)
        advanceUntilIdle()

        assertEquals(DistanceStatus.PermissionNeeded, vm.distance.value)
        assertEquals("the GPS must not start without permission", 0, location.collectors)
    }

    @Test
    fun notNowAndDenialsAreRemembered() = runTest(dispatcher) {
        val vm = viewModel(StubLocation())

        vm.onLocationPermissionChecked(LocationPermission.NONE)
        vm.onLocationPromptDismissed()
        advanceUntilIdle()
        assertEquals(DistanceStatus.PermissionDenied(permanently = false), vm.distance.value)

        // Coming back to the screen keeps the "no" instead of nagging again.
        vm.onLocationPermissionChecked(LocationPermission.NONE)
        advanceUntilIdle()
        assertEquals(DistanceStatus.PermissionDenied(permanently = false), vm.distance.value)

        vm.onLocationPermissionResult(LocationPermission.NONE, canAskAgain = false)
        advanceUntilIdle()
        assertEquals(DistanceStatus.PermissionDenied(permanently = true), vm.distance.value)
    }

    @Test
    fun gpsSwitchIsFollowedLive() = runTest(dispatcher) {
        val location = StubLocation(enabled = false)
        val vm = viewModel(location)

        vm.onLocationPermissionChecked(LocationPermission.PRECISE)
        advanceUntilIdle()
        assertEquals(DistanceStatus.LocationOff, vm.distance.value)

        // Quick-settings tile: no onResume, the flow alone must react.
        location.enabled.value = true
        advanceUntilIdle()
        assertEquals(DistanceStatus.Searching, vm.distance.value)

        location.fixes.emit(UserLocation(pin, 5f))
        advanceUntilIdle()
        assertTrue(vm.distance.value is DistanceStatus.Known)

        location.enabled.value = false
        advanceUntilIdle()
        assertEquals("a stale distance must not stay on screen", DistanceStatus.LocationOff, vm.distance.value)
    }

    @Test
    fun preciseFixGivesDistanceAndArrival() = runTest(dispatcher) {
        val location = StubLocation()
        val vm = viewModel(location)
        vm.onLocationPermissionChecked(LocationPermission.PRECISE)
        advanceUntilIdle()
        assertEquals(DistanceStatus.Searching, vm.distance.value)
        assertEquals(true, location.lastPrecise)

        location.fixes.emit(UserLocation(Coordinate(4.6028, -74.0652), 8f)) // ~160 m
        advanceUntilIdle()
        val far = vm.distance.value as DistanceStatus.Known
        assertEquals(160.0, far.proximity.distanceMeters, 1.0)
        assertFalse(far.proximity.arrived)
        assertFalse(far.approximate)

        location.fixes.emit(UserLocation(Coordinate(4.6038, -74.0640), 8f)) // ~22 m
        advanceUntilIdle()
        assertTrue((vm.distance.value as DistanceStatus.Known).proximity.arrived)
    }

    @Test
    fun approximatePermissionShowsDistanceButNeverArrival() = runTest(dispatcher) {
        val location = StubLocation()
        val vm = viewModel(location)
        vm.onLocationPermissionChecked(LocationPermission.APPROXIMATE)
        location.fixes.emit(UserLocation(pin, 1_200f))
        advanceUntilIdle()

        val known = vm.distance.value as DistanceStatus.Known
        assertTrue(known.approximate)
        assertFalse(known.proximity.arrived)
        assertEquals(false, location.lastPrecise)
    }

    @Test
    fun sensorFailureBecomesUnavailable() = runTest(dispatcher) {
        val location = object : co.edu.uniandes.unieat.data.location.LocationRepository {
            override fun locationEnabled() = kotlinx.coroutines.flow.flowOf(true)
            override fun locationUpdates(precise: Boolean) =
                kotlinx.coroutines.flow.flow<UserLocation> { throw SecurityException("revoked") }
        }
        val vm = MenuDetailViewModel("m", MenuDetailViewModelTest.StubRepository { MenuDetailResponse(now, now, menu()) }, location) { now }
        backgroundScope.launch { vm.distance.collect {} }
        vm.onLocationPermissionChecked(LocationPermission.PRECISE)
        advanceUntilIdle()

        assertEquals(DistanceStatus.Unavailable, vm.distance.value)
    }

    @Test
    fun restaurantWithoutPinNeverStartsTheGps() = runTest(dispatcher) {
        val location = StubLocation()
        val vm = viewModel(location, menu(latitude = null, longitude = null))
        vm.onLocationPermissionChecked(LocationPermission.PRECISE)
        advanceUntilIdle()

        assertEquals(DistanceStatus.NoRestaurantPin, vm.distance.value)
        assertEquals(0, location.collectors)
    }

    @Test
    fun arrivalAnswerIsKept() = runTest(dispatcher) {
        val vm = viewModel(StubLocation())
        vm.onArrivalAnswered(true)
        assertEquals(ArrivalAnswer.CONFIRMED, vm.arrival.value)
    }

    private fun menu(latitude: Double? = pin.latitude, longitude: Double? = pin.longitude) = DailyMenu(
        id = "m", title = "Tazón", validUntil = now + Duration.ofHours(1), publishedAt = now,
        establishmentId = "e", establishmentName = "Bowls", area = "Centro", lowestPriceCop = 12_000,
        latitude = latitude, longitude = longitude,
    )
}
