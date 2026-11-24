package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SpotifyAuthorization(val url: String, val state: String)

/** Public-client PKCE: no client secret is required, embedded, logged, or sent to the music provider. */
class SpotifyAccount(
    dataDir: File,
    http: OkHttpClient = Yt.http,
    private val accountsBase: HttpUrl = "https://accounts.spotify.com/".toHttpUrl(),
    private val apiBase: HttpUrl = "https://api.spotify.com/v1/".toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Session(val access: String, val refresh: String, val expiresAt: Long, val client: String)
    private data class Pending(val verifier: String, val state: String, val redirect: HttpUrl, val client: String, val at: Long, val generation: Long)
    private val sessionFile = File(dataDir, "spotify-session.json")
    private val settingsFile = File(dataDir, "spotify-client.txt")
    private val mutex = Mutex()
    private val stateLock = Any()
    private var generation = 0L
    private var pending: Pending? = null
    @Volatile private var session: Session? = runCatching {
        val row = JSONObject(sessionFile.readText())
        Session(row.getString("access"), row.getString("refresh"), row.getLong("expires"), row.getString("client"))
    }.getOrNull()
    private var isConnected by mutableStateOf(session != null)
    val connected get() = isConnected
    var clientId by mutableStateOf(runCatching { settingsFile.readText().trim() }.getOrDefault(session?.client.orEmpty()))
        private set
    var displayName by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    private val http = http.newBuilder().callTimeout(25, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    fun beginAuthorization(clientId: String, redirectUri: String): SpotifyAuthorization = synchronized(stateLock) {
        val id = clientId.trim()
        require(id.matches(Regex("[a-fA-F0-9]{32}"))) { "Enter the 32-character Client ID from your Spotify developer app." }
        val redirect = redirectUri.toHttpUrlOrNull() ?: error("Invalid Spotify redirect URI.")
        require(redirect.scheme == "http" && redirect.host in setOf("127.0.0.1", "::1") &&
            redirect.query == null && redirect.fragment == null && redirect.username.isEmpty() && redirect.password.isEmpty()) {
            "Spotify sign-in requires an explicit loopback redirect address."
        }
        generation++
        val verifier = random(64)
        val state = random(32)
        pending = Pending(verifier, state, redirect, id, clock(), generation)
        this.clientId = id
        settingsFile.writeAtomically(id)
        busy = true
        status = "Complete Spotify sign-in in your browser."
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val url = accountsBase.resolve("authorize")!!.newBuilder()
            .addQueryParameter("client_id", id).addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirect.toString()).addQueryParameter("state", state)
            .addQueryParameter("code_challenge_method", "S256").addQueryParameter("code_challenge", challenge)
            .addQueryParameter("scope", "playlist-read-private playlist-read-collaborative").build()
        SpotifyAuthorization(url.toString(), state)
    }

    suspend fun completeAuthorization(callbackUrl: String) {
        val attempt = synchronized(stateLock) { pending } ?: error("Start Spotify sign-in again.")
        try {
            val callback = callbackUrl.toHttpUrlOrNull() ?: error("Invalid Spotify callback.")
            require(callback.scheme == attempt.redirect.scheme && callback.host == attempt.redirect.host &&
                callback.port == attempt.redirect.port && callback.encodedPath == attempt.redirect.encodedPath &&
                callback.fragment == null && callback.username.isEmpty() && callback.password.isEmpty()) { "Unexpected Spotify redirect address." }
            require(callback.queryParameterValues("state").size == 1 && callback.queryParameter("state") == attempt.state) { "Spotify sign-in state did not match. Start again." }
            require(clock() - attempt.at in 0..10 * 60_000L) { "Spotify sign-in expired. Start again." }
            callback.queryParameter("error")?.let { error("Spotify sign-in was not approved.") }
            require(callback.queryParameterValues("code").size == 1) { "Spotify returned an invalid authorization code." }
            val code = callback.queryParameter("code")?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: error("Spotify did not return an authorization code.")
            synchronized(stateLock) {
                require(pending === attempt && generation == attempt.generation) { "Spotify sign-in was canceled." }
                pending = null // A callback is single-use, including concurrent duplicate requests.
            }
            val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
                .add("redirect_uri", attempt.redirect.toString()).add("client_id", attempt.client)
                .add("code_verifier", attempt.verifier).build()
            val result = execute(Request.Builder().url(accountsBase.resolve("api/token")!!).post(body).build())
            val adopted = decodeSession(result, attempt.client, null)
            synchronized(stateLock) {
                if (generation != attempt.generation) return
                persist(adopted)
                session = adopted; isConnected = true
                status = "Spotify connected. You can import up to 1,000 tracks."
            }
        } catch (cancelled: CancellationException) {
            synchronized(stateLock) { if (generation == attempt.generation) status = "Spotify sign-in canceled." }
            throw cancelled
        } catch (failure: Exception) {
            synchronized(stateLock) { if (generation == attempt.generation) status = failure.message?.take(240) ?: "Spotify sign-in failed." }
            throw failure
        } finally {
            synchronized(stateLock) { if (generation == attempt.generation) { pending = null; busy = false } }
        }
    }

    fun cancelAuthorization() = synchronized(stateLock) {
        if (pending == null && !busy) return@synchronized
        generation++; pending = null; busy = false
        status = "Spotify sign-in canceled."
    }

    fun disconnect() = synchronized(stateLock) {
        generation++; pending = null; session = null; isConnected = false; displayName = null; busy = false
        sessionFile.delete()
        status = "Spotify disconnected on this device."
    }

    internal suspend fun tracks(type: String, id: String, limit: Int = Spotify.IMPORT_LIMIT): SpotifyImportSource {
        require(type in setOf("track", "album", "playlist") && id.matches(Regex("[A-Za-z0-9]{22}"))) { "Invalid Spotify link." }
        val cap = limit.coerceIn(1, Spotify.IMPORT_LIMIT)
        val revision = synchronized(stateLock) { generation }
        fun current() = synchronized(stateLock) { check(revision == generation && session != null) { "Spotify account changed during import." } }
        if (type == "track") {
            val item = get("tracks/$id")
            current()
            return SpotifyImportSource(listOfNotNull(decodeTrack(item)))
        }
        val path = if (type == "playlist") "playlists/$id/items" else "albums/$id/tracks"
        val tracks = mutableListOf<SpotifyTrack>()
        var offset = 0
        var total = 0
        var skipped = 0
        do {
            current()
            val requested = minOf(50, cap - offset)
            val page = get("$path?limit=$requested&offset=$offset")
            current()
            val rows = page.optJSONArray("items") ?: error("Spotify returned an invalid track page.")
            require(rows.length() <= requested) { "Spotify returned an oversized track page." }
            total = page.optInt("total", offset + rows.length()).coerceAtLeast(0)
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index)
                val item = if (type == "playlist") row?.optJSONObject("item") ?: row?.optJSONObject("track") else row
                val track = if (row?.optBoolean("is_local") == true) null else item?.let(::decodeTrack)
                if (track != null) tracks += track else skipped++
            }
            offset += rows.length()
            if (rows.length() == 0 || page.isNull("next") || page.optString("next").isBlank()) break
        } while (offset < cap && offset < total)
        val warnings = buildList {
            if (total > cap) add("Only the first $cap of $total Spotify items are included in one import.")
            if (skipped > 0) add("Skipped $skipped unavailable, local, or non-song Spotify items.")
        }
        return SpotifyImportSource(tracks, warnings)
    }

    private suspend fun get(path: String): JSONObject {
        val revision = synchronized(stateLock) { generation }
        val credential = validSession()
        fun request(access: String) = Request.Builder().url(apiBase.resolve(path)!!).header("Authorization", "Bearer $access").build()
        val result = try { execute(request(credential.access)) }
        catch (failure: SpotifyHttpException) {
            if (failure.code != 401) throw failure
            val refreshed = validSession(force = credential.access)
            execute(request(refreshed.access))
        }
        synchronized(stateLock) { check(revision == generation && session != null) { "Spotify account changed during import." } }
        return result
    }

    private suspend fun validSession(force: String? = null): Session = mutex.withLock {
        val original = session ?: error("Connect Spotify before importing a full playlist.")
        if ((force == null && original.expiresAt > clock() + 60_000) || (force != null && original.access != force)) return@withLock original
        val revision = synchronized(stateLock) { generation }
        val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", original.refresh)
            .add("client_id", original.client).build()
        val result = execute(Request.Builder().url(accountsBase.resolve("api/token")!!).post(body).build())
        val renewed = decodeSession(result, original.client, original.refresh)
        synchronized(stateLock) {
            check(revision == generation && session === original) { "Spotify account changed during import." }
            persist(renewed); session = renewed
        }
        renewed
    }

    private fun decodeSession(json: JSONObject, client: String, previousRefresh: String?): Session {
        val access = json.getString("access_token").also { require(it.isNotBlank() && it.length <= 16_384) }
        val refresh = json.optString("refresh_token").takeIf(String::isNotBlank) ?: previousRefresh ?: error("Spotify did not return a refresh token. Sign in again.")
        require(refresh.length <= 16_384)
        val type = json.optString("token_type", "Bearer")
        require(type.equals("Bearer", true)) { "Spotify returned an unsupported token type." }
        return Session(access, refresh, clock() + json.optLong("expires_in", 3600).coerceIn(60, 86_400) * 1000, client)
    }

    private fun persist(value: Session) = sessionFile.writeAtomically(JSONObject().put("access", value.access)
        .put("refresh", value.refresh).put("expires", value.expiresAt).put("client", value.client).toString())

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val source = it.body?.source() ?: throw IOException("Spotify returned an empty response.")
                        source.request(2_000_001)
                        if (source.buffer.size > 2_000_000) throw IOException("Spotify returned too much data.")
                        val text = source.readUtf8()
                        if (!it.isSuccessful) throw SpotifyHttpException(it.code, when (it.code) {
                            400 -> "Spotify rejected authorization. Check the Client ID, registered redirect URI, and developer-app user access, then reconnect."
                            401 -> "Spotify authorization expired. Connect Spotify again."
                            403 -> "Spotify permits full playlist imports only when your account owns or collaborates on the playlist. Check your developer-app access too."
                            429 -> "Spotify is rate limiting requests. Wait and resume the import."
                            else -> "Spotify returned HTTP ${it.code}. Please try again."
                        })
                        val json = JSONObject(text)
                        if (continuation.isActive) continuation.resume(json)
                    }
                } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        })
    }

    companion object {
        private fun random(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes))
        private fun decodeTrack(item: JSONObject): SpotifyTrack? {
            if (item.optString("type", "track") != "track" || item.optBoolean("is_local") || item.optBoolean("is_playable", true).not()) return null
            val title = item.optString("name").takeIf(String::isNotBlank) ?: return null
            val artists = item.optJSONArray("artists") ?: return null
            val names = (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            return if (names.isEmpty()) null else SpotifyTrack(title, names, item.optLong("duration_ms"))
        }
    }
}

private class SpotifyHttpException(val code: Int, message: String) : IOException(message)
