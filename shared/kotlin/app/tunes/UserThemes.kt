package app.tunes

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Immutable
data class ThemeSettings(val themeId: String = "monochrome", val accentId: String = "neutral") {
    internal fun normalized() = ThemeSettings(
        themeId.takeIf { it in ThemeCatalog.themeIds } ?: "monochrome",
        accentId.takeIf { it in ThemeCatalog.accentIds } ?: "neutral",
    )
}

data class ThemeChoice(val id: String, val title: String, val description: String)

object ThemeCatalog {
    val themes = listOf(
        ThemeChoice("monochrome", "Monochrome", "Pure black. Clear white. The Troit original."),
        ThemeChoice("midnight", "Midnight", "Deep charcoal with a quieter blue undertone."),
        ThemeChoice("paper", "Paper", "A light canvas for daytime listening."),
        ThemeChoice("rose", "Rose", "Soft rose accents over a dark backdrop."),
        ThemeChoice("ocean", "Ocean", "Cool blues and deep, calm surfaces."),
    )
    val accents = listOf(
        ThemeChoice("neutral", "Original", "Use this theme's original accent"),
        ThemeChoice("coral", "Coral", "Warm coral"),
        ThemeChoice("mint", "Mint", "Fresh mint"),
        ThemeChoice("blue", "Blue", "Clear blue"),
        ThemeChoice("violet", "Violet", "Soft violet"),
    )
    val themeIds = themes.map { it.id }.toSet()
    val accentIds = accents.map { it.id }.toSet()
}

internal data class StoredTheme(
    val settings: ThemeSettings = ThemeSettings(),
    val revision: Long = 0,
    val dirty: Boolean = false,
    val editedAt: Long = 0,
)

/** Bounded local preferences. Guest and every account have separate entries. */
internal class LocalThemeStore(private val file: File) {
    private val entries = LinkedHashMap<String, StoredTheme>()

    init {
        runCatching {
            if (!file.isFile || file.length() > MAX_BYTES) return@runCatching
            val json = JSONObject(file.readText())
            json.keys().asSequence().take(MAX_ACCOUNTS).forEach { key ->
                if (validOwner(key)) json.optJSONObject(key)?.let { row ->
                    entries[key] = StoredTheme(
                        settings = ThemeSettings(row.optString("theme_id"), row.optString("accent_id")).normalized(),
                        revision = row.optLong("revision").coerceAtLeast(0),
                        dirty = row.optBoolean("dirty"),
                        editedAt = row.optLong("edited_at").coerceAtLeast(0),
                    )
                }
            }
        }
    }

    fun get(userId: String?) = entries[owner(userId)] ?: StoredTheme()

    fun put(userId: String?, entry: StoredTheme) {
        val key = owner(userId)
        entries[key] = entry.copy(settings = entry.settings.normalized())
        while (entries.size > MAX_ACCOUNTS) {
            val oldest = entries.filterKeys { it != key }.minByOrNull { it.value.editedAt }?.key ?: break
            entries.remove(oldest)
        }
    }

    fun encode(): String = JSONObject().apply {
        entries.forEach { (key, value) ->
            put(key, JSONObject()
                .put("theme_id", value.settings.themeId).put("accent_id", value.settings.accentId)
                .put("revision", value.revision).put("dirty", value.dirty).put("edited_at", value.editedAt))
        }
    }.toString()

    fun save(encoded: String) {
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        file.parentFile?.mkdirs()
        file.writeAtomically(encoded)
    }

    companion object {
        const val MAX_ACCOUNTS = 32
        const val MAX_BYTES = 65_536
        private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private fun validOwner(value: String) = value == "guest" || uuid.matches(value)
        private fun owner(userId: String?) = userId?.takeIf { uuid.matches(it) } ?: "guest"
    }
}

/**
 * Theme mutations are immediate locally. Cloud updates are coalesced, account-guarded and serialized;
 * a newer setting from another device is presented as a choice rather than overwritten silently.
 */
