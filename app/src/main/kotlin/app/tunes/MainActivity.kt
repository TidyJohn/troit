package app.tunes

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var activityPlayer: MusicPlayer? = null
    private var remotePlayback: Pair<PlaybackRemoteState, String>? = null
    private var notificationPeerId by mutableStateOf<String?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        ChatNotifications.accountChanged(applicationContext, widgetAccount(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPeerId = ChatNotifications.peerFromIntent(this, intent)
        setContent {
            var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, _ ->
                    active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    ChatNotifications.appVisible = active
                }
                ChatNotifications.appVisible = active
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer); ChatNotifications.appVisible = false }
            }
            val player = rememberPlayer()
            SideEffect { activityPlayer = player }
            App(player, filesDir, playbackDeviceName(), foreground = active,
                isTelevision = isTelevisionDevice(), managePlaybackConnect = false,
                onRemotePlaybackReady = { state, localDeviceId -> remotePlayback = state to localDeviceId },
                onChatNotification = { ChatNotifications.show(applicationContext, it) },
                onChatRead = { ChatNotifications.markRead(applicationContext, it) },
                notificationPeerId = notificationPeerId,
                onNotificationOpened = { notificationPeerId = null },
                videoSurface = { song, modifier -> (player as? AndroidPlayer)?.let { SyncedVideo(it.media, song, modifier) } },
                onAccountChanged = {
                    TroitMusicWidget.onAccountChanged(applicationContext, it)
                    ChatNotifications.accountChanged(applicationContext, it)
                    if (it != null) requestChatNotificationPermission()
                    if (it != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        LiveWidgetService.start(applicationContext)
                    }
                }, themeChrome = {
                val background = MaterialTheme.colorScheme.background
                SideEffect {
                    window.statusBarColor = background.toArgb()
                    window.navigationBarColor = background.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = background.luminance() > .5f
                        isAppearanceLightNavigationBars = background.luminance() > .5f
                    }
                }
            }) { onBack -> BackHandler(onBack = onBack) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationPeerId = ChatNotifications.peerFromIntent(this, intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (handleTelevisionMediaKey(event)) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (isTelevisionDevice() && keyCode in televisionMediaKeys && activityPlayer != null) true
        else super.onKeyUp(keyCode, event)

    private fun handleTelevisionMediaKey(event: KeyEvent): Boolean {
        if (isTelevisionDevice() && event.keyCode in televisionMediaKeys) {
            val player = activityPlayer ?: return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val (remote, localDeviceId) = remotePlayback ?: (null to "")
                val snapshot = remote?.snapshot
                if (remote != null && snapshot != null && snapshot.deviceId != localDeviceId && !player.now.playing) {
                    val action = when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (snapshot.playing) "pause" else "play"
                        KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
                        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> "pause"
                        KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
                        else -> null
                    }
                    if (action != null) lifecycleScope.launch { remote.sendControl(action) }
                    return true
                }
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> player.toggle()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> if (!player.now.playing) player.toggle()
                    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (player.now.playing) player.toggle()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> player.next()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player.previous()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekTo(player.positionMs() + 10_000)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekTo((player.positionMs() - 10_000).coerceAtLeast(0))
                }
            }
            return true // Consume key-up too, preventing a second MediaSession action.
        }
        return false
    }

    private fun requestChatNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || ChatNotifications.allowed(this)) return
        val prefs = getSharedPreferences("troit_chat_notifications", MODE_PRIVATE)
        if (prefs.getBoolean("permission_requested", false)) return
        prefs.edit().putBoolean("permission_requested", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        LiveWidgetService.start(applicationContext)
        TroitMusicWidget.refresh(applicationContext)
    }

    companion object {
        private val televisionMediaKeys = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)
    }
}

/** Connects to the player in [PlaybackService]. Null until the connection is up. */
@Composable
fun rememberPlayer(): MusicPlayer? {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MusicPlayer?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { player = runCatching { AndroidPlayer(future.get()) }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { (player as? AndroidPlayer)?.close(); MediaController.releaseFuture(future) }
    }
    return player
}


