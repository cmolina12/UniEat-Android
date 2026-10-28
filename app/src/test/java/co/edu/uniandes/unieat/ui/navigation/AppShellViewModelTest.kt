package co.edu.uniandes.unieat.ui.navigation

import co.edu.uniandes.unieat.core.model.MeResponse
import co.edu.uniandes.unieat.data.auth.AuthRepository
import co.edu.uniandes.unieat.data.auth.Session
import co.edu.uniandes.unieat.data.auth.SessionManager
import co.edu.uniandes.unieat.data.auth.SessionPersistence
import co.edu.uniandes.unieat.data.local.ProfileCache
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.ProfileRepository
import co.edu.uniandes.unieat.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            override suspend fun signUp(email: String, password: String, displayName: String): Session? = error("unused")
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

    @Test fun `going back online retries a failed profile`() = runTest(main.dispatcher) {
        val store = ShellStore(Session("a", "r", Instant.parse("2026-10-02T13:00:00Z"), "u"))
        val sessions = SessionManager(ShellAuth(), store, { Instant.parse("2026-10-02T12:00:00Z") }, this)
        val online = MutableStateFlow(false)
        val vm = AppShellViewModel(sessions, ShellProfiles(1), false, online)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.profileState.value is AppProfileState.Error)

        online.value = true
        testScheduler.advanceUntilIdle()

        assertEquals("restaurant", (vm.profileState.value as AppProfileState.Content).profile.role)
    }

    @Test fun `demo profile remains available while signed out`() = runTest(main.dispatcher) {
        val sessions = SessionManager(ShellAuth(), ShellStore(null), { Instant.parse("2026-10-02T12:00:00Z") }, this)
        val vm = AppShellViewModel(sessions, ShellProfiles(0), true)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.profileState.value is AppProfileState.Content)
    }
    private fun signedIn(scope: kotlinx.coroutines.CoroutineScope, userId: String = "u") = SessionManager(
        ShellAuth(),
        ShellStore(Session("a", "r", Instant.parse("2026-10-02T13:00:00Z"), userId)),
        { Instant.parse("2026-10-02T12:00:00Z") },
        scope,
    )

    @Test fun `a loaded profile is saved for the next offline launch`() = runTest(main.dispatcher) {
        val cache = MemoryProfileCache()
        AppShellViewModel(signedIn(this), ShellProfiles(0), false, cache = cache)
        testScheduler.advanceUntilIdle()
        assertEquals("restaurant", cache.load("u")?.role)
    }

    @Test fun `opening offline uses the saved role so the tabs stay`() = runTest(main.dispatcher) {
        val cache = MemoryProfileCache().apply { save("u", MeResponse("u", "Admin", "admin")) }
        val vm = AppShellViewModel(signedIn(this), OfflineProfiles(), false, cache = cache)
        testScheduler.advanceUntilIdle()
        val content = vm.profileState.value as AppProfileState.Content
        assertEquals("admin", content.profile.role)
        assertTrue(content.saved)
    }

    @Test fun `opening offline with nothing saved still shows the error`() = runTest(main.dispatcher) {
        val vm = AppShellViewModel(signedIn(this), OfflineProfiles(), false, cache = MemoryProfileCache())
        testScheduler.advanceUntilIdle()
        assertTrue(vm.profileState.value is AppProfileState.Error)
    }

    @Test fun `another account's saved profile is never used`() = runTest(main.dispatcher) {
        val cache = MemoryProfileCache().apply { save("someone-else", MeResponse("someone-else", "Admin", "admin")) }
        val vm = AppShellViewModel(signedIn(this), OfflineProfiles(), false, cache = cache)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.profileState.value is AppProfileState.Error)
    }

    @Test fun `errors that are not about the connection do not hide behind the saved profile`() = runTest(main.dispatcher) {
        val cache = MemoryProfileCache().apply { save("u", MeResponse("u", "Admin", "admin")) }
        val vm = AppShellViewModel(signedIn(this), ShellProfiles(1), false, cache = cache)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.profileState.value is AppProfileState.Error)
    }

    @Test fun `back online the saved profile is replaced by the server's`() = runTest(main.dispatcher) {
        val cache = MemoryProfileCache().apply { save("u", MeResponse("u", "Samuel", "student")) }
        val online = MutableStateFlow(false)
        val profiles = OfflineProfiles()
        val vm = AppShellViewModel(signedIn(this), profiles, false, online, cache)
        testScheduler.advanceUntilIdle()
        assertTrue((vm.profileState.value as AppProfileState.Content).saved)

        profiles.offline = false
        online.value = true
        testScheduler.advanceUntilIdle()

        val content = vm.profileState.value as AppProfileState.Content
        assertFalse(content.saved)
        assertEquals("restaurant", content.profile.role) // the role changed on the server meanwhile
        assertEquals("restaurant", cache.load("u")?.role)
    }
}

private class OfflineProfiles : ProfileRepository {
    var offline = true
    override suspend fun me(): MeResponse {
        if (offline) throw ApiException.offline()
        return MeResponse("u", "Samuel", "restaurant")
    }
}
private class MemoryProfileCache : ProfileCache {
    private val saved = mutableMapOf<String, MeResponse>()
    override suspend fun save(userId: String, profile: MeResponse) { saved.clear(); saved[userId] = profile }
    override suspend fun load(userId: String) = saved[userId]
    override suspend fun clear() = saved.clear()
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
    override suspend fun signUp(email: String, password: String, displayName: String): Session? = error("unused")
    override suspend fun signOut(accessToken: String) = Unit
}
private class ShellStore(private var value: Session?) : SessionPersistence {
    override suspend fun save(session: Session) { value = session }
    override suspend fun load() = value
    override suspend fun clear() { value = null }
}
