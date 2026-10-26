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

    /** Only the display name is sent: the backend gives every new account the student role. */
    override suspend fun signUp(email: String, password: String, displayName: String): Session? {
        val response = try {
            http.post("${config.authBaseUrl}/signup") {
                header("apikey", config.publishableKey)
                contentType(ContentType.Application.Json)
                setBody(SignUpBody(email, password, SignUpData(displayName)))
            }
        } catch (e: IOException) { throw ApiException.offline(e) }
        if (!response.status.isSuccess()) {
            val errorCode = runCatching { response.body<AuthErrorDto>().errorCode }.getOrNull()
            throw signUpError(response.status.value, errorCode)
        }
        // With email confirmation on, Supabase answers with the bare user and no tokens.
        val dto = response.body<SignUpDto>()
        val accessToken = dto.accessToken ?: return null
        val refreshToken = dto.refreshToken ?: return null
        val user = dto.user ?: return null
        return Session(accessToken, refreshToken, clock().plusSeconds(dto.expiresIn), user.id)
    }

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

    private fun signUpError(status: Int, errorCode: String?): ApiException {
        val message = when (errorCode) {
            "user_already_exists", "email_exists" -> "Ya existe una cuenta con ese correo. Inicia sesión."
            "weak_password" -> "La contraseña es muy débil: usa al menos $MIN_PASSWORD_LENGTH caracteres."
            "email_address_invalid", "validation_failed" -> "Escribe un correo válido."
            "signup_disabled", "email_provider_disabled" -> "El registro de cuentas nuevas está deshabilitado."
            else -> return authError(status)
        }
        return ApiException(ApiError(ApiException.VALIDATION_ERROR, message), status)
    }

    @Serializable private data class PasswordGrant(val email: String, val password: String)
    @Serializable private data class RefreshGrant(@SerialName("refresh_token") val refreshToken: String)
    @Serializable private data class SignUpData(@SerialName("display_name") val displayName: String)
    @Serializable private data class SignUpBody(val email: String, val password: String, val data: SignUpData)
    @Serializable private data class AuthErrorDto(@SerialName("error_code") val errorCode: String? = null)
    @Serializable private data class AuthUser(val id: String)
    @Serializable private data class SignUpDto(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        val user: AuthUser? = null,
    )
    @Serializable private data class AuthSessionDto(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        val user: AuthUser,
    )

    companion object {
        /** Supabase Auth minimum (supabase/config.toml in the backend). */
        const val MIN_PASSWORD_LENGTH = 6
    }
}
