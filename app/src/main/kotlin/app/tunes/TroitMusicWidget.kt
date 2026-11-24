package app.tunes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A small, account-scoped display cache. It never contains credentials, queues, or album bitmaps. */
internal data class WidgetEntry(
    val owner: String?, val local: Boolean, val title: String, val artist: String,
    val url: String, val playing: Boolean, val previous: Boolean = true, val next: Boolean = true,
    val queueIndex: Int = -1,
    val remote: String? = null, val nonce: String = UUID.randomUUID().toString(),
) {
    fun json() = JSONObject().put("owner", owner ?: JSONObject.NULL).put("local", local)
        .put("title", title.take(300)).put("artist", artist.take(300)).put("url", url.take(2048))
        .put("playing", playing).put("previous", previous).put("next", next).put("queueIndex", queueIndex)
        .put("remote", remote ?: JSONObject.NULL).put("nonce", nonce).toString()

    companion object {
        fun parse(raw: String): WidgetEntry? = runCatching {
            require(raw.length <= MAX_CACHE_CHARS)
            val j = JSONObject(raw)
            WidgetEntry(j.optString("owner").takeUnless { j.isNull("owner") || it.isBlank() },
                j.getBoolean("local"), j.getString("title"), j.getString("artist"), j.getString("url"),
                j.getBoolean("playing"), j.optBoolean("previous", true), j.optBoolean("next", true), j.optInt("queueIndex", -1),
                j.optString("remote").takeUnless { j.isNull("remote") || it.isBlank() }, j.getString("nonce"))
        }.getOrNull()
    }
}

private const val MAX_CACHE_CHARS = 20_000
private const val MAX_COMMAND_BYTES = 7_000
private const val WORK_TAG = "troit-widget"
private const val PERIODIC = "troit-widget-periodic"
private const val REFRESH = "troit-widget-refresh"
private const val ACTION = "app.tunes.WIDGET_CONTROL"

/** Read only the account identifier. Auth and refresh stay inside Sync's protected account transport. */
internal fun widgetAccount(context: Context): String? = runCatching {
    val file = File(context.filesDir, "session.json")
    require(file.length() in 1..32_768)
    JSONObject(file.readText()).getString("userId").takeIf { it.isNotBlank() }
}.getOrNull()

private object WidgetCache {
    private fun prefs(c: Context) = c.getSharedPreferences("troit_music_widget", Context.MODE_PRIVATE)
    fun accountMarker(c: Context) = prefs(c).getString("observed_owner", null)
    fun renderKey(c: Context) = prefs(c).getString("render_key", null)
    fun markRendered(c: Context, key: String) = prefs(c).edit().putString("render_key", key).apply()
    fun markAccount(c: Context, owner: String?) = prefs(c).edit().putString("observed_owner", WidgetPolicy.accountMarker(owner)).apply()
    fun entry(c: Context) = prefs(c).getString("entry", null)?.let(WidgetEntry::parse)
        ?.takeIf { it.owner == widgetAccount(c) }
    fun remote(c: Context, owner: String?) = prefs(c).getString("remote", null)
        ?.takeIf { prefs(c).getString("owner", null) == owner }
        ?.let { runCatching { ConnectSnapshot.fromJson(it) }.getOrNull() }
    fun save(c: Context, entry: WidgetEntry?) {
        val edit = prefs(c).edit()
        if (entry == null) edit.remove("entry") else {
            val raw = entry.json()
            if (raw.length <= MAX_CACHE_CHARS) edit.putString("entry", raw) else edit.remove("entry")
        }
        edit.apply()
    }
    fun saveRemote(c: Context, owner: String?, remote: ConnectSnapshot?) {
        val raw = remote?.toJson()?.takeIf { it.length <= MAX_CACHE_CHARS }
        prefs(c).edit().putString("owner", owner).putString("remote", raw).apply()
    }
    fun clear(c: Context) = prefs(c).edit().clear().apply()
}

