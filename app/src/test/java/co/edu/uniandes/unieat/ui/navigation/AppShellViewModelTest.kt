package co.edu.uniandes.unieat.ui.navigation

import co.edu.uniandes.unieat.core.model.MeResponse
import co.edu.uniandes.unieat.data.auth.AuthRepository
import co.edu.uniandes.unieat.data.auth.Session
import co.edu.uniandes.unieat.data.auth.SessionManager
import co.edu.uniandes.unieat.data.auth.SessionPersistence
import co.edu.uniandes.unieat.data.repository.ProfileRepository
import co.edu.uniandes.unieat.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AppShellViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `profile error is visible and retry recovers role`() = runTest(main.dispatcher) {
        val store = ShellStore(Session("a", "r", Instant.parse("2026-10-02T13:00:00Z"), "u"))
        val sessions = SessionManager(ShellAuth(), store, { Instant.parse("2026-10-02T12:00:00Z") }, this)
        val repo = ShellProfiles(1)
        val vm = AppShellViewModel(sessions, repo, false)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.profileState.value is AppProfileState.Error)
        vm.retryProfile()
        testScheduler.advanceUntilIdle()
        val content = vm.profileState.value as AppProfileState.Content
        assertEquals("restaurant", content.profile.role)
    }

    @Test fun `token refresh for the same user does not reload the profile`() = runTest(main.dispatcher) {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val store = ShellStore(Session("old", "r", now.plusSeconds(30), "u"))
        val auth = object : AuthRepository {
            override suspend fun signIn(email: String, password: String) = error("unused")
            override suspend fun refresh(refreshToken: String) = Session("new", "r2", now.plusSeconds(3600), "u")
            override suspend fun signOut(accessToken: String) = Unit
        }
        val sessions = SessionManager(auth, store, { now }, this)
        val repo = ShellProfiles(0)
        val vm = AppShellViewModel(sessions, repo, false)
        testScheduler.advanceUntilIdle()
        assertEquals("new", sessions.accessToken()) // inside the refresh margin: refreshes
        testScheduler.advanceUntilIdle()
        assertTrue(vm.profileState.value is AppProfileState.Content)
        assertEquals(1, repo.calls)
    }

    @Test fun `demo profile remains available while signed out`() = runTest(main.dispatcher) {
        val sessions = SessionManager(ShellAuth(), ShellStore(null), { Instant.parse("2026-10-02T12:00:00Z") }, this)
        val vm = AppShellViewModel(sessions, ShellProfiles(0), true)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.profileState.value is AppProfileState.Content)
    }
}

private class ShellProfiles(private var failures: Int) : ProfileRepository {
    var calls = 0
    override suspend fun me(): MeResponse {
        calls++
        if (failures-- > 0) error("temporary")
        return MeResponse("u", "Samuel", "restaurant")
    }
}
private class ShellAuth : AuthRepository {
    override suspend fun signIn(email: String, password: String) = error("unused")
    override suspend fun refresh(refreshToken: String) = error("unused")
    override suspend fun signOut(accessToken: String) = Unit
}
private class ShellStore(private var value: Session?) : SessionPersistence {
    override suspend fun save(session: Session) { value = session }
    override suspend fun load() = value
    override suspend fun clear() { value = null }
}
