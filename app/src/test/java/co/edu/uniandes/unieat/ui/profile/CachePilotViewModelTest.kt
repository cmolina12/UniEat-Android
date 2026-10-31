package co.edu.uniandes.unieat.ui.profile

import co.edu.uniandes.unieat.data.pilot.PilotMembership
import co.edu.uniandes.unieat.data.pilot.PilotStatus
import co.edu.uniandes.unieat.data.pilot.PilotVariant
import co.edu.uniandes.unieat.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Samuel — the BQ-13 consent switch in Perfil. */
@OptIn(ExperimentalCoroutinesApi::class)
class CachePilotViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeMembership(var signedIn: Boolean = true) : PilotMembership {
        var joined = false
        var joins = 0
        override suspend fun status() =
            PilotStatus(signedIn, joined, if (joined) PilotVariant.ONLINE_ONLY else null)
        override suspend fun join(): Boolean {
            joins++
            joined = signedIn
            return signedIn
        }
        override suspend fun leave() {
            joined = false
        }
    }

    @Test fun `joining shows the assigned group and leaving hides it`() = runTest(main.dispatcher) {
        val membership = FakeMembership()
        val vm = CachePilotViewModel(membership)
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.status.joined)

        vm.setJoined(true)
        testScheduler.advanceUntilIdle()
        assertEquals(PilotVariant.ONLINE_ONLY, vm.state.value.status.variant)

        vm.setJoined(false)
        testScheduler.advanceUntilIdle()
        assertEquals(null, vm.state.value.status.variant)
        assertFalse(vm.state.value.working)
    }

    @Test fun `a double tap joins once`() = runTest(main.dispatcher) {
        val membership = FakeMembership()
        val vm = CachePilotViewModel(membership)
        testScheduler.advanceUntilIdle()

        vm.setJoined(true)
        vm.setJoined(true)
        testScheduler.advanceUntilIdle()

        assertEquals(1, membership.joins)
    }

    @Test fun `signed out the switch cannot join`() = runTest(main.dispatcher) {
        val vm = CachePilotViewModel(FakeMembership(signedIn = false))
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.status.signedIn)
    }
}
