package co.edu.uniandes.unieat.data.remote

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.core.model.UniEatJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Weak signal: a request that takes too long must end as OFFLINE, so the screens use their saved copy. */
class ApiTimeoutTest {
    @Test
    fun aRequestThatTakesTooLongBecomesOffline() = runBlocking {
        val slowServer = MockEngine {
            delay(5_000) // the answer never arrives in time
            respond("{}")
        }
        val http = HttpClient(slowServer) {
            expectSuccess = false
            install(ContentNegotiation) { json(UniEatJson) }
            install(HttpTimeout) { requestTimeoutMillis = 100 } // same plugin as the app, shorter for the test
        }
        val api = ApiClient(SupabaseConfig("https://demo.supabase.co", "sb_publishable_test"), { "jwt" }, http)

        try {
            api.get<Map<String, String>>("feed")
            fail("the request should have timed out")
        } catch (e: ApiException) {
            assertEquals(ApiException.OFFLINE, e.code)
        }
    }

    @Test
    fun aDeadLinkIsDetectedInSecondsButASlowAnswerCanFinish() {
        // Dead link: no connection, or silence in the middle of the answer.
        assertTrue(ApiClient.CONNECT_TIMEOUT_MILLIS <= 5_000)
        assertTrue(ApiClient.SOCKET_TIMEOUT_MILLIS <= 8_000)
        // Slow link: the 15 KB feed over EDGE needs more than 10 s, so the whole-request ceiling is larger.
        assertTrue(ApiClient.REQUEST_TIMEOUT_MILLIS >= 20_000)
    }
}
