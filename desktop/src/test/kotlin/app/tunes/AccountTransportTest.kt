package app.tunes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** All HTTP is intercepted locally. These tests never connect to Supabase or use a real account. */
class AccountTransportTest {
    private val owner = "00000000-0000-0000-0000-000000000101"
    private val oldAccess = "offline-fixture-access-before-renewal"
    private val freshAccess = "offline-fixture-access-after-renewal"
    private val mediaType = "application/json".toMediaType()

    @Test
    fun largeCollaborationPromotionKeepsOtherAccountRequestLimitsIntact() = runBlocking {
        var requests = 0
        val body = JSONObject().put("p_songs", org.json.JSONArray().apply {
            repeat(1_000) { put(JSONObject().put("title", "Song $it").put("artist", "Artist")
                .put("url", "https://music.youtube.com/watch?v=%011d".format(it))
                .put("artwork", "https://art.example/" + "x".repeat(300)).put("duration", 180)) }
        }).toString()
        assertTrue(body.toByteArray().size > 256_000)
        Fixture(Interceptor { chain -> requests++; reply(chain.request(), text = "ok") }).use { f ->
            assertEquals("ok", f.sync.accountRequest("rpc/troit_collab_create", "POST", body, maximumRequestBytes = 2_000_000))
            val rejected = runCatching { f.sync.accountRequest("rpc/troit_chat_send", "POST", body, maximumRequestBytes = 2_000_000) }
            assertTrue(rejected.isFailure)
            assertEquals(1, requests)
        }
    }

