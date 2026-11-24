package app.tunes

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
internal class AndroidPlayer(private var player: Player) : MusicPlayer {
    private var baseNow by mutableStateOf(NowPlaying())
        private set
    override val now: NowPlaying
        get() = when {
            PlaybackService.currentRadioLoading -> baseNow.copy(playing = true, loading = true, error = null)
            PlaybackService.currentRadioError != null -> baseNow.copy(playing = false, loading = false, error = PlaybackService.currentRadioError)
            else -> baseNow
        }
    override val playbackMode get() = PlaybackService.currentPlaybackMode
    override val transitionSettings get() = PlaybackService.currentTransitionSettings
    override fun setTransitionSettings(settings: TransitionSettings) = PlaybackService.setTransitionSettings(settings)
    override val canNormalize get() = android.os.Build.VERSION.SDK_INT >= 28
    /** The session player itself: the clock the music-video picture follows. */
    internal val media: Player get() = player
    override val completedTrackRevision get() = PlaybackService.completedTrackRevision
    override fun setStopAfterCurrent(enabled: Boolean) = PlaybackService.setStopAfterCurrent(enabled)
    override fun setSleepDeadline(deadlineMillis: Long?) = PlaybackService.setSleepDeadline(deadlineMillis)
    override fun setPlaybackMode(mode: PlaybackMode) {
        if (mode != playbackMode) { PlaybackService.setPlaybackMode(mode); PlaybackService.recordInteraction() }
    }
    private var queue = emptyList<Song>()
    override val interactionRevision: Long
        get() = PlaybackService.localInteractionRevision

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player !== this@AndroidPlayer.player) return
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) readQueue()
            update()
        }
    }

    init {
        player.addListener(listener)
        readQueue() // the service may already be playing when the app is reopened
        update()
    }

    fun close() { player.removeListener(listener) }

    /** The service keeps one cloud receiver while MediaSession promotes a prepared decoder. */
    fun rebind(next: Player) {
        if (next === player) return
        player.removeListener(listener)
        player = next
        next.addListener(listener)
        readQueue()
        update()
    }

    private fun readQueue() {
        queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() }
    }

    private fun update() {
        baseNow = NowPlaying(
            queue = queue,
            index = if (queue.isEmpty()) -1 else player.currentMediaItemIndex,
            playing = !Util.shouldShowPlayButton(player),
            loading = player.playbackState == Player.STATE_BUFFERING,
            error = player.playerError?.let { "Playback failed (${it.errorCodeName})." },
            audioQuality = player.currentMediaItem?.mediaId?.let(Yt::cachedAudioQuality),
        )
    }

    override fun positionMs() = player.currentPosition
    override suspend fun preloadSource(song: Song) {
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) { Yt.audioSource(song.url) }
    }
    override fun durationMs() = player.duration.takeIf { it > 0 } ?: 0L

    override fun seekTo(positionMs: Long) {
        PlaybackService.cancelPendingRadio()
        val target = positionMs.coerceAtLeast(0).let { requested ->
            durationMs().takeIf { it > 0 }?.let(requested::coerceAtMost) ?: requested
        }
        player.seekTo(target)
        PlaybackService.recordInteraction()
    }

    override fun play(songs: List<Song>, index: Int, positionMs: Long) {
        if (index !in songs.indices) return
        PlaybackService.resumeAfterSleepTimer()
        PlaybackService.cancelPendingRadio()
        if (queue == songs && player.mediaItemCount == songs.size) {
            player.seekTo(index, positionMs.coerceAtLeast(0))
        } else {
            player.setMediaItems(songs.map { it.toMediaItem() }, index, if (positionMs > 0) positionMs else C.TIME_UNSET)
        }
        player.prepare()
        player.play()
        PlaybackService.recordInteraction()
    }

    override fun toggle() {
        if (player.currentMediaItem == null) return
        if (Util.shouldShowPlayButton(player)) PlaybackService.resumeAfterSleepTimer()
        if (!PlaybackService.toggleRadioFromApp()) Util.handlePlayPauseButtonAction(player)
        PlaybackService.recordInteraction()
    }

    override fun next() {
        if (!PlaybackService.nextFromApp()) return
        PlaybackService.recordInteraction()
    }

    override fun previous() {
        if (player.currentMediaItem == null) return
        PlaybackService.cancelPendingRadio()
        player.seekToPrevious()
        PlaybackService.recordInteraction()
    }

    override suspend fun control(expectedUrl: String, expectedIndex: Int, action: String, allowed: () -> Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            // Check the controller itself: its state may already have advanced ahead of the UI listener.
            if (!allowed() || player.currentMediaItem?.mediaId != expectedUrl ||
                player.currentMediaItemIndex != expectedIndex ||
                (player.playbackState == Player.STATE_BUFFERING && action != "pause")) return@withContext false
            if (action == "play" && !PlaybackService.canStartAfterSleepDeadline()) return@withContext false
            when (action) {
                "next" -> if (!PlaybackService.nextFromApp()) return@withContext false
                "previous" -> { PlaybackService.cancelPendingRadio(); player.seekToPrevious() }
                "play" -> if (!PlaybackService.playRadioFromApp()) { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
                "pause" -> { PlaybackService.cancelPendingRadio(); player.pause() }
                else -> return@withContext false
            }
            // ExoPlayer batches onEvents until the looper yields. Publish the applied state before
            // the Connect receipt, rather than acknowledging a skip with the previous cached song.
            readQueue()
            update()
            true
        }

    override suspend fun replaceQueue(expectedQueue: List<Song>?, songs: List<Song>, index: Int,
        positionMs: Long, playing: Boolean, mode: PlaybackMode, allowed: () -> Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val liveQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() }
            if (!allowed() || index !in songs.indices || songs.size > 1000 ||
                (expectedQueue != null && liveQueue != expectedQueue)) return@withContext false
            if (playing && !PlaybackService.canStartAfterSleepDeadline()) return@withContext false
            if (expectedQueue != null && (player.currentMediaItemIndex != index ||
                    liveQueue.take(index + 1) != songs.take(index + 1))) return@withContext false
            PlaybackService.cancelPendingRadio()
            PlaybackService.setPlaybackMode(mode)
            if (expectedQueue != null) {
                // Only replace upcoming items. Media3 keeps the current decoder and seek position.
                if (player.mediaItemCount > index + 1) player.removeMediaItems(index + 1, player.mediaItemCount)
                if (songs.size > index + 1) player.addMediaItems(songs.drop(index + 1).map(Song::toMediaItem))
            } else {
                player.setMediaItems(songs.map(Song::toMediaItem), index, positionMs.coerceAtLeast(0))
                player.prepare()
                if (playing && PlaybackService.canStartAfterSleepDeadline()) player.play() else player.pause()
                PlaybackService.recordInteraction()
            }
            readQueue()
            update()
            true
        }
}

private fun MediaItem.toSong() = Song(
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    url = mediaId,
    artwork = mediaMetadata.artworkUri?.toString(),
    durationSeconds = mediaMetadata.extras?.getLong("troit_duration_seconds", 0L) ?: 0L,
)
