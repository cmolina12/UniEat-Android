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
        assertEquals("android", request.headers["X-UniEat-Platform"])
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
    fun menuDetailDecodesReportsAndFeedsLocationGuidance() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = RemoteMenuRepository(client(HttpStatusCode.OK, DETAIL_SAMPLE, seen))

        val detail = repo.menu("10000000-0000-4000-8000-000000000002")

        val request = seen.single()
        assertEquals(io.ktor.http.HttpMethod.Get, request.method)
        assertEquals("/functions/v1/api-v1/menus/10000000-0000-4000-8000-000000000002", request.url.encodedPath)
        assertEquals("Bearer jwt-token", request.headers[HttpHeaders.Authorization])

        val menu = detail.menu
        assertEquals(Instant.parse("2026-09-29T17:20:34.728Z"), detail.serverNow)
        assertEquals(3, menu.reports.size)
        assertEquals(co.edu.uniandes.unieat.core.model.ReportKind.LOCATION, menu.reports[0].kind)
        assertNull(menu.reports[0].resolvedAt)
        assertEquals(Instant.parse("2026-09-29T17:15:00.000Z"), menu.reports[1].resolvedAt)

        // Contract → BQ-05: only the pending location report counts; the missing photo is flagged.
        val guidance = co.edu.uniandes.unieat.core.decision.locationGuidance(menu)
        assertEquals(1, guidance.pendingLocationReports)
        assertEquals(setOf(co.edu.uniandes.unieat.core.decision.LocationReference.PHOTO), guidance.missing)
        assertEquals(Instant.parse("2026-09-29T16:34:11.216Z"), guidance.updatedAt)
    }

    @Test
    fun performanceDecodesAndSendsTheDays() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val body = """{"periodDays":7,"impressions":120,"detailOpens":45,"selections":18,
            "reportedArrivals":9,"sampleSize":120,"insufficientData":false,
            "rates":{"detailOpenRate":0.37}}"""
        val repo = co.edu.uniandes.unieat.data.analytics.RemoteAnalyticsRepository(client(HttpStatusCode.OK, body, seen))

        val summary = repo.performance(days = 7)

        val request = seen.single()
        assertEquals("/functions/v1/api-v1/performance", request.url.encodedPath)
        assertEquals("7", request.url.parameters["days"])
        assertEquals(120, summary.impressions)
        assertEquals(18, summary.selections)
        assertFalse(summary.insufficientData) // unknown fields like "rates" are ignored
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
    fun performanceUsesTheRoleEndpoint() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val body = """{"periodDays":7,"impressions":1,"detailOpens":1,"selections":0,"reportedArrivals":0,
            "savedMenus":0,"locationOpens":0,"reports":0,"sampleSize":1,"insufficientData":true,"rates":null}"""
        val repo = co.edu.uniandes.unieat.data.analytics.RemoteAnalyticsRepository(client(HttpStatusCode.OK, body, seen))

        repo.performance(7)
        repo.restaurantPerformance(28)

        assertEquals("/functions/v1/api-v1/performance", seen[0].url.encodedPath)
        assertEquals("/functions/v1/api-v1/restaurant/performance", seen[1].url.encodedPath)
        assertEquals("28", seen[1].url.parameters["days"])
    }

    /** GET /admin/dashboard per docs/api-v1.md: only `bq05` is decoded, the rest is ignored. */
    @Test
    fun adminDashboardDecodesBq05ByPlatform() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val body = """{"platform":"ios","periodDays":7,"bq03":{"feedRequests":4},"bq04":{"detailRequests":2},
            "engagement":{"impressions":1},
            "bq05":{"periodDays":7,"generatedAt":"2026-10-03T12:00:00Z",
              "ios":{"locationOpens":3,"reportedArrivals":1,"locationReports":{"pending":1,"confirmed":0,"dismissed":0}},
              "android":{"locationOpens":2,"reportedArrivals":1,"locationReports":{"pending":0,"confirmed":1,"dismissed":0}},
              "unknown":{"locationOpens":0,"reportedArrivals":0,"locationReports":{"pending":0,"confirmed":0,"dismissed":0}},
              "coverage":{"establishments":4,"withCoordinates":3,"withoutCoordinates":1,"withEntranceDescription":2,
                "withoutEntranceDescription":2,"withPhoto":0,"withoutPhoto":4}}}"""
        val repo = co.edu.uniandes.unieat.data.analytics.RemoteAnalyticsRepository(client(HttpStatusCode.OK, body, seen))

        val bq05 = repo.locationGuidance(7)!!

        assertEquals("/functions/v1/api-v1/admin/dashboard", seen.single().url.encodedPath)
        assertEquals("7", seen.single().url.parameters["days"])
        assertEquals(2, bq05.android.locationOpens)
        assertEquals(1, bq05.android.locationReports.confirmed)
        assertEquals(1, bq05.ios.locationReports.pending)
        assertEquals(1, bq05.coverage.withoutCoordinates)
        assertEquals(4, bq05.coverage.withoutPhoto)
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

    @Test
    fun eventBatchIsPostedAndRejectedEventsDecode() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val body = """{"accepted":1,"duplicates":0,"rejected":[{"eventId":"e2","reason":"UNKNOWN_VERSION"}],
            "receivedAt":"2026-09-29T17:00:01.000Z"}"""
        val repo = co.edu.uniandes.unieat.data.analytics.RemoteAnalyticsRepository(client(HttpStatusCode.OK, body, seen))

        val response = repo.sendBatch(listOf(
            co.edu.uniandes.unieat.core.model.RemoteEvent("e1", "s", "p", 1, "detail_open", Instant.parse("2026-09-29T17:00:00Z")),
        ))

        val request = seen.single()
        assertEquals(io.ktor.http.HttpMethod.Post, request.method)
        assertEquals("/functions/v1/api-v1/events/batch", request.url.encodedPath)
        val sent = (request.body as io.ktor.http.content.TextContent).text
        assertTrue(sent, sent.contains("\"platform\":\"android\""))
        assertTrue(sent, sent.contains("\"eventId\":\"e1\""))
        assertEquals(1, response.accepted)
        assertEquals("UNKNOWN_VERSION", response.rejected.single().reason)
    }

    @Test
    fun locationReportIsPostedWithTheContractShape() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val body = """{"reportId":"r1","publicationId":"p","version":2,"kind":"location","status":"pending",
            "createdAt":"2026-09-29T17:00:00.000Z",
            "message":"Reporte recibido. Queda pendiente hasta que se revise; el menú oficial no cambia."}"""
        val repo = co.edu.uniandes.unieat.data.repository.RemoteReportRepository(client(HttpStatusCode.Created, body, seen))

        val response = repo.submit(co.edu.uniandes.unieat.core.model.ReportBody(
            publicationId = "p", version = 2, kind = co.edu.uniandes.unieat.core.model.ReportKind.LOCATION,
        ))

        val request = seen.single()
        assertEquals(io.ktor.http.HttpMethod.Post, request.method)
        assertEquals("/functions/v1/api-v1/reports", request.url.encodedPath)
        val sent = (request.body as io.ktor.http.content.TextContent).text
        assertEquals("""{"publicationId":"p","version":2,"kind":"location"}""", sent)
        assertEquals("pending", response.status)
        assertEquals(co.edu.uniandes.unieat.core.model.ReportKind.LOCATION, response.kind)
    }

    private companion object {
        /** GET /menus/:id 200 per docs/api-v1.md (BQ-04): DailyMenu + status, explanation and reports. */
        const val DETAIL_SAMPLE = """
        {
          "serverNow": "2026-09-29T17:20:34.728Z",
          "fetchedAt": "2026-09-29T17:20:34.728Z",
          "menu": {
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
            "items": [{"id":"45ef039d-0000-4000-8000-000000000000","name":"Tazón de hummus","description":"",
                       "category":"Almuerzo","priceCop":12000,"dietaryTags":[],"dietaryKnown":true}],
            "lowestPriceCop": 12000,
            "waitMinutes": null, "waitSampleCount": 1, "waitNewestReportAt": "2026-09-29T17:09:11.216Z",
            "pendingReports": 2, "status": "active", "relevanceScore": 0,
            "explanation": "Menú vigente",
            "reports": [
              {"id":"r1","kind":"location","status":"pending","createdAt":"2026-09-29T17:10:00.000Z","resolvedAt":null},
              {"id":"r2","kind":"location","status":"dismissed","createdAt":"2026-09-29T17:00:00.000Z","resolvedAt":"2026-09-29T17:15:00.000Z"},
              {"id":"r3","kind":"price","status":"pending","createdAt":"2026-09-29T16:50:00.000Z","resolvedAt":null}
            ]
          }
        }"""

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
