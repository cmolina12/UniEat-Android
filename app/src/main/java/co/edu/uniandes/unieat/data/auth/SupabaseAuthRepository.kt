package co.edu.uniandes.unieat.data.auth

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.Instant

class SupabaseAuthRepository(
    private val config: SupabaseConfig,
    private val http: HttpClient = ApiClient.defaultHttpClient(),
    private val clock: () -> Instant = Instant::now,
) : AuthRepository {
    override suspend fun signIn(email: String, password: String): Session =
        token("password", PasswordGrant(email, password))

    override suspend fun refresh(refreshToken: String): Session =
        token("refresh_token", RefreshGrant(refreshToken))

    override suspend fun signOut(accessToken: String) {
        val response = try {
            http.post("${config.authBaseUrl}/logout") {
                header("apikey", config.publishableKey)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
        } catch (e: IOException) { throw ApiException.offline(e) }
        if (!response.status.isSuccess()) throw authError(response.status.value)
    }

    private suspend inline fun <reified B: Any> token(grantType: String, body: B): Session {
        val response = try {
            http.post("${config.authBaseUrl}/token") {
                parameter("grant_type", grantType)
                header("apikey", config.publishableKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: IOException) { throw ApiException.offline(e) }
        if (!response.status.isSuccess()) throw authError(response.status.value)
        val dto = response.body<AuthSessionDto>()
        return Session(dto.accessToken, dto.refreshToken, clock().plusSeconds(dto.expiresIn), dto.user.id)
    }

    private fun authError(status: Int): ApiException = when (status) {
        400, 401 -> ApiException(
            ApiError(ApiException.AUTH_REQUIRED, "Correo o contraseña incorrectos, o la sesión ya no es válida."),
            status,
        )
        429 -> ApiException(
            ApiError(ApiException.RATE_LIMITED, "Demasiados intentos. Espera un momento e inténtalo de nuevo."),
            status,
        )
        else -> ApiException.unexpected(status)
    }

    @Serializable private data class PasswordGrant(val email: String, val password: String)
    @Serializable private data class RefreshGrant(@SerialName("refresh_token") val refreshToken: String)
    @Serializable private data class AuthUser(val id: String)
    @Serializable private data class AuthSessionDto(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        val user: AuthUser,
    )
}
