package app.tunes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** A user-started network controller for the PC, independent of the Android activity/player. */
class LiveWidgetService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var observer: Job? = null
    private var observerEpoch = 0L
    private var observerOwner: String? = null
    private var notificationText: String? = null
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { wake.trySend(Unit) }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Connected player", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Keeps the Troit widget in sync with your computer." })
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            settings(this).edit().putBoolean("paused", true).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        val owner = widgetAccount(this)
        if (owner == null || !TroitMusicWidget.hasWidgets(this) || settings(this).getBoolean("paused", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification("Keeping your widget up to date"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (_: RuntimeException) {
            // Android may reject a background start. WorkManager/manual controls remain available.
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer?.isActive == true && observerOwner == owner) wake.trySend(Unit)
        else {
            val epoch = ++observerEpoch
            observer?.cancel()
            observerOwner = owner
            observer = scope.launch {
                try { observe(owner) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* WorkManager remains available after a transport startup failure. */ }
                finally { if (epoch == observerEpoch) stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun observe(owner: String) {
        val sync = withContext(Dispatchers.IO) {
            // This transport never opens, serializes, or uploads the user's real music library.
            Sync(filesDir, "Phone", Library(File(cacheDir, "live-widget-transport.json")), scope)
        }
        val generation = sync.accountGeneration
        if (sync.userId != owner) return
        val client = ConnectClient(sync)
        val power = getSystemService(PowerManager::class.java)
        var unsuccessfulReads = 0
        var remote: ConnectSnapshot? = null
        while (scope.isActive && owner == widgetAccount(this) && generation == sync.accountGeneration &&
            TroitMusicWidget.hasWidgets(this)) {
            val interactive = power.isInteractive
            val localPlaying = PlaybackService.widgetSnapshot()?.let { it.owner == owner && it.playing } == true
            val networkAvailable = networkAvailable()
            if (interactive && networkAvailable) {
                try {
                    val state = client.state()?.takeIf { it.deviceId != sync.deviceId }
                    if (owner != widgetAccount(this) || generation != sync.accountGeneration) break
                    remote = state
                    unsuccessfulReads = if (state?.isFresh() == true) 0 else unsuccessfulReads + 1
                    TroitMusicWidget.acceptRemoteState(this, owner, state)
                    updateNotification(when {
                        localPlaying -> "Your phone player updates the widget live"
                        state?.isFresh() == true -> "${state.song.title.take(100)} · ${state.deviceName.take(40)}"
                        else -> "Waiting for your computer · updates reconnect automatically"
                    })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (owner != widgetAccount(this) || generation != sync.accountGeneration) break
                    unsuccessfulReads = if (failure is AccountApiException && failure.setupRequired) 4 else unsuccessfulReads + 1
                    if (remote != null) TroitMusicWidget.acceptRemoteState(this, owner, remote.copy(fresh = false),
                        "Reconnecting automatically")
                    updateNotification(if (failure is AccountApiException && failure.setupRequired)
                        "Connect needs the Troit database update" else "Reconnecting to your computer")
                }
            }
            val waitMs = LiveWidgetPolicy.nextDelayMs(interactive, localPlaying, networkAvailable,
                remote?.isFresh() == true, remote?.playing == true, unsuccessfulReads)
            // The screen-on broadcast interrupts this wait; no wake lock or screen-off HTTP loop is used.
            withTimeoutOrNull(waitMs) { wake.receive() }
        }
    }

    private fun networkAvailable(): Boolean {
        val network = getSystemService(ConnectivityManager::class.java)
        return network.activeNetwork?.let(network::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveWidgetService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.widget_music)
            .setContentTitle("Troit Connect").setContentText(text).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop live updates", stop).build()
    }

    private fun updateNotification(text: String) {
        if (notificationText == text) return
        notificationText = text
        // Updating the existing foreground notification does not require a separate notification prompt.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(text),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (_: RuntimeException) { stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (running === this) running = null
        observerEpoch++
        observer?.cancel()
        scope.cancel()
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        wake.close()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "troit-connected-widget"
        private const val NOTIFICATION = 4521
        private const val STOP = "app.tunes.STOP_LIVE_WIDGET"
        @Volatile private var running: LiveWidgetService? = null
        private fun settings(c: Context) = c.getSharedPreferences("troit_live_widget", Context.MODE_PRIVATE)

        fun isRunning() = running?.observer?.isActive == true

        /** Call only from a visible activity or an explicit user interaction with the widget. */
        fun start(c: Context, userInitiated: Boolean = false) {
            if (!TroitMusicWidget.hasWidgets(c) || widgetAccount(c) == null) return
            if (userInitiated) settings(c).edit().putBoolean("paused", false).apply()
            else if (settings(c).getBoolean("paused", false)) return
            try { ContextCompat.startForegroundService(c, Intent(c, LiveWidgetService::class.java)) }
            catch (_: RuntimeException) { /* Scheduled widget refresh remains the fallback. */ }
        }

        fun stop(c: Context) { c.stopService(Intent(c, LiveWidgetService::class.java)) }
        fun playbackChanged() { running?.wake?.trySend(Unit) }
    }
}
