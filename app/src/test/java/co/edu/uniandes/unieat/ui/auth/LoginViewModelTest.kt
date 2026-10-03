package co.edu.uniandes.unieat.ui.auth

import co.edu.uniandes.unieat.data.auth.AuthRepository
import co.edu.uniandes.unieat.data.auth.Session
import co.edu.uniandes.unieat.data.auth.SessionManager
import co.edu.uniandes.unieat.data.auth.SessionPersistence
import co.edu.uniandes.unieat.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `validates email and password before calling auth`() = runTest(main.dispatcher) {
        val auth = LoginAuth()
        val vm = LoginViewModel(manager(auth, this))
        vm.signIn("bad", "secret")
        assertTrue(vm.state.value is LoginState.Error)
        vm.signIn("a@b.co", "")
        assertTrue(vm.state.value is LoginState.Error)
        assertEquals(0, auth.signIns)
    }

    @Test fun `moves loading to success and ignores double tap`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val auth = LoginAuth(gate)
        val vm = LoginViewModel(manager(auth, this))
        vm.signIn("samuel@example.com", "secret")
        testScheduler.runCurrent()
        assertTrue(vm.state.value is LoginState.Loading)
        vm.signIn("samuel@example.com", "secret")
        testScheduler.runCurrent()
        assertEquals(1, auth.signIns)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value is LoginState.Success)
    }

    private fun manager(auth: AuthRepository, scope: kotlinx.coroutines.CoroutineScope) = SessionManager(
        auth, EmptyStore(), { Instant.parse("2026-10-02T12:00:00Z") }, scope,
    )
}

private class LoginAuth(private val gate: CompletableDeferred<Unit>? = null) : AuthRepository {
    var signIns = 0
    override suspend fun signIn(email: String, password: String): Session {
        signIns++
        gate?.await()
        return Session("a", "r", Instant.parse("2026-10-02T13:00:00Z"), "u")
    }
    override suspend fun refresh(refreshToken: String) = error("unused")
    override suspend fun signOut(accessToken: String) = Unit
}

private class EmptyStore : SessionPersistence {
    private var value: Session? = null
    override suspend fun save(session: Session) { value = session }
    override suspend fun load() = value
    override suspend fun clear() { value = null }
}