/** No network or service startup in a broadcast. Buttons target the exact track/device shown. */
class TroitMusicWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context)
        schedule(context)
        refresh(context)
    }

    override fun onEnabled(context: Context) { schedule(context); refresh(context) }
    override fun onDisabled(context: Context) {
        LiveWidgetService.stop(context)
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        WidgetCache.clear(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION || !hasWidgets(context)) return
        val action = intent.getStringExtra("control") ?: return
        if (action == "refresh") {
            LiveWidgetService.start(context, userInitiated = true)
            refresh(context, userInitiated = true)
            return
        }
        if (action !in WidgetPolicy.actions) return
        LiveWidgetService.start(context, userInitiated = true)
        val entry = intent.getStringExtra("entry")?.let(WidgetEntry::parse) ?: return
        if (entry.owner != widgetAccount(context)) { onAccountChanged(context, widgetAccount(context)); return }
        if (entry.local) {
            val handled = PlaybackService.controlFromWidget(action, entry.url, entry.queueIndex, entry.owner)
            if (!handled) { render(context, "Open Troit to resume on this phone"); refresh(context) }
            return
        }
        val owner = entry.owner ?: return
        val target = entry.remote?.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES } ?: return
        runCatching { ConnectSnapshot.fromJson(target) }.getOrNull() ?: return
        val commandId = UUID.nameUUIDFromBytes("${intent.getIntExtra("widget", 0)}:${entry.nonce}:$action".toByteArray()).toString()
        val work = OneTimeWorkRequestBuilder<MusicWidgetWorker>()
            .setInputData(workDataOf("owner" to owner, "target" to target, "control" to action,
                "commandId" to commandId, "issuedAt" to System.currentTimeMillis()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .keepResultsForAtLeast(1, TimeUnit.DAYS)
            .apply { if (Build.VERSION.SDK_INT >= 31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .addTag(WORK_TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork("troit-widget-command-$commandId", ExistingWorkPolicy.KEEP, work)
    }

    companion object {
        private fun ids(c: Context) = AppWidgetManager.getInstance(c)
            .getAppWidgetIds(ComponentName(c, TroitMusicWidget::class.java))
        fun hasWidgets(c: Context) = ids(c).isNotEmpty()

        fun onAccountChanged(c: Context, owner: String?) {
            if (!hasWidgets(c)) return
            if (owner != widgetAccount(c)) return
            val marker = WidgetCache.accountMarker(c)
            if (!WidgetPolicy.needsAccountReset(marker, owner)) {
                // The Compose hook also runs on Activity creation; preserve a valid display and pending tap.
                WidgetCache.markAccount(c, owner)
                schedule(c)
                refresh(c)
                return
            }
            // Guest has a distinct marker, so sign-out followed by the same account still cancels old taps.
            LiveWidgetService.stop(c)
            WorkManager.getInstance(c).cancelAllWorkByTag(WORK_TAG)
            WidgetCache.clear(c)
            WidgetCache.markAccount(c, owner)
            render(c)
            schedule(c)
            refresh(c)
        }

        fun refresh(c: Context, userInitiated: Boolean = false) {
            if (!hasWidgets(c)) return
            val owner = widgetAccount(c)
            val work = OneTimeWorkRequestBuilder<MusicWidgetWorker>()
                .setInputData(workDataOf("owner" to owner, "guest" to (owner == null), "userInitiated" to userInitiated))
                .apply { if (userInitiated && Build.VERSION.SDK_INT >= 31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                .addTag(WORK_TAG).build()
            WorkManager.getInstance(c).enqueueUniqueWork(REFRESH, ExistingWorkPolicy.REPLACE, work)
        }

        /** A short read-only fallback if Android could not keep the live connection running. */
        internal fun followUp(c: Context, owner: String?, remaining: Int = 3) {
            if (remaining !in 1..3 || owner != widgetAccount(c) || !hasWidgets(c) || LiveWidgetService.isRunning()) return
            val work = OneTimeWorkRequestBuilder<MusicWidgetWorker>()
                .setInputData(workDataOf("owner" to owner, "followUp" to (remaining - 1)))
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(WORK_TAG).build()
            WorkManager.getInstance(c).enqueueUniqueWork("troit-widget-followup-$remaining", ExistingWorkPolicy.REPLACE, work)
        }

        private fun schedule(c: Context) {
            if (!hasWidgets(c)) return
            val work = PeriodicWorkRequestBuilder<MusicWidgetWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("owner" to widgetAccount(c), "guest" to (widgetAccount(c) == null)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(WORK_TAG).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, work)
        }

        /** Called on the player's main looper only when visible playback state changes. */
        internal fun localChanged(c: Context) {
            LiveWidgetService.playbackChanged()
            if (!hasWidgets(c)) return
            val owner = widgetAccount(c)
            selectAndRender(c, PlaybackService.widgetSnapshot(), WidgetCache.remote(c, owner), expectedOwner = owner)
        }

        /** Account-scoped metadata ingress used by workers and the live connection service. */
        internal suspend fun acceptRemoteState(c: Context, owner: String?, remote: ConnectSnapshot?, message: String? = null,
            resetActions: Boolean = false) = withContext(Dispatchers.Main.immediate) {
            if (owner != widgetAccount(c) || !hasWidgets(c)) return@withContext
            val incoming = remote?.widgetMetadata()
            val previous = WidgetCache.remote(c, owner)
            val accepted = if (WidgetPresentation.accepts(previous, incoming)) incoming else previous
            WidgetCache.saveRemote(c, owner, accepted)
            if (owner != widgetAccount(c)) return@withContext
            selectAndRender(c, PlaybackService.widgetSnapshot(), accepted, message, expectedOwner = owner,
                forceRebind = resetActions)
        }

        internal fun selectAndRender(c: Context, local: WidgetEntry?, remote: ConnectSnapshot?, message: String? = null,
            expectedOwner: String?, forceRebind: Boolean = false) {
            val owner = expectedOwner
            if (owner != widgetAccount(c)) return
            val phone = local?.takeIf { it.owner == owner }
            val fresh = remote?.let { it.fresh && WidgetPolicy.remoteFresh(it.expiresAt, System.currentTimeMillis()) } == true
            val entry = if (WidgetPolicy.chooseLocal(phone != null, phone?.playing == true, fresh, remote?.playing == true)) phone
                else remote?.takeIf { owner != null }?.let {
                    WidgetEntry(owner, false, it.song.title, it.song.artist, it.song.url, it.playing, remote = it.toJson())
                }
            // Heartbeats refresh the cached lease but preserve the visible widget and button identities.
            val previous = WidgetCache.entry(c)
            val sameDisplay = WidgetPresentation.key(entry, null) == WidgetPresentation.key(previous, null)
            val display = if (!forceRebind && entry != null && previous != null && sameDisplay)
                entry.copy(nonce = previous.nonce) else entry
            if (owner != widgetAccount(c)) return
            WidgetCache.save(c, display)
            if (forceRebind || WidgetCache.renderKey(c) != WidgetPresentation.key(display, message)) render(c, message)
        }

        internal fun render(c: Context, message: String? = null) {
            val renderOwner = widgetAccount(c)
            val entry = WidgetCache.entry(c)
            val remote = entry?.remote?.let { runCatching { ConnectSnapshot.fromJson(it) }.getOrNull() }
            val fresh = remote?.let { it.fresh && WidgetPolicy.remoteFresh(it.expiresAt, System.currentTimeMillis()) } == true
            // A stale remote button renews only the exact displayed target; it never controls another track.
            val enabled = entry != null && (entry.local || remote != null)
            val label = message ?: when {
                entry == null -> if (widgetAccount(c) == null) "TROIT · sign in to connect devices" else "TROIT · phone + computer + TV"
                entry.local -> "TROIT · on this phone"
                fresh -> "TROIT · on ${remote?.deviceName.orEmpty().take(50)}"
                else -> "Last reported · ${remote?.deviceName.orEmpty().take(50)}"
            }
            val manager = AppWidgetManager.getInstance(c)
            for (id in ids(c)) {
                val views = RemoteViews(c.packageName, R.layout.troit_music_widget)
                views.setTextViewText(R.id.widget_title, entry?.title ?: c.getString(R.string.widget_empty_title))
                views.setTextViewText(R.id.widget_artist, entry?.artist ?: c.getString(R.string.widget_empty_artist))
                views.setTextViewText(R.id.widget_device, label)
                views.setImageViewResource(R.id.widget_toggle, if (entry?.playing == true) R.drawable.widget_pause else R.drawable.widget_play)
                views.setContentDescription(R.id.widget_toggle, c.getString(if (entry?.playing == true) R.string.widget_pause else R.string.widget_play))
                val open = PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java)
                    .setData(Uri.parse("troit://widget/$id/open")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_open, open)
                views.setOnClickPendingIntent(R.id.widget_cover, open)
                bind(c, views, id, R.id.widget_refresh, "refresh", null, true)
                bind(c, views, id, R.id.widget_previous, "previous", entry, enabled && entry?.previous == true)
                bind(c, views, id, R.id.widget_toggle, if (entry?.playing == true) "pause" else "play", entry, enabled)
                bind(c, views, id, R.id.widget_next, "next", entry, enabled && entry?.next == true)
                if (renderOwner != widgetAccount(c)) return
                manager.updateAppWidget(id, views)
            }
            if (renderOwner == widgetAccount(c)) WidgetCache.markRendered(c, WidgetPresentation.key(entry, message))
        }

        private fun bind(c: Context, views: RemoteViews, id: Int, view: Int, action: String, entry: WidgetEntry?, enabled: Boolean) {
            views.setBoolean(view, "setEnabled", enabled)
            views.setFloat(view, "setAlpha", if (enabled) 1f else .3f)
            val intent = Intent(c, TroitMusicWidget::class.java).setAction(ACTION)
                .setData(Uri.parse("troit://widget/$id/$view"))
                .putExtra("widget", id).putExtra("control", action).putExtra("entry", entry?.json())
            views.setOnClickPendingIntent(view, PendingIntent.getBroadcast(c, id * 31 + view, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }
}

/** Work is short, serial, cancellable and tied to the account and runtime shown at the original tap. */
class MusicWidgetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = mutex.withLock {
        val c = applicationContext
        if (!TroitMusicWidget.hasWidgets(c)) return@withLock Result.success()
        val owner = inputData.getString("owner")
        if (owner != widgetAccount(c)) return@withLock Result.success()
        val action = inputData.getString("control")
        // The live service already observes state; keep periodic fallback work from duplicating its reads.
        if (action == null && !inputData.getBoolean("userInitiated", false) && LiveWidgetService.isRunning())
            return@withLock Result.success()
        try {
            coroutineScope {
                val sync = withContext(Dispatchers.IO) { Sync(c.filesDir, "Phone", Library(File(c.cacheDir, "widget-transport.json")), this@coroutineScope) }
                if (sync.userId != owner || owner != widgetAccount(c)) return@coroutineScope
                val connect = ConnectClient(sync)
                suspend fun showChanged(current: ConnectSnapshot?) {
                    if (owner != widgetAccount(c)) return
                    val display = current?.takeIf { it.deviceId != sync.deviceId }
                    TroitMusicWidget.acceptRemoteState(c, owner, display,
                        if (current?.isFresh() == true) "Playback changed · tap again" else "Device is offline")
                }
                var receipt: ConnectCommandResult? = null
                if (action != null) {
                    val issuedAt = inputData.getLong("issuedAt", 0)
                    if (action !in WidgetPolicy.actions || !WidgetPolicy.owns(owner, widgetAccount(c)) ||
                        !WidgetPolicy.commandFresh(issuedAt, System.currentTimeMillis())) return@coroutineScope
                    val raw = inputData.getString("target") ?: return@coroutineScope
                    if (raw.toByteArray(Charsets.UTF_8).size > MAX_COMMAND_BYTES) return@coroutineScope
                    var target = ConnectSnapshot.fromJson(raw)
                    val commandId = inputData.getString("commandId") ?: return@coroutineScope
                    withContext(Dispatchers.Main.immediate) { TroitMusicWidget.render(c, "Sending to ${target.deviceName.take(40)}…") }
                    // A previous request may have succeeded before its response was lost. Never renew/recreate it.
                    receipt = connect.commandStatus(commandId)
                    if (owner != widgetAccount(c)) return@coroutineScope
                    if (receipt == null) {
                        if (!target.isFresh()) {
                            val renewed = connect.state()?.widgetMetadata()
                            if (owner != widgetAccount(c)) return@coroutineScope
                            if (renewed?.isFresh() == true && WidgetPolicy.sameTarget(target, renewed)) target = renewed
                            else {
                                showChanged(renewed)
                                return@coroutineScope
                            }
                        }
                        // Renewal and network waits also consume the original tap's validity window.
                        if (!WidgetPolicy.commandFresh(issuedAt, System.currentTimeMillis()) || owner != widgetAccount(c)) return@coroutineScope
                        try { receipt = connect.command(target, action, commandId) }
                        catch (failure: AccountApiException) {
                            if (failure.code != "40001") throw failure
                            // The track may change between renewal and enqueue; show it without replaying the tap.
                            showChanged(connect.state()?.widgetMetadata())
                            return@coroutineScope
                        }
                    }
                    if (owner != widgetAccount(c)) return@coroutineScope
                    val update = reconcileWidgetCommand(target, action, receipt,
                        isCurrentAccount = { owner == widgetAccount(c) },
                        readReceipt = { connect.commandStatus(commandId) },
                        readState = { connect.state() },
                        onState = { current, latestReceipt ->
                            TroitMusicWidget.acceptRemoteState(c, owner,
                                current?.takeIf { it.deviceId != sync.deviceId },
                                if (latestReceipt?.pending == true) "Updating playback…" else null)
                        })
                    if (!update.accountValid) return@coroutineScope
                    TroitMusicWidget.acceptRemoteState(c, owner,
                        update.state?.takeIf { it.deviceId != sync.deviceId },
                        when {
                            !update.confirmed -> "Waiting for device"
                            update.receipt?.executed != true -> "Playback changed"
                            else -> null
                        }, resetActions = update.receipt?.pending == false)
                    if (!update.confirmed) TroitMusicWidget.followUp(c, owner)
                    return@coroutineScope
                }
                if (owner != widgetAccount(c)) return@coroutineScope
                val remote = if (owner == null) null else connect.state()?.takeIf { it.deviceId != sync.deviceId }?.widgetMetadata()
                if (owner != widgetAccount(c)) return@coroutineScope
                TroitMusicWidget.acceptRemoteState(c, owner, remote)
                TroitMusicWidget.followUp(c, owner, inputData.getInt("followUp", 0))
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (owner == widgetAccount(c)) withContext(Dispatchers.Main.immediate) {
                TroitMusicWidget.localChanged(c)
                TroitMusicWidget.render(c, if (failure is AccountApiException && failure.setupRequired)
                    "Open Troit to finish Connect setup" else "Reconnecting to device…")
            }
            // Retry the same command ID only while its original 30-second validity window remains.
            if (action != null && owner == widgetAccount(c) && runAttemptCount < 2 &&
                WidgetPolicy.commandFresh(inputData.getLong("issuedAt", 0), System.currentTimeMillis())) Result.retry()
            else Result.success()
        }
    }

    companion object { private val mutex = Mutex() }
}

/** Keep both cached snapshots and WorkManager Data comfortably below the 10 KiB work-data limit. */
private fun ConnectSnapshot.widgetMetadata() = copy(deviceName = deviceName.take(64), song = song.copy(
    title = song.title.take(160), artist = song.artist.take(160), artwork = null))
