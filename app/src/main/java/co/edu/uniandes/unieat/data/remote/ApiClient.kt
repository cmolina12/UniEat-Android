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

/** Supplies the Supabase Auth access token (JWT). Never return the publishable key here. */
fun interface AccessTokenProvider {
    suspend fun accessToken(): String
}

/**
 * Adapter over the shared API v1: adds `apikey` and `Authorization: Bearer <jwt>`, sends/reads
 * camelCase JSON and turns every failure into [ApiException]. Port of iOS APIClient.
 */
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

    /** Sends the request and returns it only if 2xx; otherwise throws the decoded [ApiException]. */
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
                // Lets the server attribute reports, feed and detail requests to Android (not "unknown").
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
            // Non-JSON body (e.g. gateway HTML) or missing "error" envelope.
            ApiException.unexpected(status, e)
        }
    }

    companion object {
        const val PLATFORM_HEADER = "X-UniEat-Platform"
        const val PLATFORM = "android"

        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(UniEatJson) }
            install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        }
    }
}
