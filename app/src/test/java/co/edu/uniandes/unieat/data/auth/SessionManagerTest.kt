package co.edu.uniandes.unieat.data.auth

import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SessionManagerTest {
    @Test fun `reuses valid token`() = runTest {
        val now=Instant.parse("2026-10-02T12:00:00Z"); val repo=FakeAuth(now); val store=MemoryStore(Session("a","r",now.plusSeconds(600),"u"))
        val manager=SessionManager(repo,store,{now},this)
        assertEquals("a",manager.accessToken()); assertEquals(0,repo.refreshes)
    }
    @Test fun `refreshes expiring token once for concurrent callers`() = runTest {
        val now=Instant.parse("2026-10-02T12:00:00Z"); val repo=FakeAuth(now); val store=MemoryStore(Session("old","r",now.plusSeconds(30),"u"))
        val manager=SessionManager(repo,store,{now},this)
        val tokens=listOf(async{manager.accessToken()},async{manager.accessToken()}).awaitAll()
        assertEquals(listOf("new","new"),tokens); assertEquals(1,repo.refreshes)
    }
    @Test fun `auth refresh failure clears session`() = runTest {
        val now=Instant.parse("2026-10-02T12:00:00Z"); val repo=FakeAuth(now,fail=true); val store=MemoryStore(Session("old","r",now.plusSeconds(10),"u"))
        val manager=SessionManager(repo,store,{now},this)
        try { manager.accessToken(); fail("expected") } catch(_:ApiException) {}
        assertNull(store.value); assertTrue(manager.state.value is AuthState.SignedOut)
    }
    @Test fun `logout clears local session when remote logout is offline`() = runTest {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val store = MemoryStore(Session("a", "r", now.plusSeconds(600), "u"))
        val repo = object : AuthRepository {
            override suspend fun signIn(email: String, password: String) = error("unused")
            override suspend fun refresh(refreshToken: String) = error("unused")
            override suspend fun signOut(accessToken: String) { throw ApiException.offline() }
        }
        val manager = SessionManager(repo, store, { now }, this)
        manager.signOut()
        assertNull(store.value)
        assertTrue(manager.state.value is AuthState.SignedOut)
    }

    @Test fun `server refresh failure does not clear local session`() = runTest {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val original = Session("old", "r", now.plusSeconds(10), "u")
        val store = MemoryStore(original)
        val repo = object : AuthRepository {
            override suspend fun signIn(email: String, password: String) = error("unused")
            override suspend fun refresh(refreshToken: String): Session { throw ApiException.unexpected(500) }
            override suspend fun signOut(accessToken: String) = Unit
        }
        val manager = SessionManager(repo, store, { now }, this)
        try { manager.accessToken(); fail("expected") } catch (e: ApiException) {
            assertEquals(ApiException.INTERNAL_ERROR, e.code)
        }
        assertEquals(original, store.value)
        assertTrue(manager.state.value is AuthState.SignedIn)
    }

}
private class MemoryStore(var value:Session?):SessionPersistence{ override suspend fun save(session:Session){value=session}; override suspend fun load()=value; override suspend fun clear(){value=null} }
private class FakeAuth(private val now:Instant,private val fail:Boolean=false):AuthRepository{ var refreshes=0; override suspend fun signIn(email:String,password:String)=Session("a","r",now.plusSeconds(600),"u"); override suspend fun refresh(refreshToken:String):Session{refreshes++; if(fail) throw ApiException.authRequired(); return Session("new","r2",now.plusSeconds(600),"u")}; override suspend fun signOut(accessToken:String)=Unit }
