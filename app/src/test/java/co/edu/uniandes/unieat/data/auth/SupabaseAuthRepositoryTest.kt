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

}
