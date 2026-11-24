package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpotifyAccountTest {
    private val clientId = "0123456789abcdef0123456789abcdef"
    private val redirect = "http://127.0.0.1:43824/spotify/callback"
    private val playlist = "0123456789ABCDEFGHIJKL"
    private fun response(request: Request, body: JSONObject, code: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
        .body(body.toString().toResponseBody("application/json".toMediaType())).build()
    private fun token(access: String = "fixture-access", refresh: Boolean = true) = JSONObject()
        .put("access_token", access).put("token_type", "Bearer").put("expires_in", 3600)
        .apply { if (refresh) put("refresh_token", "fixture-refresh") }
    private fun track(index: Int) = JSONObject().put("name", "Song $index").put("type", "track")
        .put("artists", JSONArray().put(JSONObject().put("name", "Artist"))).put("duration_ms", 180_000)

    @Test
    fun pkceHasUniqueStateAndVerifierAndRejectsWrongOrReplayedCallbacks() = runBlocking {
        val dir = Files.createTempDirectory("troit-spotify-pkce").toFile()
        val calls = AtomicInteger()
        var challenge: String? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val form = chain.request().body as FormBody
            val values = (0 until form.size).associate { form.name(it) to form.value(it) }
            assertFalse(values.containsKey("client_secret"))
            assertEquals(clientId, values["client_id"])
            assertEquals(redirect, values["redirect_uri"])
            val verifier = requireNotNull(values["code_verifier"])
            assertTrue(verifier.length in 43..128)
            assertEquals(challenge, Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))))
            response(chain.request(), token())
        }.build()
        try {
            val account = SpotifyAccount(dir, http)
            val first = account.beginAuthorization(clientId, redirect)
            assertTrue(account.busy)
            assertTrue(runCatching { account.completeAuthorization("$redirect?code=abc&state=wrong") }.isFailure)
            assertEquals(0, calls.get())
            assertFalse(account.connected)
            val foreign = account.beginAuthorization(clientId, redirect)
            assertTrue(runCatching { account.completeAuthorization("http://127.0.0.1:43824/wrong?code=abc&state=${foreign.state}") }.isFailure)
            assertEquals(0, calls.get())
            val second = account.beginAuthorization(clientId, redirect)
            assertNotEquals(first.state, second.state)
            challenge = second.url.toHttpUrl().queryParameter("code_challenge")
            assertEquals("S256", second.url.toHttpUrl().queryParameter("code_challenge_method"))
            account.completeAuthorization("$redirect?code=abc&state=${second.state}")
            assertTrue(account.connected)
            assertFalse(account.busy)
            assertTrue(SpotifyAccount(dir, http).connected)
            assertTrue(runCatching { account.completeAuthorization("$redirect?code=abc&state=${second.state}") }.isFailure)
            assertEquals(1, calls.get())
            account.disconnect()
            assertFalse(SpotifyAccount(dir, http).connected)
        } finally { http.dispatcher.executorService.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun cancelDuringTokenExchangeCannotAdoptTheLateSession() = runBlocking {
        val dir = Files.createTempDirectory("troit-spotify-auth-cancel").toFile()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableFuture<Unit>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(Unit)
            gate.get(5, TimeUnit.SECONDS)
            response(chain.request(), token())
        }.build()
        try {
            val account = SpotifyAccount(dir, http)
            val auth = account.beginAuthorization(clientId, redirect)
            val finish = async { account.completeAuthorization("$redirect?code=abc&state=${auth.state}") }
            started.await()
            account.cancelAuthorization()
            gate.complete(Unit)
            finish.await()
            assertFalse(account.connected)
            assertFalse(account.busy)
            assertFalse(SpotifyAccount(dir, http).connected)
        } finally { gate.complete(Unit); http.dispatcher.executorService.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun officialPlaylistPaginationReadsOneThousandItemsWithoutFollowingForeignNextUrls() = runBlocking {
        val dir = Files.createTempDirectory("troit-spotify-pages").toFile()
        val pages = mutableListOf<Int>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host == "accounts.spotify.com") response(request, token()) else {
                assertEquals("api.spotify.com", request.url.host)
                assertEquals("/v1/playlists/$playlist/items", request.url.encodedPath)
                assertEquals("Bearer fixture-access", request.header("Authorization"))
                assertEquals("50", request.url.queryParameter("limit"))
                val offset = request.url.queryParameter("offset")!!.toInt()
                pages += offset
                response(request, JSONObject().put("total", 1105).put("next", "https://foreign.example/steal")
                    .put("items", JSONArray((offset until offset + 50).map { JSONObject().put("item", track(it)) })))
            }
        }.build()
        try {
            val account = SpotifyAccount(dir, http)
            val auth = account.beginAuthorization(clientId, redirect)
            account.completeAuthorization("$redirect?code=abc&state=${auth.state}")
            val source = account.tracks("playlist", playlist)
            assertEquals(1000, source.tracks.size)
            assertEquals("Song 999", source.tracks.last().title)
            assertEquals((0 until 1000 step 50).toList(), pages)
            assertTrue(source.warnings.any { it.contains("1105") })
        } finally { http.dispatcher.executorService.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun expiredCredentialsRefreshWithPkceClientIdAndKeepTheExistingRefreshToken() = runBlocking {
        val dir = Files.createTempDirectory("troit-spotify-refresh").toFile()
        var time = 0L
        val tokenCalls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host == "accounts.spotify.com") {
                val count = tokenCalls.incrementAndGet()
                if (count == 2) {
                    val form = request.body as FormBody
                    val values = (0 until form.size).associate { form.name(it) to form.value(it) }
                    assertEquals("refresh_token", values["grant_type"])
                    assertEquals("fixture-refresh", values["refresh_token"])
                    assertEquals(clientId, values["client_id"])
                }
                response(request, token(if (count == 1) "original" else "renewed", refresh = count == 1))
            } else {
                assertEquals("Bearer renewed", request.header("Authorization"))
                response(request, track(1))
            }
        }.build()
        try {
            val account = SpotifyAccount(dir, http, clock = { time })
            val auth = account.beginAuthorization(clientId, redirect)
            account.completeAuthorization("$redirect?code=abc&state=${auth.state}")
            time = 3_600_001
            assertEquals("Song 1", account.tracks("track", playlist).tracks.single().title)
            assertEquals(2, tokenCalls.get())
        } finally { http.dispatcher.executorService.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun disconnectInvalidatesAnInFlightPageAndPermissionFailuresStayVisible() = runBlocking {
        val dir = Files.createTempDirectory("troit-spotify-disconnect").toFile()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableFuture<Unit>()
        var failPermissions = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host == "accounts.spotify.com") response(request, token())
            else if (failPermissions) response(request, JSONObject(), 403)
            else {
                started.complete(Unit)
                gate.get(5, TimeUnit.SECONDS)
                response(request, track(1))
            }
        }.build()
        try {
            val account = SpotifyAccount(dir, http)
            var auth = account.beginAuthorization(clientId, redirect)
            account.completeAuthorization("$redirect?code=abc&state=${auth.state}")
            val request = async { runCatching { account.tracks("track", playlist) } }
            started.await()
            account.disconnect()
            gate.complete(Unit)
            assertTrue(request.await().isFailure)
            failPermissions = true
            auth = account.beginAuthorization(clientId, redirect)
            account.completeAuthorization("$redirect?code=abc&state=${auth.state}")
            val failure = runCatching { account.tracks("playlist", playlist) }.exceptionOrNull()
            assertTrue(failure!!.message!!.contains("owns or collaborates"))
        } finally { gate.complete(Unit); http.dispatcher.executorService.shutdown(); dir.deleteRecursively() }
    }
}
