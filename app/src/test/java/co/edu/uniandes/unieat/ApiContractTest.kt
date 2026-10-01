package co.edu.uniandes.unieat

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.core.model.EventBatch
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.MenuStatus
import co.edu.uniandes.unieat.core.model.TravelEvidence
import co.edu.uniandes.unieat.core.model.UniEatJson
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.RemoteMenuRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Checks models and ApiClient against the samples in docs/api-v1.md. */
class ApiContractTest {

    private val config = SupabaseConfig("https://demo.supabase.co", "sb_publishable_test")

    private fun client(status: HttpStatusCode, body: String, seen: MutableList<HttpRequestData>): ApiClient {
        val engine = MockEngine { request ->
            seen += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(UniEatJson) }
        }
        return ApiClient(config, { "jwt-token" }, http)
    }

    @Test
    fun feedDecodesAndSendsHeaders() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = RemoteMenuRepository(client(HttpStatusCode.OK, FEED_SAMPLE, seen))

        val feed = repo.feed(FeedFilters(budgetCop = 15000, diet = "vegetarian", paymentMethod = null))

        val request = seen.single()
        assertEquals("sb_publishable_test", request.headers["apikey"])
        assertEquals("Bearer jwt-token", request.headers[HttpHeaders.Authorization])
        assertEquals("/functions/v1/api-v1/feed", request.url.encodedPath)
        assertEquals("15000", request.url.parameters["budgetCop"])
        assertNull(request.url.parameters["paymentMethod"])

        val menu = feed.menus.single()
        assertEquals(Instant.parse("2026-09-29T17:20:34.728Z"), feed.serverNow)
        assertEquals("Tazón completo", menu.title)
        assertEquals(12000, menu.items.single().priceCop)
        assertEquals(listOf("vegetarian", "vegan"), menu.items.single().dietaryTags)
        assertNull(menu.waitMinutes)
        assertNull(menu.closedAt)
        assertEquals(MenuStatus.ACTIVE, menu.status)
        assertEquals(TravelEvidence.APPROXIMATE, menu.travelEvidence)
        assertTrue(menu.isActive(feed.serverNow))
        assertFalse(menu.hasWaitEvidence(feed.serverNow))
    }

    @Test
    fun errorEnvelopeBecomesApiException() = runTest {
        val body = """{"error":{"code":"GONE","message":"Este menú ya venció","traceId":"t-1",
            "details":{"status":"expired","version":1,"validUntil":"2026-09-30T05:19:11.216Z","closedAt":null}}}"""
        val repo = RemoteMenuRepository(client(HttpStatusCode.Gone, body, mutableListOf()))
        try {
            repo.menu("x")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiException.GONE, e.code)
            assertEquals(410, e.httpStatus)
            assertEquals("Este menú ya venció", e.message)
            assertEquals("t-1", e.error.traceId)
        }
    }

    @Test
    fun nonJsonErrorIsUnexpected() = runTest {
        val repo = RemoteMenuRepository(client(HttpStatusCode.BadGateway, "<html>", mutableListOf()))
        try {
            repo.myMenus()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiException.INTERNAL_ERROR, e.code)
            assertEquals(502, e.httpStatus)
        }
    }

    @Test
    fun eventBatchEncodesPlatformAndMillis() {
        val json = UniEatJson.encodeToString(
            EventBatch.serializer(),
            EventBatch(events = listOf(
                co.edu.uniandes.unieat.core.model.RemoteEvent(
                    "e", "s", "p", 1, "feed_impression", Instant.parse("2026-09-29T17:00:00Z"),
                ),
            )),
        )
        assertTrue(json, json.contains("\"platform\":\"android\""))
        assertTrue(json, json.contains("\"occurredAt\":\"2026-09-29T17:00:00.000Z\""))
        assertFalse(json, json.contains("metadata"))
    }

    private companion object {
        const val FEED_SAMPLE = """
        {
          "serverNow": "2026-09-29T17:20:34.728Z",
          "fetchedAt": "2026-09-29T17:20:34.728Z",
          "algorithmVersion": "rank-v1",
          "filters": {"budgetCop":15000,"availableMinutes":40,"diet":"vegetarian","area":"Centro","paymentMethod":null},
          "resultCount": 1,
          "menus": [{
            "id": "10000000-0000-4000-8000-000000000002",
            "title": "Tazón completo",
            "version": 1, "currentVersion": 1,
            "validUntil": "2026-09-30T05:19:11.216Z",
            "publishedAt": "2026-09-29T16:34:11.216Z",
            "closedAt": null,
            "establishmentId": "e0000000-0000-4000-8000-000000000002",
            "establishmentName": "Bowls Centro Cívico",
            "area": "Centro",
            "address": "Carrera 1 #18A-70",
            "entranceDescription": "Local junto a la esquina del bloque B",
            "latitude": 4.6036, "longitude": -74.064,
            "photoUrl": null,
            "paymentMethods": ["Nequi", "Tarjeta"],
            "isVerified": false,
            "items": [{"id":"45ef039d-0000-4000-8000-000000000000","name":"Tazón de hummus","description":"Vegetales, garbanzo y arroz",
                       "category":"Almuerzo","priceCop":12000,"dietaryTags":["vegetarian","vegan"],"dietaryKnown":true}],
            "lowestPriceCop": 12000,
            "waitMinutes": null, "waitSampleCount": 1, "waitNewestReportAt": "2026-09-29T17:09:11.216Z",
            "pendingReports": 0, "status": "active", "relevanceScore": 40,
            "travelMinutes": 5, "travelEvidence": "approximate", "hasWaitEvidence": false,
            "explanation": "Tazón de hummus por ${'$'}12.000 COP cumple presupuesto"
          }]
        }"""
    }
}
