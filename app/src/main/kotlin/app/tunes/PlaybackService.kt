package app.tunes

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.IdentityHashMap

/** Owns the player so music keeps going when the app is closed. Media3 draws the notification and lock-screen controls. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var widgetOwner: String? = null
    private var lastWidgetState: WidgetEntry? = null
    private val radioScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var radioJob: Job? = null
    private var radioRevision = 0L
    private val radioSession = RadioQueueSession()
    private var expectedRadioQueue: List<String>? = null
    private var connectPlayer: AndroidPlayer? = null
    private var listeningRecorder: ListeningRecorder? = null
    private data class ListeningRecorder(val transport: Sync, val owner: String?, val generation: Long,
        val state: ListeningLibraryState)
    private val playerListeners = IdentityHashMap<ExoPlayer, Player.Listener>()
    private var prepared: PreparedTransition? = null
    private var fading: ActiveFade? = null
    private var transitionQueueRevision = 0L
    private var changingEngine = false
    private var changingVolume = false
    private var stopAfterCurrent = false
    private var countedCompletion: Pair<ExoPlayer, Int>? = null
    private var sleepDeadlineJob: Job? = null
    private var audioSession = 0
    private var leveler: AudioEffect? = null
    private var sleepDeadline: Long? = null
    private var lastFiredDeadline: Long? = null
    private data class PreparedTransition(val outgoing: ExoPlayer, val incoming: ExoPlayer,
        val sourceIndex: Int, val nextIndex: Int, val queueRevision: Long, val intent: Long, val mode: PlaybackMode)
    private data class ActiveFade(val prepared: PreparedTransition, val start: Long, val durationMs: Long, val volume: Float)

    override fun onCreate() {
        super.onCreate()
        Downloads.init(filesDir) // the service can start playback without the activity
        // One session for the current and the crossfading player, so a single effect levels both.
        audioSession = (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        val player = createPlayer(handleAudioFocus = true)
        currentPlaybackMode = runCatching {
            PlaybackMode.valueOf(getSharedPreferences("troit-playback", MODE_PRIVATE).getString("mode", "STOP") ?: "STOP")
        }.getOrDefault(PlaybackMode.STOP)
        currentRadioLoading = false
        currentRadioError = null
        val transitionPrefs = getSharedPreferences("troit-transitions", MODE_PRIVATE)
        currentTransitionSettings = TransitionSettings(transitionPrefs.getBoolean("gapless", true),
            transitionPrefs.getInt("crossfade_seconds", 0), transitionPrefs.getBoolean("normalize", false)).sanitized()
        applyTransitionOptions(player)
        applyLeveler()
        player.repeatMode = nativeRepeatMode(currentPlaybackMode)
        widgetOwner = widgetAccount(this)
        attachPlayerListeners(player)
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                @Suppress("DEPRECATION")
                override fun onPlayerCommandRequest(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                    // Commands arriving from notification/Bluetooth controllers are explicit user intent.
                    // Cloud controls use the service player directly and never pass through this callback.
                    if ((controller.packageName != packageName || mediaSession.isMediaNotificationController(controller)) && playerCommand in setOf(
                        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_BACK,
                    )) {
                        if (playerCommand == Player.COMMAND_PLAY_PAUSE) clearExpiredSleepFence()
                        cancelTransition(); recordInteraction()
                    }
                    return SessionResult.RESULT_SUCCESS
                }
                // Items can lose their uri on the way from the UI to the service: rebuild it from the id.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.mapTo(mutableListOf()) { it.buildUpon().setUri(it.mediaId).build() },
                )
            })
            .build()
        running = this
        // The service remains the command receiver when the phone/TV activity is closed.
        // All AndroidPlayer wrappers observe the same explicit-interaction revision.
        connectPlayer = AndroidPlayer(player).also { receiver ->
            radioScope.launch { maintainConnectedPlayback(receiver) }
            radioScope.launch { recordListeningPlayback(receiver) }
        }
        radioScope.launch { while (isActive) {
            try { transitionTick() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { cancelTransition() }
            delay(50)
        } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        running = null
        cancelTransition()
        cancelRadio()
        radioScope.cancel()
        connectPlayer?.close()
        connectPlayer = null
        listeningRecorder = null
        leveler?.release()
        leveler = null
        session?.run {
            player.release()
            release()
        }
        session = null
        playerListeners.clear()
        TroitMusicWidget.localChanged(this)
        TroitMusicWidget.refresh(this)
        super.onDestroy()
    }

    private suspend fun maintainConnectedPlayback(receiver: AndroidPlayer) {
        var transport: Sync? = null
        var connect: PlaybackConnect? = null
        var transportOwner: String? = null
        var transportGeneration = Long.MIN_VALUE
        var reports = PlaybackReportPolicy()
        while (radioScope.isActive) {
            try {
                val owner = withContext(Dispatchers.IO) { widgetAccount(this@PlaybackService) }
                val oldTransport = transport
                if (oldTransport == null || owner != transportOwner || oldTransport.accountGeneration != transportGeneration) {
                    val freshTransport = withContext(Dispatchers.IO) {
                        // Only playback is published here; the activity owns the actual library synchronization.
                        Sync(filesDir, playbackDeviceName(), Library(File(cacheDir, "playback-transport.json")), radioScope)
                    }
                    transport = freshTransport
                    transportOwner = owner
                    transportGeneration = freshTransport.accountGeneration
                    connect = PlaybackConnect(freshTransport, receiver, playbackDeviceName())
                    listeningRecorder = ListeningRecorder(freshTransport, owner, freshTransport.accountGeneration,
                        ListeningLibraryState(freshTransport, filesDir, radioScope, playbackDeviceName()))
                    reports = PlaybackReportPolicy()
                }
                val currentTransport = checkNotNull(transport)
                // Guest sleep timers and local listening history work without an account, too.
                connect?.tick()
                if (owner != null && currentTransport.userId == owner) {
                    if (reports.shouldReport(receiver.now, System.currentTimeMillis())) {
                        currentTransport.pushPlayback(receiver.now, receiver.positionMs())
                    }
                }
                delay(if (owner == null) 2_000L else connect?.pollDelayMs ?: 2_000L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A transient session-file/device-id failure must not silently stop remote controls.
                delay(5_000L)
            }
        }
    }

    /** History qualification cannot wait behind a slow Connect poll or an unavailable server. */
    private suspend fun recordListeningPlayback(receiver: AndroidPlayer) {
        while (radioScope.isActive) {
            try {
                val recorder = listeningRecorder
                if (recorder != null) {
                    val persistedOwner = withContext(Dispatchers.IO) { widgetAccount(this@PlaybackService) }
                    if (recorder === listeningRecorder && persistedOwner == recorder.owner &&
                        recorder.transport.isCurrentAccount &&
                        recorder.transport.userId == recorder.owner && recorder.transport.accountGeneration == recorder.generation) {
                        recorder.state.recordPlayback(receiver.now, receiver.positionMs())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* A journal or account-file error must not stop the recorder. */ }
            delay(1_000)
        }
    }

    private fun musicAttributes() = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

    private fun createPlayer(handleAudioFocus: Boolean): ExoPlayer {
        // Resolution runs on Media3's loader, including when the next playlist item is preloaded.
        // DefaultDataSource also opens the plain file path a downloaded song resolves to.
        val source = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory())) { spec ->
            spec.withUri(Uri.parse(Yt.audioUrl(spec.uri.toString())))
        }
        return ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(musicAttributes(), handleAudioFocus).setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK).build().also { if (audioSession > 0) it.audioSessionId = audioSession }
    }

    /**
     * Android has no loudness normalizer, so this is a gentle compressor with make-up gain and a limiter:
     * quiet songs come up, loud ones are held back.
     * ponytail: fixed curve, API 28+. Per-track gain from measured loudness is the upgrade if this pumps.
     */
    private fun applyLeveler() {
        if (Build.VERSION.SDK_INT >= 28 && currentTransitionSettings.normalize && audioSession > 0) {
            if (leveler == null) leveler = runCatching { createLeveler() }.getOrNull()
        } else {
            leveler?.release()
            leveler = null
        }
    }

    @RequiresApi(28)
    private fun createLeveler(): AudioEffect {
        val config = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, false, 0, true, 1, false, 0, true).build()
        return DynamicsProcessing(0, audioSession, config).apply {
            // one full-range band: threshold -24 dB, ratio 3:1, +6 dB make-up; then a -1 dB limiter
            setMbcBandAllChannelsTo(0, DynamicsProcessing.MbcBand(true, 20_000f, 10f, 300f, 3f, -24f, 6f, -90f, 1f, 0f, 6f))
            setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
            setEnabled(true)
        }
    }

    private fun applyTransitionOptions(player: ExoPlayer) {
        val preload = if (currentTransitionSettings.gapless && currentTransitionSettings.crossfadeSeconds == 0 && !stopAfterCurrent)
            20_000_000L else C.TIME_UNSET
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(preload)
        player.pauseAtEndOfMediaItems = stopAfterCurrent || fading?.prepared?.outgoing === player
    }

    private fun attachPlayerListeners(engine: ExoPlayer) {
        if (playerListeners.containsKey(engine)) return
        val listener = object : Player.Listener {
            private fun active() = session?.player === engine
            override fun onPlayerError(error: PlaybackException) {
                if (!active()) { if (prepared?.incoming === engine) cancelTransition(); return }
                cancelTransition(); cancelRadio(); Yt.forgetAudioUrls()
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (!active() || reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                transitionQueueRevision++; countedCompletion = null; cancelTransition()
                val songs = engine.queueSongs()
                if (expectedRadioQueue == songs.map { it.url }) expectedRadioQueue = null
                else { expectedRadioQueue = null; cancelRadio(); radioSession.reset(songs) }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!active() || changingEngine) return
                if (!playWhenReady) {
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                        if (fading != null) {
                            finishFade()
                            // A slow/failed standby must not turn an otherwise valid playlist into a stop.
                            if (session?.player === engine && !stopAfterCurrent && canStartAfterSleepDeadline()) {
                                engine.pauseAtEndOfMediaItems = false
                                engine.play()
                            }
                        } else countCompletion(engine, engine.currentMediaItemIndex)
                    } else cancelTransition()
                    cancelRadio()
                }
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                if (!active()) return
                val mode = when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> PlaybackMode.REPEAT_ALL
                    Player.REPEAT_MODE_ONE -> PlaybackMode.REPEAT_ONE
                    else -> if (currentPlaybackMode == PlaybackMode.RADIO) PlaybackMode.RADIO else PlaybackMode.STOP
                }
                if (mode != currentPlaybackMode) changeMode(mode)
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                // Transient focus loss leaves playWhenReady true while suppressing native audio.
                if (active() && playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) cancelTransition()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (!active() || changingEngine) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SKIP || reason == Player.DISCONTINUITY_REASON_REMOVE) {
                    countedCompletion = null; cancelTransition(); cancelRadio()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!active() || changingEngine) return
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    if (countedCompletion == null) completedTrackRevision++
                    countedCompletion = null
                    cancelTransition()
                }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) widgetOwner = widgetAccount(this@PlaybackService)
            }
            override fun onVolumeChanged(volume: Float) {
                if (active() && !changingVolume && fading != null) cancelTransition(restoreVolume = false)
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (!active()) return
                if (engine.playbackState == Player.STATE_ENDED) countCompletion(engine, engine.currentMediaItemIndex)
                if (engine.playbackState == Player.STATE_ENDED && engine.playWhenReady && !stopAfterCurrent &&
                    currentPlaybackMode == PlaybackMode.RADIO && radioJob == null && currentRadioError == null) startRadio()
                val state = widgetState()
                if (state?.copy(nonce = "") != lastWidgetState?.copy(nonce = "")) {
                    lastWidgetState = state; TroitMusicWidget.localChanged(this@PlaybackService)
                }
            }
        }
        playerListeners[engine] = listener
        engine.addListener(listener)
    }

    private fun countCompletion(player: ExoPlayer, index: Int) {
        val key = player to index
        if (countedCompletion != key && player.currentMediaItem != null) {
            completedTrackRevision++; countedCompletion = key
        }
    }

    private fun changeTransitions(settings: TransitionSettings) {
        currentTransitionSettings = settings.sanitized()
        getSharedPreferences("troit-transitions", MODE_PRIVATE).edit()
            .putBoolean("gapless", currentTransitionSettings.gapless)
            .putInt("crossfade_seconds", currentTransitionSettings.crossfadeSeconds)
            .putBoolean("normalize", currentTransitionSettings.normalize).apply()
        applyLeveler()
        cancelTransition()
        (session?.player as? ExoPlayer)?.let(::applyTransitionOptions)
    }

    private fun changeStopAfterCurrent(enabled: Boolean) {
        if (stopAfterCurrent == enabled) return
        stopAfterCurrent = enabled
        if (enabled) cancelTransition()
        (session?.player as? ExoPlayer)?.let(::applyTransitionOptions)
    }

    private fun changeSleepDeadline(deadlineMillis: Long?) {
        if (deadlineMillis == null) {
            sleepDeadlineJob?.cancel(); sleepDeadlineJob = null
            sleepDeadline = null; lastFiredDeadline = null
            return
        }
        if (deadlineMillis == sleepDeadline || (deadlineMillis != null && deadlineMillis == lastFiredDeadline)) return
        sleepDeadlineJob?.cancel(); sleepDeadlineJob = null; sleepDeadline = deadlineMillis
        lastFiredDeadline = null
        sleepDeadlineJob = radioScope.launch {
            delay((deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0))
            if (sleepDeadline == deadlineMillis) {
                lastFiredDeadline = deadlineMillis; sleepDeadline = null; sleepDeadlineJob = null
                cancelTransition(); cancelRadio()
                session?.player?.pause()
            }
        }
    }

    /** Also covers a deadline reached before the main-looper alarm gets its turn. */
    private fun canStartAfterSleepDeadline() = lastFiredDeadline == null &&
        (sleepDeadline?.let { System.currentTimeMillis() < it } ?: true)

    private fun clearExpiredSleepFence() {
        if (!canStartAfterSleepDeadline()) changeSleepDeadline(null)
    }

    private fun volume(player: ExoPlayer, value: Float) {
        changingVolume = true
        try { player.volume = value.coerceIn(0f, 1f) } finally { changingVolume = false }
    }

    /** At most one upcoming decoder is retained. Failed/slow preparation leaves native gapless playback. */
    private fun transitionTick() {
        val player = session?.player as? ExoPlayer ?: return
        if (!canStartAfterSleepDeadline()) {
            cancelTransition(); player.pause(); return
        }
        val settings = currentTransitionSettings
        val fade = fading
        if (fade != null) {
            val prepared = fade.prepared
            if (stopAfterCurrent || settings.crossfadeSeconds == 0 || player !== prepared.outgoing ||
                transitionQueueRevision != prepared.queueRevision || localInteractionRevision != prepared.intent ||
                player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
                player.currentMediaItemIndex != prepared.sourceIndex || prepared.incoming.playerError != null ||
                (!player.playWhenReady && player.playbackState != Player.STATE_ENDED) ||
                prepared.incoming.playbackState == Player.STATE_BUFFERING || prepared.incoming.playbackState == Player.STATE_ENDED) {
                cancelTransition(); return
            }
            val progress = ((SystemClock.elapsedRealtime() - fade.start).toFloat() / fade.durationMs).coerceIn(0f, 1f)
            volume(player, fade.volume * (1f - progress))
            volume(prepared.incoming, fade.volume * progress)
            if (progress >= 1f || player.playbackState == Player.STATE_ENDED) finishFade()
            return
        }
        if (stopAfterCurrent || settings.crossfadeSeconds <= 0 || !player.isPlaying || currentRadioLoading ||
            player.duration <= 0 || player.currentMediaItem == null) {
            if (prepared != null && (!player.playWhenReady || stopAfterCurrent || settings.crossfadeSeconds <= 0)) cancelTransition()
            return
        }
        val remaining = player.duration - player.currentPosition
        val next = nextPlaybackIndex(currentPlaybackMode, player.currentMediaItemIndex, player.mediaItemCount) ?: run {
            if (prepared != null) cancelTransition()
            return
        }
        val pending = prepared
        if (pending != null && (pending.outgoing !== player || pending.sourceIndex != player.currentMediaItemIndex ||
                pending.nextIndex != next || pending.intent != localInteractionRevision || pending.queueRevision != transitionQueueRevision || pending.mode != currentPlaybackMode)) {
            cancelTransition(); return
        }
        if (pending == null && remaining in 1..maxOf(30_000L, settings.crossfadeSeconds * 1_000L + 10_000L)) {
            val incoming = createPlayer(handleAudioFocus = false)
            val entry = PreparedTransition(player, incoming, player.currentMediaItemIndex, next,
                transitionQueueRevision, localInteractionRevision, currentPlaybackMode)
            prepared = entry
            attachPlayerListeners(incoming)
            incoming.repeatMode = nativeRepeatMode(currentPlaybackMode)
            incoming.pauseAtEndOfMediaItems = true
            incoming.volume = 0f
            incoming.setMediaItems((0 until player.mediaItemCount).map(player::getMediaItemAt), next, 0)
            incoming.prepare()
            incoming.pause()
        }
        val ready = prepared ?: return
        if (ready.incoming.playbackState != Player.STATE_READY || ready.incoming.playerError != null) return
        val nextDuration = ready.incoming.duration.takeIf { it > 0 } ?: 0L
        val requested = settings.crossfadeSeconds * 1_000L
        val duration = minOf(requested, player.duration / 2, if (nextDuration > 0) nextDuration / 2 else requested)
        if (duration < 250 || remaining !in 1..duration) return
        val startVolume = player.volume
        player.pauseAtEndOfMediaItems = true
        fading = ActiveFade(ready, SystemClock.elapsedRealtime(), minOf(duration, remaining).coerceAtLeast(250), startVolume)
        ready.incoming.play()
    }

    private fun cancelTransition(restoreVolume: Boolean = true) {
        val fade = fading
        val pending = prepared
        fading = null; prepared = null
        if (fade != null && restoreVolume) volume(fade.prepared.outgoing, fade.volume)
        val spare = pending?.incoming
        if (spare != null && session?.player !== spare) {
            playerListeners.remove(spare)?.let(spare::removeListener)
            spare.release()
        }
        (session?.player as? ExoPlayer)?.let(::applyTransitionOptions)
    }

    private fun finishFade() {
        val fade = fading ?: return
        val entry = fade.prepared
        if (!canStartAfterSleepDeadline() || session?.player !== entry.outgoing || stopAfterCurrent || localInteractionRevision != entry.intent ||
            entry.outgoing.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
            entry.incoming.playerError != null || entry.incoming.playbackState != Player.STATE_READY) { cancelTransition(); return }
        changingEngine = true
        try {
            countCompletion(entry.outgoing, entry.sourceIndex)
            fading = null; prepared = null
            volume(entry.incoming, fade.volume)
            entry.incoming.setAudioAttributes(musicAttributes(), true)
            session?.setPlayer(entry.incoming)
            connectPlayer?.rebind(entry.incoming)
            countedCompletion = null
            applyTransitionOptions(entry.incoming)
            playerListeners.remove(entry.outgoing)?.let(entry.outgoing::removeListener)
            entry.outgoing.pause()
            entry.outgoing.release()
            TroitMusicWidget.localChanged(this)
        } finally { changingEngine = false }
    }

    private fun widgetState(): WidgetEntry? {
        val player = session?.player ?: return null
        val item = player.currentMediaItem ?: return null
        return WidgetEntry(widgetOwner, true, item.mediaMetadata.title?.toString().orEmpty(),
            item.mediaMetadata.artist?.toString().orEmpty(), item.mediaId,
            currentRadioLoading || !Util.shouldShowPlayButton(player), previous = true,
            next = player.hasNextMediaItem() || currentPlaybackMode == PlaybackMode.RADIO,
            queueIndex = player.currentMediaItemIndex)
    }

    private fun changeMode(mode: PlaybackMode) {
        val player = session?.player ?: return
        if (mode == currentPlaybackMode) return
        cancelTransition()
        cancelRadio()
        getSharedPreferences("troit-playback", MODE_PRIVATE).edit().putString("mode", mode.name).apply()
        currentPlaybackMode = mode
        player.repeatMode = nativeRepeatMode(mode)
        if (mode == PlaybackMode.RADIO && player.playbackState == Player.STATE_ENDED && player.playWhenReady) startRadio()
        TroitMusicWidget.localChanged(this)
    }

    private fun cancelRadio() {
        radioRevision++
        radioJob?.cancel()
        radioJob = null
        currentRadioLoading = false
        currentRadioError = null
    }

    private fun nextTrack(): Boolean {
        val player = session?.player ?: return false
        if (player.currentMediaItem == null) return false
        cancelTransition()
        cancelRadio()
        when {
            player.currentMediaItemIndex < player.mediaItemCount - 1 -> player.seekTo(player.currentMediaItemIndex + 1, 0)
            currentPlaybackMode == PlaybackMode.REPEAT_ALL -> player.seekTo(0, 0)
            currentPlaybackMode == PlaybackMode.RADIO -> startRadio(manual = true)
            else -> return false
        }
        return true
    }

    private fun startRadio(manual: Boolean = false) {
        val player = session?.player ?: return
        if (currentPlaybackMode != PlaybackMode.RADIO || radioJob?.isActive == true || player.currentMediaItem == null) return
        val originalQueue = player.queueSongs()
        val originalIndex = player.currentMediaItemIndex
        val originalUrl = player.currentMediaItem?.mediaId
        val continuePlaying = player.playWhenReady
        // Next means leave the current song immediately. Keep playWhenReady as the
        // user's intent while the request runs, so Pause can still cancel it.
        if (manual && player.playbackState != Player.STATE_ENDED) player.stop()
        val revision = ++radioRevision
        if (radioSession.seeds.isEmpty()) radioSession.reset(originalQueue)
        val seeds = radioSession.seeds
        val exclusions = radioSession.exclusions(originalQueue)
        currentRadioError = null
        currentRadioLoading = true
        fun stillCurrent() = running === this && revision == radioRevision && currentPlaybackMode == PlaybackMode.RADIO &&
            player.currentMediaItemIndex == originalIndex && player.currentMediaItem?.mediaId == originalUrl &&
            player.playWhenReady == continuePlaying && (manual || player.playbackState == Player.STATE_ENDED)
        radioJob = radioScope.launch {
            try {
                val preferences = withContext(Dispatchers.IO) { RegionPreferences(File(filesDir, "listening-preferences.json")).settings }
                val suggestions = withTimeout(20_000) { playlistRadioSongs(seeds, preferences, originalQueue, exclusions) }
                // Preferences may have changed while the catalogue request was in flight.
                val currentPreferences = withContext(Dispatchers.IO) { RegionPreferences(File(filesDir, "listening-preferences.json")).settings }
                if (!stillCurrent()) return@launch
                val fresh = suggestions.filter { !exclusions.contains(it) && currentPreferences.allows(it) }
                    .distinctBy(::radioSongId).take(RadioQueueSession.MAX_BATCH)
                if (fresh.isEmpty()) {
                    currentRadioError = "No similar songs found. Press Play to try again."
                    return@launch
                }
                val appended = appendRadioQueue(originalQueue, originalIndex, fresh)
                val extended = appended.songs
                radioSession.remember(fresh)
                expectedRadioQueue = extended.map { it.url }
                // Clear the request before the timeline events from this internal append arrive.
                radioJob = null
                currentRadioLoading = false
                player.setMediaItems(extended.map(Song::toMediaItem), appended.nextIndex, 0)
                player.prepare()
                if (continuePlaying) player.play() else player.pause()
            } catch (_: TimeoutCancellationException) {
                if (stillCurrent()) currentRadioError = "Similar music took too long to load. Press Play to try again."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (stillCurrent()) currentRadioError = "Couldn't load similar music. Press Play to try again."
            } finally {
                if (revision == radioRevision) {
                    radioJob = null
                    currentRadioLoading = false
                    TroitMusicWidget.localChanged(this@PlaybackService)
                }
            }
        }
    }

    companion object {
        @Volatile private var running: PlaybackService? = null
        @Volatile internal var localInteractionRevision: Long = 0L
            private set
        internal fun recordInteraction() { localInteractionRevision++ }
        internal var currentPlaybackMode by mutableStateOf(PlaybackMode.STOP)
            private set
        internal var currentTransitionSettings by mutableStateOf(TransitionSettings())
            private set
        @Volatile internal var completedTrackRevision: Long = 0L
            private set
        internal var currentRadioLoading by mutableStateOf(false)
            private set
        internal var currentRadioError by mutableStateOf<String?>(null)
            private set

        private fun nativeRepeatMode(mode: PlaybackMode) = when (mode) {
            PlaybackMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
            PlaybackMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }

        /** The activity and service share a process; the service remains authoritative when UI is closed. */
        internal fun setPlaybackMode(mode: PlaybackMode) { running?.changeMode(mode) }
        internal fun setTransitionSettings(settings: TransitionSettings) { running?.changeTransitions(settings) }
        internal fun setStopAfterCurrent(enabled: Boolean) { running?.changeStopAfterCurrent(enabled) }
        internal fun setSleepDeadline(deadlineMillis: Long?) { running?.changeSleepDeadline(deadlineMillis) }
        internal fun canStartAfterSleepDeadline(): Boolean = running?.canStartAfterSleepDeadline() != false
        internal fun resumeAfterSleepTimer() { running?.clearExpiredSleepFence() }
        internal fun cancelPendingRadio() { running?.cancelTransition(); running?.cancelRadio() }
        internal fun nextFromApp(): Boolean = running?.nextTrack() == true

        /** Returns true when a radio request consumed the playback action. */
        internal fun toggleRadioFromApp(): Boolean {
            val service = running ?: return false
            val player = service.session?.player ?: return false
            if (currentRadioLoading) {
                service.cancelRadio()
                player.pause()
                return true
            }
            if (currentPlaybackMode != PlaybackMode.RADIO || (player.playbackState != Player.STATE_ENDED && currentRadioError == null)) return false
            service.cancelRadio()
            player.play()
            service.startRadio(manual = true)
            return true
        }

        internal fun playRadioFromApp(): Boolean {
            if (currentRadioLoading) return true
            val service = running ?: return false
            val player = service.session?.player ?: return false
            if (currentPlaybackMode != PlaybackMode.RADIO || (player.playbackState != Player.STATE_ENDED && currentRadioError == null)) return false
            service.cancelRadio()
            player.play()
            service.startRadio(manual = true)
            return true
        }

        /** Main thread only. Never create a new player just to handle a remote control. */
        internal fun widgetSnapshot(): WidgetEntry? = running?.widgetState()

        internal fun controlFromWidget(action: String, expectedUrl: String, expectedIndex: Int, owner: String?): Boolean {
            val service = running ?: return false
            val player = service.session?.player ?: return false
            if (owner != service.widgetOwner || owner != widgetAccount(service) ||
                !WidgetPolicy.sameLocalTrack(expectedUrl, expectedIndex, player.currentMediaItem?.mediaId, player.currentMediaItemIndex)) return false
            service.cancelTransition()
            if (action == "play") service.clearExpiredSleepFence()
            when (action) {
                "previous" -> { service.cancelRadio(); player.seekToPrevious() }
                "next" -> if (!service.nextTrack()) return false
                "play" -> if (!playRadioFromApp()) { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
                "pause" -> { service.cancelRadio(); player.pause() }
                else -> return false
            }
            recordInteraction()
            TroitMusicWidget.localChanged(service)
            return true
        }
    }
}

private fun Player.queueSongs(): List<Song> = (0 until mediaItemCount).map { index ->
    val item = getMediaItemAt(index)
    Song(item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty(),
        item.mediaId, item.mediaMetadata.artworkUri?.toString(), item.mediaMetadata.extras?.getLong("troit_duration_seconds", 0L) ?: 0L)
}

fun Song.toMediaItem() = MediaItem.Builder()
    .setMediaId(url)
    .setUri(url)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(artwork?.let(Uri::parse))
            .setExtras(Bundle().apply { putLong("troit_duration_seconds", durationSeconds.coerceIn(0, 604800)) })
            .build(),
    )
    .build()
