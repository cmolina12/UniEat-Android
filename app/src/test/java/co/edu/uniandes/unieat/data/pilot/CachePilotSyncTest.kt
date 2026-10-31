package co.edu.uniandes.unieat.data.pilot

import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.core.model.UniEatJson
import co.edu.uniandes.unieat.data.analytics.FlushResult
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.telemetry.SCOPE_ALL_DEVICES
import co.edu.uniandes.unieat.data.telemetry.SCOPE_THIS_DEVICE
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Samuel — BQ-13 upload (eventual connectivity) and the backend contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class CachePilotSyncTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-10-30T17:00:00Z")
    private val dao = InMemoryCachePilotDao()
    private val pilot = CachePilot(MemoryEnrollment(), dao, { "u" }, { "wifi" }, clock = { now }, io = dispatcher)

    private fun ended(id: String) = CachePilotSession(
        sessionId = id, variant = "cache_enabled", startedAtMillis = now.toEpochMilli() - 60_000,
        lastActivityMillis = now.toEpochMilli() - 60_000, connectionType = "wifi", cacheAvailable = true,
        cacheAgeMs = 5_000, firstContentMs = 110, firstContentSource = "cache", networkRenderMs = 1_400,
        outcome = "network", selected = true, closed = true,
    )

    private class FakeRepository(var failure: ApiException? = null) : CachePilotRepository {
        val batches = mutableListOf<List<CachePilotSessionDto>>()
        override suspend fun upload(sessions: List<CachePilotSessionDto>) {
            failure?.let { throw it }
            batches += sessions
        }
        override suspend fun summary(days: Int): CachePilotReport = error("unused")
    }

    @Test
    fun sendsEndedVisitsInBatchesAndMarksThem() = runTest(dispatcher) {
        repeat(5) { dao.upsert(ended("v$it")) }
        val repository = FakeRepository()

        assertEquals(FlushResult.DONE, CachePilotUploader(pilot, repository, batchSize = 2).flush())

        assertEquals(listOf(2, 2, 1), repository.batches.map { it.size })
        assertTrue(dao.rows.values.all { it.uploaded })
    }

    @Test
    fun withoutSignalEverythingWaitsForTheNextAttempt() = runTest(dispatcher) {
        dao.upsert(ended("v1"))
        val repository = FakeRepository(ApiException.offline())

        assertEquals(FlushResult.RETRY_LATER, CachePilotUploader(pilot, repository).flush())
        assertFalse(dao.rows.getValue("v1").uploaded)

        repository.failure = null
        assertEquals(FlushResult.DONE, CachePilotUploader(pilot, repository).flush())
        assertTrue(dao.rows.getValue("v1").uploaded)
    }

    @Test
    fun anEndpointThatIsNotDeployedYetKeepsTheData() = runTest(dispatcher) {
        dao.upsert(ended("v1"))
        val notDeployed = ApiException(ApiError(ApiException.NOT_FOUND, "Ruta no encontrada."), 404)

        assertEquals(FlushResult.RETRY_LATER, CachePilotUploader(pilot, FakeRepository(notDeployed)).flush())
        assertFalse(dao.rows.getValue("v1").uploaded)
    }

    @Test
    fun aBatchTheServerRejectsIsSkippedSoItCannotBlockNewerOnes() = runTest(dispatcher) {
        dao.upsert(ended("v1"))
        val invalid = ApiException(ApiError(ApiException.VALIDATION_ERROR, "bad"), 400)

        assertEquals(FlushResult.DONE, CachePilotUploader(pilot, FakeRepository(invalid)).flush())
        assertTrue(dao.rows.getValue("v1").uploaded)
    }

    @Test
    fun openVisitsOfAPreviousLaunchAreEndedAndSent() = runTest(dispatcher) {
        dao.upsert(ended("old").copy(closed = false, outcome = "pending"))
        val repository = FakeRepository()

        CachePilotUploader(pilot, repository).flush()

        assertEquals("abandoned", repository.batches.single().single().outcome)
    }

    @Test
    fun uploadPostsTheContractShape() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = RemoteCachePilotRepository(client(HttpStatusCode.OK, """{"accepted":1,"duplicates":0}""", seen), LocalStub)

        repo.upload(listOf(ended("4f6c8a8e-0000-4000-8000-000000000001").toDto()))

        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/functions/v1/api-v1/telemetry/cache-pilot", request.url.encodedPath)
        val sent = (request.body as TextContent).text
        assertTrue(sent, sent.contains("\"platform\":\"android\""))
        assertTrue(sent, sent.contains("\"variant\":\"cache_enabled\""))
        assertTrue(sent, sent.contains("\"startedAt\":\"2026-10-30T16:59:00.000Z\""))
        assertFalse("no location or menu ever travels", sent.contains("publicationId") || sent.contains("latitude"))
    }

    @Test
    fun summaryDecodesTheBackendAnswer() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val repo = RemoteCachePilotRepository(client(HttpStatusCode.OK, SUMMARY, seen), LocalStub)

        val report = repo.summary(28)

        assertEquals("/functions/v1/api-v1/telemetry/cache-pilot/summary", seen.single().url.encodedPath)
        assertEquals("28", seen.single().url.parameters["days"])
        assertEquals(SCOPE_ALL_DEVICES, report.scope)
        assertEquals(12, report.sessions)
        val online = report.groups.single { it.variant == "online_only" }
        assertEquals(1_950L, online.medianFirstContentMs)
        assertEquals(0.5, online.withoutSelectionShare!!, 1e-9)
    }

    @Test
    fun summaryFallsBackToThisDeviceWhenTheServerCannotAnswer() = runTest {
        val repo = RemoteCachePilotRepository(client(HttpStatusCode.NotFound, NOT_FOUND, mutableListOf()), LocalStub)
        assertEquals(SCOPE_THIS_DEVICE, repo.summary(7).scope)
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

    private object LocalStub : CachePilotRepository {
        override suspend fun upload(sessions: List<CachePilotSessionDto>) = Unit
        override suspend fun summary(days: Int) = CachePilotReport(days, 0, scope = SCOPE_THIS_DEVICE)
    }

    private companion object {
        val SUMMARY = """
            {"periodDays":28,"sessions":12,"scope":"all_devices","minSessionsForShare":5,"groups":[
              {"variant":"cache_enabled","connectionType":"wifi","cacheAvailable":true,"sessions":6,"withContent":6,
               "medianFirstContentMs":140,"p95FirstContentMs":410,"withoutSelection":2,"withoutSelectionShare":0.3333},
              {"variant":"online_only","connectionType":"wifi","cacheAvailable":true,"sessions":6,"withContent":5,
               "medianFirstContentMs":1950,"p95FirstContentMs":6100,"withoutSelection":3,"withoutSelectionShare":0.5}
            ]}
        """.trimIndent()

        const val NOT_FOUND = """{"error":{"code":"NOT_FOUND","message":"Ruta no encontrada.","traceId":"t"}}"""
    }
}
