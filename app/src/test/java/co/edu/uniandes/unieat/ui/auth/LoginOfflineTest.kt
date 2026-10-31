package co.edu.uniandes.unieat.ui.auth

import co.edu.uniandes.unieat.data.auth.AuthRepository
import co.edu.uniandes.unieat.data.auth.Session
import co.edu.uniandes.unieat.data.auth.SessionManager
import co.edu.uniandes.unieat.data.auth.SessionPersistence
import co.edu.uniandes.unieat.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Samuel — eventual connectivity of the login: network only, said before sending, recovers by itself. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginOfflineTest {
    @get:Rule val main = MainDispatcherRule()

    private class CountingAuth : AuthRepository {
        var calls = 0
        override suspend fun signIn(email: String, password: String): Session {
            calls++
            return Session("a", "r", Instant.parse("2026-10-30T18:00:00Z"), "u")
        }
        override suspend fun signUp(email: String, password: String, displayName: String): Session? {
            calls++
            return null
        }
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun signOut(accessToken: String) = Unit
    }

    private class NoSession : SessionPersistence {
        override suspend fun save(session: Session) = Unit
        override suspend fun load(): Session? = null
        override suspend fun clear() = Unit
    }

    private fun manager(auth: AuthRepository, scope: kotlinx.coroutines.CoroutineScope) =
        SessionManager(auth, NoSession(), { Instant.parse("2026-10-30T17:00:00Z") }, scope)

    @Test fun `without signal nothing is sent and the student is told why`() = runTest(main.dispatcher) {
        val auth = CountingAuth()
        val online = MutableStateFlow(false)
        val vm = LoginViewModel(manager(auth, this), online)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.offline.value)

        vm.signIn("samuel@example.com", "secret")
        vm.signUp("Samuel", "samuel@example.com", "secret")
        testScheduler.advanceUntilIdle()

        assertEquals(0, auth.calls)
        assertEquals(LoginState.Error(LoginViewModel.OFFLINE_MESSAGE), vm.state.value)
    }

    @Test fun `field errors are still explained offline`() = runTest(main.dispatcher) {
        val vm = LoginViewModel(manager(CountingAuth(), this), MutableStateFlow(false))
        testScheduler.advanceUntilIdle()

        vm.signIn("no-es-correo", "secret")

        assertEquals(LoginState.Error("Escribe un correo válido."), vm.state.value)
    }

    @Test fun `the notice clears itself and sign in works when the signal returns`() = runTest(main.dispatcher) {
        val auth = CountingAuth()
        val online = MutableStateFlow(false)
        val vm = LoginViewModel(manager(auth, this), online)
        testScheduler.advanceUntilIdle()
        vm.signIn("samuel@example.com", "secret")

        online.value = true
        testScheduler.advanceUntilIdle()
        assertEquals(LoginState.Idle, vm.state.value)

        vm.signIn("samuel@example.com", "secret")
        testScheduler.advanceUntilIdle()
        assertEquals(1, auth.calls)
        assertEquals(LoginState.Success, vm.state.value)
    }
}
