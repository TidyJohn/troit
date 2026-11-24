package app.tunes

import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.min

class UserThemesTest {
    private val alice = "00000000-0000-0000-0000-000000000001"
    private val bob = "00000000-0000-0000-0000-000000000002"
    private fun dir() = Files.createTempDirectory("troit-theme-test").toFile()
    private fun cloud(settings: ThemeSettings, revision: Long) = JSONObject()
        .put("theme_id", settings.themeId).put("accent_id", settings.accentId).put("revision", revision).toString()

    @Test
    fun persistedThemesAreAccountScopedBoundedAndValidateSavedValues() {
        val file = File(dir(), "themes.json")
        val store = LocalThemeStore(file)
        store.put(alice, StoredTheme(ThemeSettings("rose", "mint"), 7, true, 1))
        store.put(bob, StoredTheme(ThemeSettings("paper", "blue"), 3, false, 2))
        store.put(null, StoredTheme(ThemeSettings("midnight", "neutral"), editedAt = 3))
        store.save(store.encode())
        val reopened = LocalThemeStore(file)
        assertEquals(ThemeSettings("rose", "mint"), reopened.get(alice).settings)
        assertEquals(ThemeSettings("paper", "blue"), reopened.get(bob).settings)
        assertEquals(ThemeSettings("midnight", "neutral"), reopened.get(null).settings)
        assertTrue(reopened.get(alice).dirty)
        assertEquals(ThemeSettings(), reopened.get("00000000-0000-0000-0000-000000000003").settings)
        repeat(40) { index -> reopened.put("00000000-0000-0000-0000-${(index + 100).toString().padStart(12, '0')}", StoredTheme(editedAt = 100L + index)) }
        assertTrue(JSONObject(reopened.encode()).length() <= LocalThemeStore.MAX_ACCOUNTS)
        file.writeText("""{"$alice":{"theme_id":"unknown","accent_id":"bad","revision":-9}}""")
        assertEquals(StoredTheme(), LocalThemeStore(file).get(alice))
        file.writeText("x".repeat(LocalThemeStore.MAX_BYTES + 1))
        assertEquals(StoredTheme(), LocalThemeStore(file).get(alice))
    }

    @Test
    fun accountSwitchImmediatelyUsesItsOwnThemeAndGuestIsSeparate() = runBlocking(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var user: String? = alice
        val state = UserThemeState(dir(), scope, { user }) { _, _, _, _ -> error("Offline") }
        try {
            state.selectTheme("rose")
            user = bob
            assertEquals(ThemeSettings(), state.settings)
            state.selectTheme("ocean")
            user = alice
            assertEquals("rose", state.settings.themeId)
            user = null
            assertEquals(ThemeSettings(), state.settings)
            state.selectAccent("violet")
            user = bob
            assertEquals(ThemeSettings("ocean", "neutral"), state.settings)
            user = null
            assertEquals(ThemeSettings("monochrome", "violet"), state.settings)
        } finally { scope.cancel() }
    }

    @Test
    fun offlineChoicePersistsAndSyncsOnceConnectionReturns() = runBlocking(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val directory = dir()
        var online = false
        var saved: String? = null
        var writes = 0
        val state = UserThemeState(directory, scope, { alice }) { path, _, body, _ ->
            check(online) { "Offline" }
            if (path.endsWith("troit_get_theme")) saved ?: "null"
            else {
                writes++
                val row = JSONObject(body!!)
                cloud(ThemeSettings(row.getString("p_theme_id"), row.getString("p_accent_id")), 1).also { saved = it }
            }
        }
        try {
            state.selectTheme("rose")
            state.selectAccent("mint")
            state.refresh()
            assertEquals(ThemeSettings("rose", "mint"), state.settings)
            assertTrue(state.pendingSync)
            delay(180)
            assertEquals(state.settings, LocalThemeStore(File(directory, "user-themes.json")).get(alice).settings)
            online = true
            state.refresh()
            assertEquals(1, writes)
            assertFalse(state.pendingSync)
            assertEquals("rose", JSONObject(saved!!).getString("theme_id"))
        } finally { scope.cancel() }
    }

