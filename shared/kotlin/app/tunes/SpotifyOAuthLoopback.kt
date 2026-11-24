package app.tunes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** PKCE callback transport shared by Android and Windows. No client secret or external listener. */
internal object SpotifyOAuthLoopback {
    const val REDIRECT_URI = "http://127.0.0.1:43824/spotify/callback"

    suspend fun authorize(
        createAuthorization: (String) -> SpotifyAuthorization,
        openBrowser: (String) -> Unit,
    ): String = SpotifyLoopbackServer().authorize(createAuthorization, openBrowser)
}

/** A short-lived loopback server; injectable ephemeral ports/timeouts keep tests isolated. */
internal class SpotifyLoopbackServer(
    private val port: Int = 43824,
    private val timeoutMs: Long = TimeUnit.MINUTES.toMillis(5),
) {
    suspend fun authorize(
        createAuthorization: (String) -> SpotifyAuthorization,
        openBrowser: (String) -> Unit,
    ): String {
        var opened: ServerSocket? = null
        try {
            val server = withContext(Dispatchers.IO) {
                ServerSocket().also { socket ->
                    opened = socket
                    try {
                        socket.reuseAddress = false
                        socket.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 4)
                        socket.soTimeout = POLL_MS
                    } catch (failure: Exception) {
                        socket.close()
                        if (failure is BindException) throw IOException("Spotify sign-in is already open, or its local port is busy. Close the other sign-in and try again.", failure)
                        throw failure
                    }
                }
            }
            val redirect = "http://127.0.0.1:${server.localPort}$PATH"
            val authorization = createAuthorization(redirect)
            require(authorization.state.isNotBlank() && authorization.state.length <= 512) { "Spotify sign-in did not supply a valid state." }
            // Retain the caller's dispatcher for the platform browser action.
            openBrowser(authorization.url)
            return withContext(Dispatchers.IO) {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() >= deadline) throw IOException("Spotify sign-in timed out. Return to Troit and try connecting again.")
                    val connection = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    connection.use { socket ->
                        socket.soTimeout = POLL_MS
                        val callback = try { readCallback(socket, server.localPort, authorization.state, deadline) }
                        catch (_: SocketTimeoutException) { null }
                        catch (_: IOException) { null }
                        if (callback != null) return@withContext "$redirect?$callback"
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("Unreachable")
            }
        } finally {
            opened?.close()
        }
    }

    private suspend fun readCallback(socket: Socket, localPort: Int, state: String, deadline: Long): String? {
        val input = socket.getInputStream().buffered()
        val bytes = ByteArray(MAX_HEADERS)
        var count = 0
        val clientDeadline = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(3))
        while (count < bytes.size) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() >= clientDeadline) return null
            val next = input.read()
            if (next == -1) return null
            bytes[count++] = next.toByte()
            if (count >= 4 && bytes[count - 4] == 13.toByte() && bytes[count - 3] == 10.toByte() &&
                bytes[count - 2] == 13.toByte() && bytes[count - 1] == 10.toByte()) break
        }
        if (count == bytes.size) { reply(socket, 431, "Request headers are too large."); return null }
        val lines = String(bytes, 0, count, Charsets.ISO_8859_1).split("\r\n")
        val request = lines.firstOrNull()?.split(' ') ?: emptyList()
        if (request.size != 3 || request[0] != "GET" || request[2] != "HTTP/1.1") {
            reply(socket, 405, "This sign-in callback accepts browser GET requests only."); return null
        }
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1).filter(String::isNotEmpty)) {
            val colon = line.indexOf(':')
            if (colon <= 0) { reply(socket, 400, "Invalid browser request."); return null }
            val name = line.substring(0, colon).lowercase(Locale.ROOT)
            if (headers.put(name, line.substring(colon + 1).trim()) != null) {
                reply(socket, 400, "Invalid browser request."); return null
            }
        }
        if (headers["host"] != "127.0.0.1:$localPort" || headers.containsKey("transfer-encoding") ||
            headers["content-length"]?.let { it != "0" } == true) {
            reply(socket, 400, "This callback is available only on this device."); return null
        }
        val target = runCatching { URI(request[1]) }.getOrNull()
        if (target == null || target.isAbsolute || target.rawAuthority != null || target.rawPath != PATH || target.rawFragment != null) {
            reply(socket, 404, "Return to the Spotify sign-in page to continue."); return null
        }
        val query = target.rawQuery
        val params = query?.let(::parameters)
        val returnedState = params?.get("state")
        val expected = state.toByteArray(Charsets.UTF_8)
        if (params == null || returnedState == null || !MessageDigest.isEqual(expected, returnedState.toByteArray(Charsets.UTF_8)) ||
            (params.containsKey("code") == params.containsKey("error")) ||
            params["code"]?.let { it.isBlank() || it.length > 4096 } == true ||
            params["error"]?.let { it.isBlank() || it.length > 256 } == true) {
            reply(socket, 400, "This sign-in response could not be matched. Continue from the Spotify sign-in page."); return null
        }
        // A browser closing its tab after the redirect must not discard a valid callback.
        runCatching { reply(socket, 200, if (params.containsKey("error")) "Spotify sign-in was canceled. You can close this window and return to Troit."
            else "Spotify sign-in response received. You can close this window and return to Troit.") }
        return query
    }

    private fun parameters(query: String): Map<String, String>? = runCatching {
        val values = linkedMapOf<String, String>()
        val pairs = query.split('&')
        require(pairs.size <= 10)
        for (pair in pairs) {
            val parts = pair.split('=', limit = 2)
            require(parts.size == 2)
            val key = URLDecoder.decode(parts[0], "UTF-8")
            val value = URLDecoder.decode(parts[1], "UTF-8")
            require(values.put(key, value) == null)
        }
        values
    }.getOrNull()

    private fun reply(socket: Socket, status: Int, message: String) {
        val body = "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Troit Spotify sign-in</title><body><h1>Troit</h1><p>$message</p></body></html>".toByteArray(Charsets.UTF_8)
        val label = when (status) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 431 -> "Request Header Fields Too Large"; else -> "Bad Request" }
        val headers = "HTTP/1.1 $status $label\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nPragma: no-cache\r\nContent-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    companion object {
        private const val PATH = "/spotify/callback"
        private const val MAX_HEADERS = 8192
        private const val POLL_MS = 250
    }
}
