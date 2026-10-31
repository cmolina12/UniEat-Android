package co.edu.uniandes.unieat.data.remote

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.core.model.UniEatJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerializationException
import java.io.IOException

fun interface AccessTokenProvider {
    suspend fun accessToken(): String
}

class ApiClient(
    private val config: SupabaseConfig,
    private val tokenProvider: AccessTokenProvider,
    @PublishedApi internal val http: HttpClient = defaultHttpClient(),
) {
    suspend inline fun <reified T> get(
        path: String,
        query: Map<String, Any?> = emptyMap(),
        authenticated: Boolean = true,
    ): T = execute(HttpMethod.Get, path, query, authenticated) {}.decode()

    suspend inline fun <reified T, reified B : Any> send(
        method: HttpMethod,
        path: String,
        body: B?,
        authenticated: Boolean = true,
    ): T = execute(method, path, emptyMap(), authenticated) {
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }.decode()

    @PublishedApi
    internal suspend fun execute(
        method: HttpMethod,
        path: String,
        query: Map<String, Any?>,
        authenticated: Boolean,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        if (!config.isConfigured) throw ApiException.unconfigured()
        val token = if (authenticated) tokenProvider.accessToken() else null

        val response = try {
            http.request("${config.apiBaseUrl}/${path.trimStart('/')}") {
                this.method = method
                header("apikey", config.publishableKey)
                header(PLATFORM_HEADER, PLATFORM)
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                query.forEach { (name, value) -> if (value != null) parameter(name, value) }
                configure()
            }
        } catch (e: IOException) {
            throw ApiException.offline(e)
        }

        if (!response.status.isSuccess()) throw parseError(response)
        return response
    }

    @PublishedApi
    internal suspend inline fun <reified T> HttpResponse.decode(): T = try {
        body()
    } catch (e: SerializationException) {
        throw ApiException.unexpected(status.value, e)
    }

    private suspend fun parseError(response: HttpResponse): ApiException {
        val status = response.status.value
        return try {
            val envelope = UniEatJson.decodeFromString<ApiErrorEnvelope>(response.bodyAsText())
            ApiException(envelope.error, status)
        } catch (e: IllegalArgumentException) {
            ApiException.unexpected(status, e)
        }
    }

    companion object {
        const val PLATFORM_HEADER = "X-UniEat-Platform"
        const val PLATFORM = "android"

        /** Reaching the server; on a healthy network it takes well under a second. A dead link fails here. */
        const val CONNECT_TIMEOUT_MILLIS = 5_000L

        /** Longest silence between two packets of an answer. A link that stops mid-answer fails here. */
        const val SOCKET_TIMEOUT_MILLIS = 8_000L

        /**
         * Whole request: only a ceiling for an answer that keeps arriving, slowly. On the emulator's EDGE profile
         * the feed (about 15 KB) took more than 10 s while still downloading, so a 10 s cap turned a slow but
         * working signal into "Sin conexión". The dead-link cases are caught by the two limits above, and while
         * the request runs the screens already show their saved copy ("Actualizando…"), so waiting costs nothing.
         */
        const val REQUEST_TIMEOUT_MILLIS = 30_000L

        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(UniEatJson) }
            // Weak signal (the "Q building test"): a dead link is detected in seconds (connect / silence), a slow
            // one is allowed to finish. A timeout is an IOException, so it becomes OFFLINE and every screen falls
            // back to its saved copy.
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            }
        }
    }
}