class UserThemeState internal constructor(
    dataDir: File,
    private val scope: CoroutineScope,
    private val accountId: () -> String?,
    private val request: suspend (path: String, method: String, body: String?, prefer: String?) -> String,
) {
    constructor(dataDir: File, sync: Sync, scope: CoroutineScope) : this(
        dataDir, scope, { sync.userId }, { path, method, body, prefer -> sync.accountRequest(path, method, body, prefer) },
    )

    private val local = LocalThemeStore(File(dataDir, "user-themes.json"))
    private val networkMutex = Mutex()
    private val writeMutex = Mutex()
    private var localVersion by mutableIntStateOf(0)
    private var editVersion = 0L
    private var mutationDue = 0L
    private var cloudJob: Job? = null
    private var localJob: Job? = null
    private var statusOwner: String? = null
    private var message by mutableStateOf("")
    private var syncingOwner by mutableStateOf<String?>(null)
    private var conflictOwner: String? = null
    private var remoteConflict by mutableStateOf<StoredTheme?>(null)
    private var lastFailed = false

    val settings: ThemeSettings get() { localVersion; return local.get(accountId()).settings }
    val signedIn get() = accountId() != null
    val syncing get() = signedIn && syncingOwner == accountId()
    val conflict get() = remoteConflict?.settings?.takeIf { conflictOwner == accountId() && signedIn }
    val pendingSync: Boolean get() { localVersion; return signedIn && local.get(accountId()).dirty }
    val status: String get() = if (statusOwner == accountId()) message else if (signedIn) "Your theme follows your account." else "Saved on this device. Sign in to sync your theme."

    fun selectTheme(id: String) {
        if (id in ThemeCatalog.themeIds) change(settings.copy(themeId = id))
    }

    fun selectAccent(id: String) {
        if (id in ThemeCatalog.accentIds) change(settings.copy(accentId = id))
    }

    fun reset() = change(ThemeSettings())

    private fun change(value: ThemeSettings) {
        val user = accountId()
        val previous = local.get(user)
        if (previous.settings == value.normalized()) return
        editVersion++
        put(user, previous.copy(settings = value.normalized(), dirty = user != null, editedAt = System.currentTimeMillis()))
        statusOwner = user
        message = if (user == null) "Saved on this device. Sign in to sync your theme." else "Saved on this device. Syncing your theme…"
        lastFailed = false
        mutationDue = System.currentTimeMillis() + 700
        if (user != null) scheduleCloudWrite()
    }

    private fun put(user: String?, value: StoredTheme) {
        local.put(user, value)
        localVersion++
        localJob?.cancel()
        localJob = scope.launch {
            delay(120)
            val snapshot = local.encode()
            writeMutex.withLock {
                try { withContext(Dispatchers.IO) { local.save(snapshot) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (accountId() == user) {
                        statusOwner = user
                        message = "Your theme is applied, but couldn't be saved on this device. Try again."
                    }
                }
            }
        }
    }

    private fun scheduleCloudWrite() {
        if (cloudJob?.isActive == true) return
        cloudJob = scope.launch {
            while (true) {
                delay((mutationDue - System.currentTimeMillis()).coerceAtLeast(100))
                if (System.currentTimeMillis() < mutationDue) continue
                refresh()
                if (!pendingSync || conflict != null || lastFailed || !signedIn) break
            }
        }
    }

    fun retry() { scope.launch { refresh() } }

    /** Call while the app is foregrounded, at most once every 30 seconds per account. */
    suspend fun refresh() {
        val user = accountId() ?: return
        if (!networkMutex.tryLock()) return
        syncingOwner = user
        try {
            val fetched = decodeCloud(request("rpc/troit_get_theme", "POST", "{}", null))
            if (accountId() != user) return
            val current = local.get(user)
            if (!current.dirty) {
                if (fetched != null && (fetched.settings != current.settings || fetched.revision != current.revision)) put(user, fetched)
                clearConflict(user)
                setStatus(user, "Theme synced across your devices.")
                lastFailed = false
                return
            }
            val remoteRevision = fetched?.revision ?: 0L
            if (current.revision != remoteRevision) {
                showConflict(user, fetched ?: StoredTheme())
                return
            }
            val sendingVersion = editVersion
            val body = JSONObject().put("p_theme_id", current.settings.themeId)
                .put("p_accent_id", current.settings.accentId).put("p_expected_revision", current.revision).toString()
            if (accountId() != user) return
            val saved = decodeCloud(request("rpc/troit_set_theme", "POST", body, null)) ?: error("No theme returned")
            if (accountId() != user) return
            val latest = local.get(user)
            put(user, if (sendingVersion == editVersion) saved else latest.copy(revision = saved.revision, dirty = true))
            clearConflict(user)
            lastFailed = false
            setStatus(user, if (local.get(user).dirty) "Saving your latest choice…" else "Theme synced across your devices.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (accountId() == user) {
                lastFailed = true
                val detail = error.message.orEmpty()
                if (detail.contains("Theme changed", true) || detail.contains("40001")) {
                    try {
                        val newest = decodeCloud(request("rpc/troit_get_theme", "POST", "{}", null))
                        if (accountId() == user && newest != null) showConflict(user, newest)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { setStatus(user, "Couldn't sync your theme. Your local choice is kept; try again when connected.") }
                } else setStatus(user, if (detail.contains("PGRST202", true) || detail.contains("schema cache", true) || detail.contains("does not exist", true))
                    "Cloud theme sync isn't ready yet. Your theme is kept on this device."
                else "Couldn't sync your theme. Your local choice is kept; try again when connected.")
            }
        } finally {
            if (syncingOwner == user) syncingOwner = null
            networkMutex.unlock()
        }
    }

    private fun setStatus(user: String?, value: String) { statusOwner = user; message = value }
    private fun clearConflict(user: String) { if (conflictOwner == user) { remoteConflict = null; conflictOwner = null } }
    private fun showConflict(user: String, value: StoredTheme) {
        conflictOwner = user
        remoteConflict = value
        setStatus(user, "This account has a newer theme from another device. Choose which one to keep.")
    }

    fun useCloudTheme() {
        val user = accountId() ?: return
        val chosen = remoteConflict?.takeIf { conflictOwner == user } ?: return
        put(user, chosen)
        clearConflict(user)
        lastFailed = false
        setStatus(user, "Using your other device's theme.")
    }

    fun keepLocalTheme() {
        val user = accountId() ?: return
        val newer = remoteConflict?.takeIf { conflictOwner == user } ?: return
        put(user, local.get(user).copy(revision = newer.revision, dirty = true, editedAt = System.currentTimeMillis()))
        clearConflict(user)
        lastFailed = false
        mutationDue = System.currentTimeMillis() + 300
        scheduleCloudWrite()
    }

    internal fun localSnapshot() = local.encode()

    companion object {
        internal fun decodeCloud(text: String): StoredTheme? {
            if (text.isBlank() || text.trim() == "null") return null
            require(text.length <= 4096) { "Unexpected theme response" }
            val row = JSONObject(text)
            return StoredTheme(
                settings = ThemeSettings(row.optString("theme_id"), row.optString("accent_id")).normalized(),
                revision = row.getLong("revision").coerceAtLeast(0), dirty = false,
                editedAt = System.currentTimeMillis(),
            )
        }
    }
}
