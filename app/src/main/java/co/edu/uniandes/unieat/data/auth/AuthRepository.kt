package co.edu.uniandes.unieat.data.auth

interface AuthRepository {
    suspend fun signIn(email: String, password: String): Session

    /** Returns null when the project requires email confirmation before the first sign-in. */
    suspend fun signUp(email: String, password: String, displayName: String): Session?
    suspend fun refresh(refreshToken: String): Session
    suspend fun signOut(accessToken: String)
}