    private inner class Fixture(interceptor: Interceptor) : AutoCloseable {
        val dir = Files.createTempDirectory("troit-account-http-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val sessionFile = File(dir, "session.json").apply {
            writeText(JSONObject().put("access", oldAccess).put("refresh", "offline-fixture-refresh")
                .put("userId", owner).put("email", "offline-fixture@example.invalid").toString())
        }
        val sync = Sync(dir, "Offline transport fixture", Library(File(dir, "library.json")), scope, client)
        fun anotherSync() = Sync(dir, "Offline widget fixture", Library(File(dir, "widget-library.json")), scope, client)
        override fun close() {
            scope.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun reply(request: Request, code: Int = 200, text: String = "{}", body: ResponseBody? = null) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Offline fixture")
        .body(body ?: text.toResponseBody(mediaType)).build()

    private fun renewedSession() = JSONObject().put("access_token", freshAccess)
        .put("refresh_token", "offline-fixture-new-refresh")
        .put("user", JSONObject().put("id", owner).put("email", "offline-fixture@example.invalid")).toString()

    private class TrackedBody(text: String) : ResponseBody() {
        val closed = AtomicBoolean(false)
        private val bytes = text.toByteArray(Charsets.UTF_8)
        private val buffered = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() { closed.set(true); super.close() }
        }.buffer()
        override fun contentType(): MediaType = "application/json".toMediaType()
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = buffered
    }

    @Test
    fun expiredAccessIsRenewedOnceAndRetriedWithTheFreshCredential() = runBlocking {
        val headers = Collections.synchronizedList(mutableListOf<String?>())
        val refreshes = AtomicInteger()
        val unauthorized = TrackedBody("{}")
        Fixture(Interceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/rest/v1/rpc/offline_fixture" -> {
                    headers += request.header("Authorization")
                    if (headers.size == 1) reply(request, 401, body = unauthorized) else reply(request, text = "{\"ok\":true}")
                }
                "/auth/v1/token" -> { refreshes.incrementAndGet(); reply(request, text = renewedSession()) }
                else -> throw IOException("Unexpected offline fixture endpoint")
            }
        }).use { fixture ->
            assertEquals("{\"ok\":true}", fixture.sync.accountRequest("rpc/offline_fixture", "POST", "{}"))
            assertEquals(1, refreshes.get())
            assertEquals(2, headers.size)
            assertTrue("The first request uses the existing fixture credential", headers[0] == "Bearer $oldAccess")
            assertTrue("The retry uses the renewed fixture credential", headers[1] == "Bearer $freshAccess")
            assertTrue("The unauthorized response must be closed before renewal", unauthorized.closed.get())
            assertEquals(owner, fixture.sync.userId)
            assertTrue("Renewal is persisted for later requests", JSONObject(fixture.sessionFile.readText()).getString("access") == freshAccess)
        }
    }

    @Test
    fun logoutDiscardsAnInflightDataResponse() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dataBody = TrackedBody("{\"private_fixture\":true}")
        Fixture(Interceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/logout")) reply(request)
            else {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "The fixture response was not released" }
                reply(request, body = dataBody)
            }
        }).use { fixture ->
            try {
                val pending = async(Dispatchers.Default) { runCatching { fixture.sync.accountRequest("rpc/offline_fixture", "POST", "{}") } }
                assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
                fixture.sync.signOut()
                release.countDown()
                val outcome = withTimeout(5_000) { pending.await() }
                assertTrue("Data from the old account must be discarded", outcome.isFailure)
                assertTrue(outcome.exceptionOrNull() is IOException)
                assertNull(fixture.sync.userId)
                assertFalse(fixture.sessionFile.exists())
                assertTrue(dataBody.closed.get())
            } finally { release.countDown() }
        }
    }

    @Test
    fun logoutDuringCredentialRenewalCannotRestoreTheOldSession() = runBlocking {
        val refreshing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dataCalls = AtomicInteger()
        Fixture(Interceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/rest/v1/rpc/offline_fixture" -> { dataCalls.incrementAndGet(); reply(request, 401) }
                "/auth/v1/token" -> {
                    refreshing.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "The fixture renewal was not released" }
                    reply(request, text = renewedSession())
                }
                "/auth/v1/logout" -> reply(request)
                else -> throw IOException("Unexpected offline fixture endpoint")
            }
        }).use { fixture ->
            try {
                val pending = async(Dispatchers.Default) { runCatching { fixture.sync.accountRequest("rpc/offline_fixture", "POST", "{}") } }
                assertTrue(withContext(Dispatchers.IO) { refreshing.await(5, TimeUnit.SECONDS) })
                fixture.sync.signOut()
                release.countDown()
                assertTrue(withTimeout(5_000) { pending.await() }.isFailure)
                assertNull(fixture.sync.userId)
                assertNull(fixture.sync.email)
                assertFalse("A completed old renewal must not recreate the deleted session", fixture.sessionFile.exists())
                assertEquals("No data retry may run after logout", 1, dataCalls.get())
            } finally { release.countDown() }
        }
    }

    @Test
    fun logoutInASecondSyncInstanceDiscardsTheFirstInstancesInflightData() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val dataBody = TrackedBody("{\"private_widget_fixture\":true}")
        Fixture(Interceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/logout")) reply(request)
            else {
                calls.incrementAndGet()
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "The widget fixture response was not released" }
                reply(request, body = dataBody)
            }
        }).use { fixture ->
            val other = fixture.anotherSync()
            try {
                val pending = async(Dispatchers.Default) { runCatching { fixture.sync.accountRequest("rpc/offline_fixture", "POST", "{}") } }
                assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
                other.signOut()
                release.countDown()
                val outcome = withTimeout(5_000) { pending.await() }
                assertTrue("The Activity logout must invalidate an independent widget worker response", outcome.exceptionOrNull() is IOException)
                assertNull(other.userId)
                assertFalse(fixture.sessionFile.exists())
                assertTrue("Discarded cross-instance data still releases its HTTP response", dataBody.closed.get())
                assertTrue(runCatching { fixture.sync.accountRequest("rpc/offline_fixture", "POST", "{}") }.isFailure)
                assertEquals("The stale worker cannot send another request using its cached credential", 1, calls.get())
            } finally { release.countDown() }
        }
    }

    @Test
    fun renewalInAWorkerCannotRecreateTheSessionAfterAnotherInstanceLogsOut() = runBlocking {
        val refreshing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dataCalls = AtomicInteger()
        Fixture(Interceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/rest/v1/rpc/offline_fixture" -> { dataCalls.incrementAndGet(); reply(request, 401) }
                "/auth/v1/token" -> {
                    refreshing.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "The cross-instance renewal was not released" }
                    reply(request, text = renewedSession())
                }
                "/auth/v1/logout" -> reply(request)
                else -> throw IOException("Unexpected offline fixture endpoint")
            }
        }).use { fixture ->
            val other = fixture.anotherSync()
            try {
                val pending = async(Dispatchers.Default) { runCatching { fixture.sync.accountRequest("rpc/offline_fixture", "POST", "{}") } }
                assertTrue(withContext(Dispatchers.IO) { refreshing.await(5, TimeUnit.SECONDS) })
                other.signOut()
                release.countDown()
                assertTrue(withTimeout(5_000) { pending.await() }.isFailure)
                assertNull(other.userId)
                assertNull(other.email)
                assertFalse("A worker renewal finishing after Activity logout must not restore credentials on disk", fixture.sessionFile.exists())
                assertEquals("The old worker must not retry data after another instance signs out", 1, dataCalls.get())
            } finally { release.countDown() }
        }
    }

    @Test
    fun twoSyncInstancesShareCredentialRenewalAndAdoptTheSamePersistedAccess() = runBlocking {
        val initialRequests = CountDownLatch(2)
        val refreshes = AtomicInteger()
        val freshRequests = AtomicInteger()
        Fixture(Interceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/rest/v1/rpc/offline_fixture" -> {
                    if (request.header("Authorization") == "Bearer $oldAccess") {
                        initialRequests.countDown()
                        check(initialRequests.await(5, TimeUnit.SECONDS)) { "Both independent fixture requests must start" }
                        reply(request, 401)
                    } else {
                        check(request.header("Authorization") == "Bearer $freshAccess")
                        freshRequests.incrementAndGet()
                        reply(request, text = "{\"ok\":true}")
                    }
                }
                "/auth/v1/token" -> { refreshes.incrementAndGet(); reply(request, text = renewedSession()) }
                else -> throw IOException("Unexpected offline fixture endpoint")
            }
        }).use { fixture ->
            val other = fixture.anotherSync()
            val responses = listOf(fixture.sync, other).map { instance ->
                async(Dispatchers.Default) { instance.accountRequest("rpc/offline_fixture", "POST", "{}") }
            }.awaitAll()
            assertEquals(listOf("{\"ok\":true}", "{\"ok\":true}"), responses)
            assertEquals("A shared session path must renew a rotating refresh token only once", 1, refreshes.get())
            assertEquals(2, freshRequests.get())
            assertTrue(JSONObject(fixture.sessionFile.readText()).getString("access") == freshAccess)
        }
    }

    @Test
    fun oversizedResponsesAreRejectedAndClosed() = runBlocking {
        val oversized = TrackedBody("x".repeat(2_000_001))
        Fixture(Interceptor { chain -> reply(chain.request(), body = oversized) }).use { fixture ->
            val outcome = runCatching { fixture.sync.accountRequest("rpc/offline_fixture", "POST", "{}") }
            assertTrue(outcome.exceptionOrNull() is IOException)
            assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("too large"))
            assertTrue("Oversized data must release its response body", oversized.closed.get())
            assertEquals(owner, fixture.sync.userId)
        }
    }

    @Test
    fun parallelFeatureCallsRemainSerialized() = runBlocking {
        val active = AtomicInteger()
        val highest = AtomicInteger()
        val calls = AtomicInteger()
        Fixture(Interceptor { chain ->
            val current = active.incrementAndGet()
            highest.updateAndGet { max(it, current) }
            try {
                calls.incrementAndGet()
                Thread.sleep(25) // Simulated blocking I/O stays inside the interceptor's worker thread.
                reply(chain.request())
            } finally { active.decrementAndGet() }
        }).use { fixture ->
            val results = (1..8).map {
                async(Dispatchers.Default) { fixture.sync.accountRequest("rpc/offline_fixture", "POST", "{}") }
            }.awaitAll()
            assertEquals(8, results.size)
            assertEquals(8, calls.get())
            assertEquals("Feature polling and writes must not overlap HTTP requests", 1, highest.get())
        }
    }

    @Test
    fun invalidEndpointsAndOversizedBodiesNeverReachHttp() = runBlocking {
        val calls = AtomicInteger()
        Fixture(Interceptor { chain -> calls.incrementAndGet(); reply(chain.request()) }).use { fixture ->
            listOf("https://example.invalid", "../profiles", "rpc/function#fragment", "rpc/function\n", "a".repeat(2049)).forEach { path ->
                assertTrue(runCatching { fixture.sync.accountRequest(path, "POST", "{}") }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(runCatching {
                fixture.sync.accountRequest("rpc/offline_fixture", "POST", "é".repeat(128_001))
            }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, calls.get())
        }
    }
}
