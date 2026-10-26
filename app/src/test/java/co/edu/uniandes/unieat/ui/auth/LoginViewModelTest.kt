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

    @Test fun `sign up validates name, email and password length before calling auth`() = runTest(main.dispatcher) {
        val auth = LoginAuth()
        val vm = LoginViewModel(manager(auth, this))
        vm.signUp(" ", "a@b.co", "secret")
        assertTrue(vm.state.value is LoginState.Error)
        vm.signUp("Ana", "bad", "secret")
        assertTrue(vm.state.value is LoginState.Error)
        vm.signUp("Ana", "a@b.co", "12345")
        assertTrue(vm.state.value is LoginState.Error)
        assertEquals(0, auth.signUps)
    }

    @Test fun `sign up with a session signs in`() = runTest(main.dispatcher) {
        val auth = LoginAuth()
        val vm = LoginViewModel(manager(auth, this))
        vm.signUp("Ana", "a@b.co", "secret")
        testScheduler.advanceUntilIdle()
        assertEquals(1, auth.signUps)
        assertTrue(vm.state.value is LoginState.Success)
    }

    @Test fun `sign up that needs email confirmation asks to check the inbox`() = runTest(main.dispatcher) {
        val auth = LoginAuth(confirmEmail = true)
        val vm = LoginViewModel(manager(auth, this))
        vm.signUp("Ana", "a@b.co", "secret")
        testScheduler.advanceUntilIdle()
        assertEquals(LoginState.ConfirmEmail, vm.state.value)
        vm.resetMessage()
        assertEquals(LoginState.Idle, vm.state.value)
    }

    private fun manager(auth: AuthRepository, scope: kotlinx.coroutines.CoroutineScope) = SessionManager(
        auth, EmptyStore(), { Instant.parse("2026-10-02T12:00:00Z") }, scope,
    )
}

private class LoginAuth(
    private val gate: CompletableDeferred<Unit>? = null,
    private val confirmEmail: Boolean = false,
) : AuthRepository {
    var signIns = 0
    var signUps = 0
    override suspend fun signIn(email: String, password: String): Session {
        signIns++
        gate?.await()
        return Session("a", "r", Instant.parse("2026-10-02T13:00:00Z"), "u")
    }
    override suspend fun refresh(refreshToken: String) = error("unused")
    override suspend fun signUp(email: String, password: String, displayName: String): Session? {
        signUps++
        return if (confirmEmail) null else Session("a", "r", Instant.parse("2026-10-02T13:00:00Z"), "u")
    }
    override suspend fun signOut(accessToken: String) = Unit
}

private class EmptyStore : SessionPersistence {
    private var value: Session? = null
    override suspend fun save(session: Session) { value = session }
    override suspend fun load() = value
    override suspend fun clear() { value = null }
}
