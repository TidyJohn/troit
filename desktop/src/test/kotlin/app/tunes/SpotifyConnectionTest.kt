package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SpotifyConnectionTest {
    private suspend fun until(done: () -> Boolean) = withTimeout(3_000) { while (!done()) delay(5) }

    @Test
    fun completedBrowserHandshakeKeepsTheConnectedStateAndSuccessMessage() = runBlocking {
        val dir = Files.createTempDirectory("troit-spotify-ui-flow").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("""{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"Bearer"}""".toResponseBody()).build()
        }.build()
        try {
            val account = SpotifyAccount(dir, http)
            val connection = SpotifyConnection(account, scope) { begin, browser ->
                val authorization = begin(SpotifyOAuthLoopback.REDIRECT_URI)
                browser(authorization.url)
                SpotifyOAuthLoopback.REDIRECT_URI.toHttpUrl().newBuilder().addQueryParameter("state", authorization.state)
                    .addQueryParameter("code", "fixture-code").build().toString()
            }
            var opened = ""
            connection.connect("a".repeat(32)) { opened = it }
            until { !connection.connecting }
            assertTrue(opened.startsWith("https://accounts.spotify.com/authorize?"))
            assertTrue(account.connected)
            assertFalse(account.busy)
            assertNull(connection.error)
            assertTrue(account.status.contains("connected", ignoreCase = true))
            assertFalse(account.status.contains("canceled", ignoreCase = true))
        } finally { scope.cancel(); http.dispatcher.executorService.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun cancelingBrowserWaitClearsThePendingAccountAttempt() = runBlocking {
        val dir = Files.createTempDirectory("troit-spotify-ui-cancel").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val account = SpotifyAccount(dir)
            val waiting = CompletableDeferred<Unit>()
            val connection = SpotifyConnection(account, scope) { begin, _ ->
                begin(SpotifyOAuthLoopback.REDIRECT_URI)
                waiting.complete(Unit)
                CompletableDeferred<String>().await()
            }
            connection.connect("b".repeat(32)) {}
            waiting.await()
            assertTrue(account.busy)
            connection.cancel()
            until { !connection.connecting }
            assertFalse(account.busy)
            assertFalse(account.connected)
            assertNull(connection.error)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }
}
