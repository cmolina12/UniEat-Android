package co.edu.uniandes.unieat.data.auth

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.core.model.UniEatJson
import co.edu.uniandes.unieat.data.remote.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class DevTokenProviderTest {

    private val config = SupabaseConfig("http://10.0.2.2:54321", "sb_publishable_test")
    private var now = Instant.parse("2026-09-29T17:00:00Z")
    private val seen = mutableListOf<HttpRequestData>()

    private fun provider(status: HttpStatusCode = HttpStatusCode.OK): DevTokenProvider {
        var issued = 0
        val engine = MockEngine { request ->
            seen += request
            issued++
            val body = if (status.value == 200) """{"access_token":"jwt-$issued","token_type":"bearer","expires_in":3600}"""
            else """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { expectSuccess = false; install(ContentNegotiation) { json(UniEatJson) } }
        return DevTokenProvider(config, "estudiante1@unieat.test", "pw", http) { now }
    }

    @Test
    fun signsInWithThePasswordGrant() = runTest {
        assertEquals("jwt-1", provider().accessToken())

        val request = seen.single()
        assertEquals("/auth/v1/token", request.url.encodedPath)
        assertEquals("password", request.url.parameters["grant_type"])
        assertEquals("sb_publishable_test", request.headers["apikey"])
        val body = (request.body as TextContent).text
        assertTrue(body, body.contains("\"email\":\"estudiante1@unieat.test\""))
    }

    @Test
    fun reusesTheTokenUntilShortlyBeforeItExpires() = runTest {
        val p = provider()
        assertEquals("jwt-1", p.accessToken())
        now = now.plusSeconds(3600 - 120) // 2 min left: still valid
        assertEquals("jwt-1", p.accessToken())
        now = now.plusSeconds(90) // 30 s left: inside the 1-min margin → sign in again
        assertEquals("jwt-2", p.accessToken())
        assertEquals(2, seen.size)
    }

    @Test
    fun wrongCredentialsBecomeAuthRequired() = runTest {
        try {
            provider(HttpStatusCode.BadRequest).accessToken()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiException.AUTH_REQUIRED, e.code)
            assertTrue(e.error.message, e.error.message.contains("estudiante1@unieat.test"))
        }
    }
}
