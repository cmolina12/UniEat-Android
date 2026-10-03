package co.edu.uniandes.unieat.data.auth

import java.time.Instant

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val userId: String,
)

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val session: Session) : AuthState
}
