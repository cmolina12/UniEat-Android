package co.edu.uniandes.unieat.ui.profile

import co.edu.uniandes.unieat.core.model.AdminUser
import co.edu.uniandes.unieat.core.model.PendingMembership
import co.edu.uniandes.unieat.data.repository.AdminRepository
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
class AdminViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun approvingARequestRemovesItAndTellsTheOwnerToRefresh() = runTest(dispatcher) {
        val admin = StubAdmin()
        val vm = AdminViewModel(admin)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.requests.size)

        vm.approve(admin.pending.single())
        advanceUntilIdle()

        assertEquals(listOf("e1:u2"), admin.approved)
        assertEquals(emptyList<PendingMembership>(), vm.state.value.requests)
        assertEquals("Hua Wok aprobado. El dueño debe actualizar su estado en la app.", vm.state.value.requestsMessage)
        assertNull(vm.state.value.workingId)
    }

    @Test
    fun roleChangesWaitForConfirmationAndToggleAdmin() = runTest(dispatcher) {
        val admin = StubAdmin()
        val vm = AdminViewModel(admin)
        advanceUntilIdle()
        val student = vm.state.value.users.first { it.role == "student" }
        val otherAdmin = vm.state.value.users.first { it.id == "u3" }

        vm.askRoleChange(student)
        assertEquals(student, vm.state.value.confirming)
        vm.cancelRoleChange()
        advanceUntilIdle()
        assertEquals(emptyList<Pair<String, Boolean>>(), admin.roleChanges)

        vm.askRoleChange(student)
        vm.confirmRoleChange()
        vm.askRoleChange(otherAdmin)
        advanceUntilIdle()
        vm.confirmRoleChange()
        advanceUntilIdle()

        assertEquals(listOf("u2" to true, "u3" to false), admin.roleChanges)
        assertEquals("Rol actualizado. El cambio se aplicará al actualizar la cuenta del usuario.", vm.state.value.usersMessage)
    }
}

private class StubAdmin : AdminRepository {
    val pending = mutableListOf(PendingMembership("e1", "Hua Wok", "Centro", "u2", "Dueño"))
    val approved = mutableListOf<String>()
    val roleChanges = mutableListOf<Pair<String, Boolean>>()

    override suspend fun pendingMemberships(): List<PendingMembership> = pending.toList()

    override suspend fun approve(membership: PendingMembership) {
        approved += membership.key
        pending.removeAll { it.key == membership.key }
    }

    override suspend fun users(): List<AdminUser> = listOf(
        AdminUser("u1", "Yo", "admin"),
        AdminUser("u2", "Dueño", "student"),
        AdminUser("u3", "Otra admin", "admin"),
    )

    override suspend fun setAdmin(userId: String, admin: Boolean): AdminUser {
        roleChanges += userId to admin
        return AdminUser(userId, "", if (admin) "admin" else "student")
    }
}
