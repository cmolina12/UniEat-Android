package co.edu.uniandes.unieat.data.auth

interface AuthRepository {
    suspend fun signIn(email: String, password: String): Session
    suspend fun refresh(refreshToken: String): Session
    suspend fun signOut(accessToken: String)
}
