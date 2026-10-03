package co.edu.uniandes.unieat.data.auth

import co.edu.uniandes.unieat.data.remote.AccessTokenProvider
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/** Proxy between ApiClient and Supabase Auth. Callers do not need to manage token refresh. */
class SessionManager(
    private val repository: AuthRepository,
    private val store: SessionPersistence,
    private val clock: () -> Instant = Instant::now,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AccessTokenProvider {
    private val mutex = Mutex()
    private var session: Session? = null
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                if (_state.value !is AuthState.Loading) return@withLock
                session = store.load()
                _state.value = session?.let(AuthState::SignedIn) ?: AuthState.SignedOut
            }
        }
    }

    suspend fun signIn(email: String, password: String) = mutex.withLock {
        repository.signIn(email.trim(), password).also { persist(it) }
    }

    /** Remote logout is best-effort. Local credentials are always removed. */
    suspend fun signOut() = mutex.withLock {
        val current = session ?: store.load()
        try {
            current?.let { repository.signOut(it.accessToken) }
        } catch (_: ApiException) {
            // A failed/expired remote session must not keep credentials on the device.
        } finally {
            session = null
            store.clear()
            _state.value = AuthState.SignedOut
        }
    }

    override suspend fun accessToken(): String = mutex.withLock {
        val current = session ?: store.load()?.also {
            session = it
            _state.value = AuthState.SignedIn(it)
        } ?: throw ApiException.authRequired()

        if (clock() < current.expiresAt.minus(REFRESH_MARGIN)) return current.accessToken

        try {
            repository.refresh(current.refreshToken).also { persist(it) }.accessToken
        } catch (e: ApiException) {
            if (e.code == ApiException.AUTH_REQUIRED) {
                session = null
                store.clear()
                _state.value = AuthState.SignedOut
            }
            throw e
        }
    }

    private suspend fun persist(value: Session) {
        session = value
        store.save(value)
        _state.value = AuthState.SignedIn(value)
    }

    private companion object {
        val REFRESH_MARGIN: Duration = Duration.ofMinutes(1)
    }
}
