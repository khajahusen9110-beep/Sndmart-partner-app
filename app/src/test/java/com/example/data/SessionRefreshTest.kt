package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppPreferences
import com.example.data.remote.SupabaseRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The refresh rules that decide whether a rider stays logged in:
 *  - a good answer is saved to the disk at once,
 *  - only a real refusal by the server ends the session,
 *  - a bad network or a busy server never does,
 *  - the request is never sent twice by the HTTP layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionRefreshTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var prefs: AppPreferences
    private lateinit var repo: SupabaseRepository
    private var expiredCalls = 0

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sndmart_partner_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
        prefs.saveSessionTokens("old-access", "old-refresh", System.currentTimeMillis() - 1000)
        prefs.userId = "user-1"
        server = MockWebServer().also { it.start() }
        repo = SupabaseRepository(context, prefs)
        repo.refreshBaseUrlForTests = server.url("/").toString()
        repo.onSessionExpired = { expiredCalls++ }
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun ok() = MockResponse().setResponseCode(200)
        .setBody("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"user":{"id":"user-1"}}""")

    @Test fun goodRefresh_isSavedToTheDisk_immediately() = runBlocking {
        server.enqueue(ok())
        val r = repo.refreshSession()
        assertTrue(r.isSuccess)
        // read it back through a NEW preferences object: it is on the disk, not only in memory
        val fresh = AppPreferences(context)
        assertEquals("new-access", fresh.token); assertEquals("new-refresh", fresh.refreshToken)
        assertTrue(fresh.tokenExpiresAt > System.currentTimeMillis())
        assertEquals("old-refresh", server.takeRequest().body.readUtf8().substringAfter("refresh_token\":\"").substringBefore("\""))
    }

    @Test fun realRefusal_endsTheSession_andSaysSo() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Invalid Refresh Token: Refresh Token Not Found"}"""))
        val r = repo.refreshSession()
        assertTrue(r.exceptionOrNull() is SupabaseRepository.SessionExpiredException)
        assertNull(prefs.token); assertNull(prefs.refreshToken)
        Thread.sleep(200)
        assertEquals(1, expiredCalls)
    }

    @Test fun busyServer_doesNotLogTheRiderOut() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val r = repo.refreshSession()
        assertTrue(r.isFailure); assertFalse(r.exceptionOrNull() is SupabaseRepository.SessionExpiredException)
        assertEquals("old-refresh", prefs.refreshToken); assertEquals("old-access", prefs.token)
        Thread.sleep(100); assertEquals(0, expiredCalls)
    }

    @Test fun tooManyRequests_isNotAnExpiry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertFalse(repo.refreshSession().exceptionOrNull() is SupabaseRepository.SessionExpiredException)
        assertEquals("old-refresh", prefs.refreshToken)
    }

    @Test fun noNetwork_doesNotLogTheRiderOut() = runBlocking {
        server.shutdown()
        val r = repo.refreshSession()
        assertTrue(r.isFailure); assertFalse(r.exceptionOrNull() is SupabaseRepository.SessionExpiredException)
        assertEquals("old-refresh", prefs.refreshToken)
        Thread.sleep(100); assertEquals(0, expiredCalls)
    }

    @Test fun refusalAboutAnOldToken_isOk_whenANewerOneWasStoredMeanwhile() = runBlocking {
        // another caller (a login, another refresh) stored a newer token while this request was in flight
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                prefs.saveSessionTokens("newer-access", "newer-refresh", System.currentTimeMillis() + 3_600_000)
                return MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
            }
        }
        val r = repo.refreshSession()
        assertTrue(r.isSuccess)
        assertEquals("newer-refresh", prefs.refreshToken) // the session was NOT cleared
        Thread.sleep(100); assertEquals(0, expiredCalls)
    }

    @Test fun aDroppedConnection_isNotSentTwice() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val r = repo.refreshSession()
        assertTrue(r.isFailure); assertFalse(r.exceptionOrNull() is SupabaseRepository.SessionExpiredException)
        assertEquals(1, server.requestCount) // a replay would carry a token the server already rotated
        assertEquals("old-refresh", prefs.refreshToken)
    }

    @Test fun noRefreshToken_meansExpired() = runBlocking {
        prefs.clearSession()
        val r = repo.refreshSession()
        assertTrue(r.exceptionOrNull() is SupabaseRepository.SessionExpiredException)
        assertEquals(0, server.requestCount)
    }
}
