package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

class SpotifyOAuthLoopbackTest {
    private val state = "local-test-state-not-a-real-session"
    private fun authorization() = SpotifyAuthorization("https://accounts.spotify.com/authorize?state=$state", state)

    private suspend fun request(redirect: String, target: String, host: String? = null, method: String = "GET"): String = withContext(Dispatchers.IO) {
        val uri = URI(redirect)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", uri.port), 2_000)
            socket.soTimeout = 2_000
            val text = "$method $target HTTP/1.1\r\nHost: ${host ?: "127.0.0.1:${uri.port}"}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(text.toByteArray(Charsets.US_ASCII)); flush() }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun validCallbackReturnsToAccountWithoutExposingCodeInBrowserResponse() = runBlocking {
        val bound = CompletableDeferred<String>()
        var browserUrl: String? = null
        val pending = async {
            SpotifyLoopbackServer(port = 0).authorize({ redirect -> bound.complete(redirect); authorization() }, { browserUrl = it })
        }
        val redirect = bound.await()
        assertEquals("127.0.0.1", URI(redirect).host)
        assertEquals("/spotify/callback", URI(redirect).path)
        val response = request(redirect, "/spotify/callback?code=fake-private-code&state=$state")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("Cache-Control: no-store"))
        assertTrue(response.contains("return to Troit"))
        assertFalse(response.contains("fake-private-code"))
        assertFalse(response.contains(state))
        assertEquals("$redirect?code=fake-private-code&state=$state", pending.await())
        assertEquals(authorization().url, browserUrl)
    }

    @Test
    fun incorrectStateHostPathMethodAndDuplicateParametersCannotConsumeCallback() = runBlocking {
        val bound = CompletableDeferred<String>()
        val pending = async { SpotifyLoopbackServer(port = 0).authorize({ redirect -> bound.complete(redirect); authorization() }, {}) }
        val redirect = bound.await()
        assertTrue(request(redirect, "/favicon.ico").startsWith("HTTP/1.1 404"))
        assertTrue(request(redirect, "/spotify/callback?code=fake&state=wrong").startsWith("HTTP/1.1 400"))
        assertTrue(request(redirect, "/spotify/callback?code=fake&state=$state", host = "external.example").startsWith("HTTP/1.1 400"))
        assertTrue(request(redirect, "/spotify/callback?code=fake&state=$state", method = "POST").startsWith("HTTP/1.1 405"))
        assertTrue(request(redirect, "/spotify/callback?code=fake&state=$state&state=$state").startsWith("HTTP/1.1 400"))
        assertFalse(pending.isCompleted)
        assertTrue(request(redirect, "/spotify/callback?code=correct&state=$state").startsWith("HTTP/1.1 200"))
        assertTrue(pending.await().endsWith("?code=correct&state=$state"))
    }

    @Test
    fun denialWithValidStateReturnsToAccountAndShowsFriendlyBrowserMessage() = runBlocking {
        val bound = CompletableDeferred<String>()
        val pending = async { SpotifyLoopbackServer(port = 0).authorize({ redirect -> bound.complete(redirect); authorization() }, {}) }
        val redirect = bound.await()
        val response = request(redirect, "/spotify/callback?error=access_denied&state=$state")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("sign-in was canceled"))
        assertTrue(pending.await().contains("error=access_denied"))
    }

    @Test
    fun cancellationClosesAnIncompleteBrowserConnectionPromptly() = runBlocking {
        val bound = CompletableDeferred<String>()
        val pending = async { SpotifyLoopbackServer(port = 0).authorize({ redirect -> bound.complete(redirect); authorization() }, {}) }
        val redirect = URI(bound.await())
        withContext(Dispatchers.IO) {
            Socket("127.0.0.1", redirect.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().apply { write("GET /spotify/".toByteArray()); flush() }
                withTimeout(1_500) { pending.cancelAndJoin() }
                assertEquals(-1, socket.getInputStream().read())
            }
        }
    }

    @Test
    fun timeoutAndBrowserLaunchFailureReleaseTheListener() = runBlocking {
        val timedRedirect = CompletableDeferred<String>()
        val result = async {
            runCatching { SpotifyLoopbackServer(port = 0, timeoutMs = 300).authorize({ redirect -> timedRedirect.complete(redirect); authorization() }, {}) }
        }
        val redirect = URI(timedRedirect.await())
        val failure = withTimeout(2_000) { result.await().exceptionOrNull() }
        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("timed out"))
        withContext(Dispatchers.IO) { ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), redirect.port)) } }

        var failedRedirect: String? = null
        val launchFailure = runCatching {
            SpotifyLoopbackServer(port = 0).authorize({ value -> failedRedirect = value; authorization() }, { throw IOException("No browser installed") })
        }.exceptionOrNull()
        assertEquals("No browser installed", launchFailure?.message)
        withContext(Dispatchers.IO) { ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", URI(failedRedirect!!).port)) } }
    }

    @Test
    fun busyLocalPortFailsBeforeCreatingAuthorizationOrOpeningBrowser() = runBlocking {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { occupied ->
            var created = false
            var opened = false
            val failure = runCatching {
                SpotifyLoopbackServer(port = occupied.localPort).authorize({ created = true; authorization() }, { opened = true })
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("local port is busy"))
            assertFalse(created)
            assertFalse(opened)
        }
    }
}
