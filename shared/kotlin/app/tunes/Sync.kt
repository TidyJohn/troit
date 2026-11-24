package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.TimeUnit

// The publishable key is meant to ship in apps: it can do nothing beyond what the row level security in
// supabase/migrations allows. Never put the secret key here, anyone can extract it from an APK or EXE.
private const val BASE = "https://cabbqhpxdxqzqqkbfpve.supabase.co"
private const val KEY = "sb_publishable_mbD8p70XPyO2ra0TRKTvqA_wz2qtrac"

class AccountApiException(val status: Int, val code: String, override val message: String) : IOException(message) {
    val setupRequired get() = status == 404 && code in setOf("PGRST202", "PGRST205", "42P01", "42883")
}

/** What another device was last playing. */
data class RemotePlayback(
    val queue: List<Song>,
    val index: Int,
    val positionMs: Long,
    val device: String,
    val deviceName: String,
    val updatedAt: Long,
)

/**
 * Keeps the library and "what's playing" the same on every device signed in to one account.
 * ponytail: polls every 30 s and the newest edit to a playlist wins, using each device's clock.
 * Add Supabase Realtime if changes must appear instantly.
 */
class Sync(
    dir: File,
    private val deviceName: String,
    private val library: Library,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient? = null,
) {
    internal val dataDirectory: File = dir
    private class Session(val access: String, val refresh: String, val userId: String, val email: String)
    private class SessionLocks(val changes: Any = Any(), val renewal: Any = Any()) {
        @Volatile var generation = 0L
        @Volatile var activeOwner: String? = null
    }

    private val sessionFile = File(dir, "session.json")
    private val sessionKey = sessionFile.absoluteFile.normalize().path
    private val sessionLocks = synchronized(sessionMonitors) {
        sessionMonitors.entries.removeAll { it.value.get() == null }
        sessionMonitors[sessionKey]?.get() ?: SessionLocks().also { sessionMonitors[sessionKey] = WeakReference(it) }
    }
    val deviceId = synchronized(sessionLocks.changes) { File(dir, "device.txt").let { file ->
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { file.writeAtomically(it) }
    } }
    private val json = "application/json; charset=utf-8".toMediaType()
    private val mutex = Mutex()
    private val sessionChanges = sessionLocks.changes
    // Shared by Activity and background transports; reading it never performs file or network I/O.
    internal val accountGeneration: Long get() = sessionLocks.generation
    internal val isCurrentAccount: Boolean get() = sessionLocks.activeOwner == userId
    @Volatile private var accountEpoch = 0L
    private val accountHttp by lazy {
        (httpClient ?: Yt.http).newBuilder().callTimeout(25, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    @Volatile private var session: Session? = synchronized(sessionChanges) {
        loadSession().also { sessionLocks.activeOwner = it?.userId }
    }
    private var lastLocalPlay = 0L
    private var dismissed = 0L

    var email by mutableStateOf(session?.email)
        private set
    var userId by mutableStateOf(session?.userId)
        private set
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var handoff by mutableStateOf<RemotePlayback?>(null)
        private set

    init {
        library.onChange = { poke() }
    }

    fun poke() {
        scope.launch { syncNow() }
    }

    fun dismissHandoff() {
        dismissed = handoff?.updatedAt ?: dismissed
        handoff = null
    }

    // ---------- Account ----------

    fun signIn(address: String, password: String) = account { epoch ->
        adopt(auth("token?grant_type=password", credentials(address, password)), epoch)
        "Signed in."
    }

    fun signUp(address: String, password: String) = account { epoch ->
        val response = auth("signup", credentials(address, password))
        if (response.has("access_token")) {
            adopt(response, epoch)
            "Account created."
        } else {
            "Check your email and open the confirmation link, then come back and sign in."
        }
    }

    /** Adopt only a fresh, independently issued TV session while the original pairing is still open. */
    internal suspend fun acceptPairedSession(reply: JSONObject, expectedGeneration: Long, allowed: () -> Boolean = { true }): Boolean {
        if (!allowed() || userId != null || accountGeneration != expectedGeneration) return false
        val access = reply.getString("access_token")
        val refresh = reply.getString("refresh_token")
        require(access.isNotBlank() && access.length <= 16_384 && refresh.isNotBlank() && refresh.length <= 16_384)
        val verifiedUser = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$BASE/auth/v1/user").header("apikey", KEY)
                .header("Authorization", "Bearer $access").get().build()
            accountHttp.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "The TV sign-in session could not be verified. Start pairing again." }
                JSONObject(boundedBody(response, 131_072L))
            }
        }
        UUID.fromString(verifiedUser.getString("id"))
        reply.optJSONObject("user")?.optString("id")?.takeIf { it.isNotEmpty() }?.let {
            check(it == verifiedUser.getString("id")) { "The TV sign-in session belongs to a different account." }
        }
        val accepted = synchronized(sessionChanges) {
            if (!allowed() || session != null || accountGeneration != expectedGeneration) false
            else {
                sessionLocks.generation++
                val epoch = ++accountEpoch
                adopt(JSONObject().put("access_token", access).put("refresh_token", refresh).put("user", verifiedUser), epoch)
                status = "TV connected."
                true
            }
        }
        if (accepted) poke()
        return accepted
    }

    /** Signing out only stops syncing. Playlists on this device stay. */
    fun signOut() {
        val old = synchronized(sessionChanges) {
            sessionLocks.generation++
            accountEpoch++
            val previous = session
            session = null
            sessionLocks.activeOwner = null
            email = null
            userId = null
            busy = false
            sessionFile.delete()
            previous
        }
        handoff = null
        lastLocalPlay = 0L
        dismissed = 0L
        status = ""
        if (old != null) {
            scope.launch {
                io {
                    val request = Request.Builder().url("$BASE/auth/v1/logout")
                        .header("apikey", KEY)
                        .header("Authorization", "Bearer ${old.access}")
                        .post(ByteArray(0).toRequestBody())
                        .build()
                    accountHttp.newCall(request).execute().close()
                }
            }
        }
    }

    private fun account(work: (Long) -> String) {
        if (busy) return
        val epoch = synchronized(sessionChanges) { sessionLocks.generation++; ++accountEpoch }
        busy = true
        scope.launch {
            val result = io { work(epoch) }
            if (epoch != accountEpoch) return@launch
            status = result.getOrElse { it.message ?: "Something went wrong." }
            email = session?.email
            busy = false
            if (result.isSuccess) syncNow()
        }
    }

    private fun credentials(address: String, password: String) =
        JSONObject().put("email", address.trim()).put("password", password)

    private fun auth(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url("$BASE/auth/v1/$path")
            .header("apikey", KEY)
            .post(body.toString().toRequestBody(json))
            .build()
        accountHttp.newCall(request).execute().use { response ->
            val reply = runCatching { JSONObject(boundedBody(response)) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) {
                error(
                    listOf("msg", "error_description", "message").map(reply::optString).firstOrNull { it.isNotEmpty() }
                        ?: "The server refused this (HTTP ${response.code}).",
                )
            }
            return reply
        }
    }

    private fun adopt(reply: JSONObject, expectedEpoch: Long = accountEpoch, expectedSession: Session? = null): Session {
        val user = reply.getJSONObject("user")
        val fresh = Session(
            reply.getString("access_token"),
            reply.getString("refresh_token"),
            user.getString("id"),
            user.optString("email"),
        )
        synchronized(sessionChanges) {
        if (expectedEpoch != accountEpoch) throw IOException("The account changed. Please try again.")
        if (expectedSession != null) {
            val stored = loadSession()
            if (stored?.userId != expectedSession.userId || stored.access != expectedSession.access || fresh.userId != expectedSession.userId) {
                throw IOException("The account changed. Please try again.")
            }
        }
        sessionFile.writeAtomically(
            JSONObject()
                .put("access", fresh.access)
                .put("refresh", fresh.refresh)
                .put("userId", fresh.userId)
                .put("email", fresh.email)
                .toString(),
        )
        if (session?.userId != fresh.userId) {
            handoff = null
            lastLocalPlay = 0L
            dismissed = 0L
        }
        session = fresh
        sessionLocks.activeOwner = fresh.userId
        userId = fresh.userId
        email = fresh.email
        }
        return fresh
    }

    private fun loadSession(): Session? = runCatching {
        val saved = JSONObject(sessionFile.readText())
        Session(saved.getString("access"), saved.getString("refresh"), saved.getString("userId"), saved.getString("email"))
    }.getOrNull()

    // ---------- Data ----------

    /** Sends [call] with the current login, renewing it once if it has expired. */
    private fun rest(path: String, maximumBodyBytes: Long = 16_000_000L, call: Request.Builder.() -> Request.Builder): String {
        val epoch = accountEpoch
        fun send(current: Session): Response = accountHttp.newCall(
            Request.Builder().url("$BASE/rest/v1/$path")
                .header("apikey", KEY)
                .header("Authorization", "Bearer ${current.access}")
                .call()
                .build(),
        ).execute()

        val current = session ?: error("Not signed in")
        fun ensureAccount() {
            if (epoch != accountEpoch || session?.userId != current.userId || loadSession()?.userId != current.userId) throw IOException("The account changed. Please try again.")
        }
        ensureAccount()
        var response = send(current)
        if (response.code == 401) {
            response.close()
            val renewed = try {
                synchronized(sessionLocks.renewal) {
                    ensureAccount()
                    val persisted = synchronized(sessionChanges) {
                        loadSession()?.takeIf { it.userId == current.userId && it.access != current.access }?.also {
                            session = it
                        }
                    }
                    persisted ?: adopt(auth("token?grant_type=refresh_token", JSONObject().put("refresh_token", current.refresh)), epoch, current)
                }
            } catch (e: Exception) {
                error("Your login expired. Please sign in again.")
            }
            ensureAccount()
            response = send(renewed)
        }
        response.use {
            val text = boundedBody(it, maximumBodyBytes)
            ensureAccount()
            if (!it.isSuccessful) {
                val error = runCatching { JSONObject(text) }.getOrNull()
                throw AccountApiException(it.code, error?.optString("code").orEmpty(), error?.optString("message")?.take(240)?.takeIf(String::isNotBlank) ?: "The request failed (HTTP ${it.code}).")
            }
            return text
        }
    }

    /** Shared authenticated transport; callers never receive tokens or make requests on the UI thread. */
    suspend fun accountRequest(path: String, method: String = "GET", body: String? = null, prefer: String? = null,
        maximumRequestBytes: Int = 256_000): String = mutex.withLock {
        require(path.length <= 2048 && path.matches(Regex("[a-z][a-z0-9_]*(?:/[a-z][a-z0-9_]*)?(?:\\?[^\\s#]*)?"))) { "Invalid account endpoint" }
        val collaborative = path.substringBefore('?').startsWith("rpc/troit_collab_")
        val queueTransport = path.substringBefore('?').startsWith("rpc/troit_ecosystem_")
        require(maximumRequestBytes in 1..if (collaborative || queueTransport) 2_000_000 else 256_000) { "The request limit is too large" }
        require(body == null || body.toByteArray(Charsets.UTF_8).size <= maximumRequestBytes) { "The request is too large" }
        val owner = userId ?: throw IOException("Sign in to use this feature.")
        val generation = accountGeneration
        check(isCurrentAccount) { "The account changed. Please refresh this connection." }
        val epoch = accountEpoch
        val result = withContext(Dispatchers.IO) {
            rest(path, if (collaborative || queueTransport) 4_000_000L else 2_000_000L) {
                prefer?.let { header("Prefer", it) }
                when (method.uppercase()) {
                    "GET" -> get()
                    "POST" -> post((body ?: "{}").toRequestBody(json))
                    "PATCH" -> patch((body ?: "{}").toRequestBody(json))
                    "DELETE" -> delete()
                    else -> throw IllegalArgumentException("Unsupported account operation")
                }
            }
        }
        if (epoch != accountEpoch || userId != owner || generation != accountGeneration || !isCurrentAccount)
            throw IOException("The account changed. Please try again.")
        result
    }

    private fun boundedBody(response: Response, maximumBytes: Long = 2_000_000L): String {
        val source = response.body?.source() ?: throw IOException("The server returned an empty response.")
        source.request(maximumBytes + 1)
        if (source.buffer.size > maximumBytes) throw IOException("The server response was too large. Please try again.")
        return source.readUtf8()
    }

    private fun upsert(table: String, conflict: String, body: String) {
        rest("$table?on_conflict=$conflict") {
            header("Prefer", "resolution=merge-duplicates,return=minimal").post(body.toRequestBody(json))
        }
    }

    suspend fun syncNow() = mutex.withLock {
        val current = session ?: return@withLock
        val remote = io { fetchPlaylists() }.getOrElse {
            status = it.message ?: "Couldn't reach the server."
            return@withLock
        }
        val merged = merge(library.all.toList(), remote)
        try { library.replaceAll(merged.all) } catch (_: Exception) {
            status = "Couldn't save synced playlists on this device. Please try again."
            return@withLock
        }
        val pushed = if (merged.push.isEmpty()) Result.success(Unit) else io { pushPlaylists(merged.push, current.userId) }
        val playback = io { fetchPlayback() }
        status = if (pushed.isSuccess && playback.isSuccess) "Up to date." else "Couldn't finish syncing. Will try again."
        playback.getOrNull()?.let { latest ->
            val isNewer = latest.updatedAt > maxOf(lastLocalPlay, dismissed)
            handoff = latest.takeIf { it.device != deviceId && isNewer && it.queue.isNotEmpty() }
        }
    }

    /** Failures are ignored: the next report carries newer information anyway. */
    suspend fun pushPlayback(now: NowPlaying, positionMs: Long) = mutex.withLock {
        val current = session ?: return@withLock
        lastLocalPlay = System.currentTimeMillis()
        handoff = null
        val row = JSONObject()
            .put("user_id", current.userId)
            .put("queue", Library.songsToJson(now.queue))
            .put("idx", now.index)
            .put("position_ms", positionMs)
            .put("device", deviceId)
            .put("device_name", deviceName)
            .put("updated_at", lastLocalPlay)
        io { upsert("tunes_playback", "user_id", JSONArray().put(row).toString()) }
        Unit
    }

    private fun fetchPlaylists(): List<SavedPlaylist> {
        val rows = JSONArray(rest("tunes_playlists?select=id,title,songs,updated_at,deleted") { get() })
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            SavedPlaylist(
                title = row.getString("title"),
                songs = Library.songsFromJson(row.getJSONArray("songs")),
                id = row.getString("id"),
                updatedAt = row.getLong("updated_at"),
                deleted = row.getBoolean("deleted"),
            )
        }
    }

    private fun pushPlaylists(playlists: List<SavedPlaylist>, userId: String) {
        val rows = JSONArray()
        for (playlist in playlists) {
            rows.put(
                JSONObject()
                    .put("user_id", userId)
                    .put("id", playlist.id)
                    .put("title", playlist.title)
                    .put("songs", Library.songsToJson(playlist.songs))
                    .put("updated_at", playlist.updatedAt)
                    .put("deleted", playlist.deleted),
            )
        }
        upsert("tunes_playlists", "user_id,id", rows.toString())
    }

    private fun fetchPlayback(): RemotePlayback? {
        val rows = JSONArray(rest("tunes_playback?select=queue,idx,position_ms,device,device_name,updated_at") { get() })
        val row = rows.optJSONObject(0) ?: return null
        val queue = Library.songsFromJson(row.getJSONArray("queue"))
        return RemotePlayback(
            queue,
            row.getInt("idx").coerceIn(0, maxOf(queue.lastIndex, 0)),
            row.getLong("position_ms").coerceAtLeast(0),
            row.getString("device"),
            row.getString("device_name"),
            row.getLong("updated_at"),
        )
    }

    class Merged(val all: List<SavedPlaylist>, val push: List<SavedPlaylist>)

    companion object {
        internal val backendUrl: String get() = BASE
        internal val publishableKey: String get() = KEY
        // Widget workers and the Activity share credentials without blocking sign-out on network I/O.
        private val sessionMonitors = HashMap<String, WeakReference<SessionLocks>>()
        /** The newest version of each playlist wins. [Merged.push] is what the server doesn't have yet. */
        internal fun merge(local: List<SavedPlaylist>, remote: List<SavedPlaylist>): Merged {
            val remoteById = remote.associateBy { it.id }
            val localIds = local.map { it.id }.toSet()
            val keptLocal = local.map { mine ->
                val theirs = remoteById[mine.id]
                if (theirs != null && theirs.updatedAt > mine.updatedAt) theirs else mine
            }
            val push = keptLocal.filter { kept ->
                val theirs = remoteById[kept.id]
                theirs == null || kept.updatedAt > theirs.updatedAt
            }
            return Merged(keptLocal + remote.filter { it.id !in localIds }, push)
        }
    }
}
