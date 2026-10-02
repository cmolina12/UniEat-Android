package co.edu.uniandes.unieat.data.auth

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.data.remote.AccessTokenProvider
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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * Debug-only stand-in for the Login feature: signs in to Supabase Auth with a test account
 * (`POST /auth/v1/token?grant_type=password`, the same call as the backend's integration tests)
 * and caches the JWT until shortly before it expires. Never compiled into release builds.
 */
class DevTokenProvider(
    private val config: SupabaseConfig,
    private val email: String,
    private val password: String,
    private val http: HttpClient = ApiClient.defaultHttpClient(),
    private val clock: () -> Instant = Instant::now,
) : AccessTokenProvider {

    private val mutex = Mutex() // concurrent requests share one sign-in
    private var token: String? = null
    private var expiresAt: Instant = Instant.EPOCH

    override suspend fun accessToken(): String = mutex.withLock {
        token?.takeIf { clock() < expiresAt - REFRESH_MARGIN } ?: signIn()
    }

    private suspend fun signIn(): String {
        val response = try {
            http.post("${config.authBaseUrl}/token") {
                parameter("grant_type", "password")
                header("apikey", config.publishableKey)
                contentType(ContentType.Application.Json)
                setBody(PasswordGrant(email, password))
            }
        } catch (e: IOException) {
            throw ApiException.offline(e)
        }
        if (!response.status.isSuccess()) {
            throw ApiException(
                ApiError(ApiException.AUTH_REQUIRED, "No se pudo iniciar sesión con la cuenta de prueba $email."),
                response.status.value,
            )
        }
        val session = response.body<Session>()
        token = session.accessToken
        expiresAt = clock().plusSeconds(session.expiresIn)
        return session.accessToken
    }

    @Serializable
    private data class PasswordGrant(val email: String, val password: String)

    @Serializable
    private data class Session(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    )

    private companion object {
        val REFRESH_MARGIN: Duration = Duration.ofMinutes(1)
    }
}
