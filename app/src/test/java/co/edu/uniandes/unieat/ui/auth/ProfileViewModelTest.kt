package co.edu.uniandes.unieat.ui.auth

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
