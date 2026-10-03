package co.edu.uniandes.unieat.data.telemetry

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.core.model.UniEatJson
import co.edu.uniandes.unieat.data.analytics.FlushResult
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.remote.ApiError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BQ-01 sync: which records are ready to send, the uploader, and the backend contract. */
class FeedLoadSyncTest {

    @Test fun `a load waiting for its render is not sent until it is abandoned`() {
        val pending = record("p", "render_pending", startedAt = 0)
        assertNull(FeedLoadTelemetry.settle(pending, nowMs = 59_999))
        val abandoned = FeedLoadTelemetry.settle(pending, nowMs = 60_000)!!
        assertEquals("abandoned", abandoned.outcome)
        assertEquals("RENDER_ABANDONED", abandoned.errorCode)
        val rendered = record("r", "rendered", startedAt = 0)
        assertEquals(rendered, FeedLoadTelemetry.settle(rendered, nowMs = 1))
    }

    @Test fun `uploader sends in batches and marks every sent record`() = runTest {
        val outbox = FakeOutbox(List(5) { record("l$it", "rendered") })
        val repo = FakeRepository()
        assertEquals(FlushResult.DONE, FeedLoadUploader(outbox, repo, batchSize = 2).flush())
        assertEquals(listOf(2, 2, 1), repo.batchSizes)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test fun `offline keeps the records for a later retry`() = runTest {
        val outbox = FakeOutbox(listOf(record("a", "failure")))
        val repo = FakeRepository(failure = ApiException.offline())
        assertEquals(FlushResult.RETRY_LATER, FeedLoadUploader(outbox, repo).flush())
        assertEquals(1, outbox.pending.size)
    }

    @Test fun `a batch the server rejects as invalid is dropped so it cannot block newer ones`() = runTest {
        val outbox = FakeOutbox(listOf(record("a", "rendered")))
        val repo = FakeRepository(failure = ApiException(ApiError(ApiException.VALIDATION_ERROR, "bad"), 400))
        assertEquals(FlushResult.DONE, FeedLoadUploader(outbox, repo).flush())
        assertTrue(outbox.pending.isEmpty())
    }

    @Test fun `summary decodes the backend answer`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = RemoteFeedLoadRepository(client(HttpStatusCode.OK, SUMMARY_SAMPLE, seen), LocalStub)

        val report = repo.summary(7)

        val request = seen.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/functions/v1/api-v1/telemetry/feed-loads/summary", request.url.encodedPath)
        assertEquals("7", request.url.parameters["days"])
        assertEquals(SCOPE_ALL_DEVICES, report.scope)
        assertEquals(12, report.attempts)
        assertEquals(2, report.abandoned)
        val worst = report.groups.first()
        assertEquals("cellular", worst.connectionType)
        assertEquals(13, worst.hour)
        assertEquals(0.25, worst.failureRate, 0.0)
        assertEquals(2400L, worst.p95RequestToRenderMs)
        assertNull(report.groups[1].p95RequestToRenderMs)
    }

    @Test fun `summary falls back to this device when the server cannot answer`() = runTest {
        val repo = RemoteFeedLoadRepository(client(HttpStatusCode.NotFound, NOT_FOUND, mutableListOf()), LocalStub)
        assertEquals(SCOPE_THIS_DEVICE, repo.summary(7).scope)
    }

    @Test fun `upload posts to the telemetry endpoint`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = RemoteFeedLoadRepository(client(HttpStatusCode.OK, """{"accepted":1,"duplicates":0}""", seen), LocalStub)
        repo.upload(listOf(record("a", "rendered")))
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/functions/v1/api-v1/telemetry/feed-loads", request.url.encodedPath)
    }

    private fun client(status: HttpStatusCode, body: String, seen: MutableList<HttpRequestData>): ApiClient {
        val engine = MockEngine { request ->
            seen += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(UniEatJson) }
        }
        return ApiClient(SupabaseConfig("https://demo.supabase.co", "sb_publishable_test"), { "jwt-token" }, http)
    }

    private companion object {
        val SUMMARY_SAMPLE = """
            {"periodDays":7,"scope":"all_devices","attempts":12,"abandoned":2,"groups":[
              {"connectionType":"cellular","deviceModel":"Pixel 6a","osVersion":"Android 14 (SDK 34)","hour":13,
               "attempts":8,"failures":2,"abandoned":1,"failureRate":0.25,"p95RequestToRenderMs":2400},
              {"connectionType":"wifi","deviceModel":"Pixel 8","osVersion":"Android 15 (SDK 35)","hour":12,
               "attempts":4,"failures":4,"abandoned":1,"failureRate":1,"p95RequestToRenderMs":null}
            ]}
        """.trimIndent()

        const val NOT_FOUND = """{"error":{"code":"NOT_FOUND","message":"Ruta no encontrada.","traceId":"t"}}"""
    }
}

private fun record(id: String, outcome: String, startedAt: Long = 1_000) = FeedLoadRecord(
    loadId = id,
    sessionId = "s",
    startedAtEpochMs = startedAt,
    renderedAtEpochMs = if (outcome == "rendered") startedAt + 300 else null,
    outcome = outcome,
    connectionType = "wifi",
    deviceModel = "Pixel",
    osVersion = "Android 15",
    appVersion = "1",
)

private class FakeOutbox(records: List<FeedLoadRecord>) : FeedLoadOutbox {
    val pending = records.toMutableList()
    override suspend fun pendingUpload(limit: Int) = pending.take(limit)
    override suspend fun markUploaded(records: List<FeedLoadRecord>) {
        val ids = records.map { it.loadId }.toSet()
        pending.removeAll { it.loadId in ids }
    }
}

private class FakeRepository(private val failure: ApiException? = null) : FeedLoadRepository {
    val batchSizes = mutableListOf<Int>()
    override suspend fun upload(loads: List<FeedLoadRecord>) {
        if (failure != null) throw failure
        batchSizes += loads.size
    }
    override suspend fun summary(days: Int): FeedLoadReport = error("unused")
}

private object LocalStub : FeedLoadRepository {
    override suspend fun upload(loads: List<FeedLoadRecord>) = Unit
    override suspend fun summary(days: Int) = FeedLoadReport(days, 0, 0, emptyList(), SCOPE_THIS_DEVICE)
}