    @Test
    fun themeChangeDuringAnInflightWriteIsNotLost() = runBlocking(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val started = CompletableDeferred<Unit>()
        var stored: String? = null
        var revision = 0L
        val state = UserThemeState(dir(), scope, { alice }) { path, _, body, _ ->
            if (path.endsWith("troit_get_theme")) stored ?: "null"
            else {
                val row = JSONObject(body!!)
                assertEquals(revision, row.getLong("p_expected_revision"))
                started.complete(Unit)
                delay(50)
                revision++
                cloud(ThemeSettings(row.getString("p_theme_id"), row.getString("p_accent_id")), revision).also { stored = it }
            }
        }
        try {
            state.selectTheme("midnight")
            val edit = scope.launch { started.await(); state.selectAccent("blue") }
            state.refresh()
            edit.join()
            assertEquals(ThemeSettings("midnight", "blue"), state.settings)
            assertTrue(state.pendingSync)
            state.refresh()
            assertFalse(state.pendingSync)
            assertEquals("blue", JSONObject(stored!!).getString("accent_id"))
            assertEquals(2L, revision)
        } finally { scope.cancel() }
    }

    @Test
    fun newerOtherDeviceThemeRequiresAnExplicitChoice() = runBlocking(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var remote = cloud(ThemeSettings("midnight", "neutral"), 1)
        var writes = 0
        val state = UserThemeState(dir(), scope, { alice }) { path, _, body, _ ->
            if (path.endsWith("troit_get_theme")) remote
            else {
                writes++
                val row = JSONObject(body!!)
                assertEquals(JSONObject(remote).getLong("revision"), row.getLong("p_expected_revision"))
                cloud(ThemeSettings(row.getString("p_theme_id"), row.getString("p_accent_id")), row.getLong("p_expected_revision") + 1).also { remote = it }
            }
        }
        try {
            state.refresh()
            assertEquals("midnight", state.settings.themeId)
            state.selectTheme("rose")
            remote = cloud(ThemeSettings("ocean", "blue"), 2)
            state.refresh()
            assertEquals("rose", state.settings.themeId)
            assertEquals(ThemeSettings("ocean", "blue"), state.conflict)
            assertEquals(0, writes)
            state.keepLocalTheme()
            state.refresh()
            assertNull(state.conflict)
            assertFalse(state.pendingSync)
            assertEquals("rose", JSONObject(remote).getString("theme_id"))
            assertEquals(1, writes)
        } finally { scope.cancel() }
    }

    @Test
    fun responseFromPreviousAccountCannotChangeNewAccountsTheme() = runBlocking(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var user: String? = alice
        val state = UserThemeState(dir(), scope, { user }) { _, _, _, _ ->
            delay(80)
            cloud(ThemeSettings("rose", "mint"), 2)
        }
        try {
            val switch = scope.launch { delay(20); user = bob }
            state.refresh()
            switch.join()
            assertEquals(ThemeSettings(), state.settings)
            assertNull(state.conflict)
            assertFalse(state.syncing)
        } finally { scope.cancel() }
    }

    @Test
    fun allThemeAccentCombinationsKeepTextAndControlsReadable() {
        fun contrast(first: androidx.compose.ui.graphics.Color, second: androidx.compose.ui.graphics.Color): Float {
            val a = first.luminance()
            val b = second.luminance()
            return (max(a, b) + .05f) / (min(a, b) + .05f)
        }
        for (theme in ThemeCatalog.themes) for (accent in ThemeCatalog.accents) {
            val colors = troitColorScheme(ThemeSettings(theme.id, accent.id))
            val label = "${theme.id}/${accent.id}"
            assertTrue("$label main text contrast", contrast(colors.onSurface, colors.surface) >= 4.5f)
            assertTrue("$label button text contrast", contrast(colors.onPrimary, colors.primary) >= 4.5f)
            assertTrue("$label supporting text contrast", contrast(colors.onSurfaceVariant, colors.surface) >= 4.5f)
            assertTrue("$label primary controls contrast", contrast(colors.primary, colors.surface) >= 3f)
        }
    }
}
