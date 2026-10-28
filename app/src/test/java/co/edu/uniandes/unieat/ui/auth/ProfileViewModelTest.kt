package co.edu.uniandes.unieat.ui.auth

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
class ProfileViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `loads profile content`() = runTest(main.dispatcher) {
        val repo = RetryProfileRepository(failures = 0)
        val vm = ProfileViewModel(repo, sessionManager(this))
        testScheduler.advanceUntilIdle()
        val content = vm.state.value as ProfileState.Content
        assertEquals("Samuel", content.me.displayName)
    }

    @Test fun `shows error and retry can recover`() = runTest(main.dispatcher) {
        val repo = RetryProfileRepository(failures = 1)
        val vm = ProfileViewModel(repo, sessionManager(this))
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ProfileState.Error)
        vm.load()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ProfileState.Content)
        assertEquals(2, repo.calls)
    }

    @Test fun `offline the saved profile opens Perfil and its role sections`() = runTest(main.dispatcher) {
        val cache = SavedProfiles("u" to MeResponse("u", "Admin", "admin"))
        val vm = ProfileViewModel(OfflineProfiles(), signedIn(this, "u"), cache)
        testScheduler.advanceUntilIdle()

        val content = vm.state.value as ProfileState.Content
        assertEquals("admin", content.me.role)
        assertTrue(content.saved)
    }

    @Test fun `offline another account's saved profile is not used`() = runTest(main.dispatcher) {
        val cache = SavedProfiles("someone-else" to MeResponse("someone-else", "Admin", "admin"))
        val vm = ProfileViewModel(OfflineProfiles(), signedIn(this, "u"), cache)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value is ProfileState.Error)
    }

    @Test fun `back online the saved profile is replaced by the server's`() = runTest(main.dispatcher) {
        val cache = SavedProfiles("u" to MeResponse("u", "Admin", "admin"))
        val repo = OfflineProfiles()
        val online = MutableStateFlow(false)
        val vm = ProfileViewModel(repo, signedIn(this, "u"), cache, online)
        testScheduler.advanceUntilIdle()

        repo.offline = false
        online.value = true
        testScheduler.advanceUntilIdle()

        val content = vm.state.value as ProfileState.Content
        assertFalse(content.saved)
        assertEquals("Samuel", content.me.displayName)
    }

    private fun signedIn(scope: kotlinx.coroutines.CoroutineScope, userId: String) = SessionManager(
        object : AuthRepository {
            override suspend fun signIn(email: String, password: String) = error("unused")
            override suspend fun refresh(refreshToken: String) = error("unused")
            override suspend fun signUp(email: String, password: String, displayName: String): Session? = error("unused")
            override suspend fun signOut(accessToken: String) = Unit
        },
        object : SessionPersistence {
            override suspend fun save(session: Session) = Unit
            override suspend fun load() = Session("a", "r", Instant.parse("2026-10-02T13:00:00Z"), userId)
            override suspend fun clear() = Unit
        },
        { Instant.parse("2026-10-02T12:00:00Z") },
        scope,
    )
}

private class OfflineProfiles : ProfileRepository {
    var offline = true
    override suspend fun me(): MeResponse {
        if (offline) throw ApiException.offline()
        return MeResponse("u", "Samuel", "admin")
    }
}

private class SavedProfiles(private val saved: Pair<String, MeResponse>) : ProfileCache {
    override suspend fun save(userId: String, profile: MeResponse) = Unit
    override suspend fun load(userId: String) = saved.second.takeIf { saved.first == userId }
    override suspend fun clear() = Unit
}

internal class RetryProfileRepository(private var failures: Int) : ProfileRepository {
    var calls = 0
    override suspend fun me(): MeResponse {
        calls++
        if (failures-- > 0) error("temporary")
        return MeResponse("u", "Samuel", "restaurant")
    }
}

internal fun sessionManager(scope: kotlinx.coroutines.CoroutineScope): SessionManager = SessionManager(
    object : AuthRepository {
        override suspend fun signIn(email: String, password: String) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun signUp(email: String, password: String, displayName: String): Session? = error("unused")
        override suspend fun signOut(accessToken: String) = Unit
    },
    object : SessionPersistence {
        override suspend fun save(session: Session) = Unit
        override suspend fun load(): Session? = null
        override suspend fun clear() = Unit
    },
    { Instant.parse("2026-10-02T12:00:00Z") },
    scope,
)
