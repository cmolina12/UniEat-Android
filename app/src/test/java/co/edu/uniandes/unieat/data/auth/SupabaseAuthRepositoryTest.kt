package co.edu.uniandes.unieat.data.auth
import co.edu.uniandes.unieat.core.config.SupabaseConfig
import co.edu.uniandes.unieat.core.model.UniEatJson
import co.edu.uniandes.unieat.data.remote.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class SupabaseAuthRepositoryTest {
 private val config=SupabaseConfig("http://localhost:54321","key")
 @Test fun `password grant maps Supabase session`()=runTest{
  var path=""; var grant:String?=null; var apiKey:String?=null
  val engine=MockEngine{r->path=r.url.encodedPath; grant=r.url.parameters["grant_type"]; apiKey=r.headers["apikey"]; respond("""{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"u"}}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))}
  val repo=SupabaseAuthRepository(config,HttpClient(engine){install(ContentNegotiation){json(UniEatJson)}},{Instant.EPOCH})
  val s=repo.signIn("a@b.co","pw"); assertEquals("/auth/v1/token",path); assertEquals("password",grant); assertEquals("key",apiKey); assertEquals("u",s.userId); assertEquals(Instant.EPOCH.plusSeconds(3600),s.expiresAt)
 }
 @Test fun `bad credentials become auth required`()=runTest{
  val engine=MockEngine{respond("{}",HttpStatusCode.BadRequest,headersOf(HttpHeaders.ContentType,"application/json"))}
  val repo=SupabaseAuthRepository(config,HttpClient(engine){install(ContentNegotiation){json(UniEatJson)}})
  try{repo.signIn("a@b.co","bad");fail("expected")}catch(e:ApiException){assertEquals(ApiException.AUTH_REQUIRED,e.code)}
 }
 @Test fun `server error is not reported as bad credentials`()=runTest{
  val engine=MockEngine{respond("{}",HttpStatusCode.InternalServerError,headersOf(HttpHeaders.ContentType,"application/json"))}
  val repo=SupabaseAuthRepository(config,HttpClient(engine){install(ContentNegotiation){json(UniEatJson)}})
  try{repo.signIn("a@b.co","pw");fail("expected")}catch(e:ApiException){assertEquals(ApiException.INTERNAL_ERROR,e.code);assertEquals(500,e.httpStatus)}
 }
 @Test fun `rate limit has its own error code`()=runTest{
  val engine=MockEngine{respond("{}",HttpStatusCode.TooManyRequests,headersOf(HttpHeaders.ContentType,"application/json"))}
  val repo=SupabaseAuthRepository(config,HttpClient(engine){install(ContentNegotiation){json(UniEatJson)}})
  try{repo.signIn("a@b.co","pw");fail("expected")}catch(e:ApiException){assertEquals(ApiException.RATE_LIMITED,e.code)}
 }
 @Test fun `sign up sends only the display name and maps the session`()=runTest{
  var path=""; var body=""
  val engine=MockEngine{r->path=r.url.encodedPath; body=(r.body as io.ktor.http.content.TextContent).text; respond("""{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"u"}}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))}
  val repo=SupabaseAuthRepository(config,HttpClient(engine){install(ContentNegotiation){json(UniEatJson)}},{Instant.EPOCH})
  val s=repo.signUp("a@b.co","secret","Ana")
  assertEquals("/auth/v1/signup",path); assertTrue(body.contains("\"display_name\":\"Ana\"")); assertFalse(body.contains("role")); assertEquals("u",s?.userId)
 }
 @Test fun `sign up waiting for email confirmation returns no session`()=runTest{
  val engine=MockEngine{respond("""{"id":"u","email":"a@b.co","confirmation_sent_at":"2026-10-25T12:00:00Z"}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))}
  val repo=SupabaseAuthRepository(config,HttpClient(engine){install(ContentNegotiation){json(UniEatJson)}})
  assertNull(repo.signUp("a@b.co","secret","Ana"))
 }
 @Test fun `sign up with a taken email explains it`()=runTest{
  val engine=MockEngine{respond("""{"code":422,"error_code":"user_already_exists","msg":"User already registered"}""",HttpStatusCode.UnprocessableEntity,headersOf(HttpHeaders.ContentType,"application/json"))}
  val repo=SupabaseAuthRepository(config,HttpClient(engine){install(ContentNegotiation){json(UniEatJson)}})
  try{repo.signUp("a@b.co","secret","Ana");fail("expected")}catch(e:ApiException){assertEquals(ApiException.VALIDATION_ERROR,e.code);assertTrue(e.message!!.contains("Ya existe"))}
 }

}
