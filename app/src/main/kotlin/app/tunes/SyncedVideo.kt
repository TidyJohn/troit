package app.tunes

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The music video for [song], silent, kept in step with [audio]: the playback service is never touched,
 * so showing or hiding the picture can't interrupt the music. A second player decodes only the picture and
 * is nudged or re-seeked against the audio position a few times a second.
 */
@OptIn(UnstableApi::class)
@Composable
fun SyncedVideo(audio: Player, song: Song, modifier: Modifier) {
    val context = LocalContext.current
    val picture = remember { ExoPlayer.Builder(context).build().apply { volume = 0f } }
    var message by remember(song.url) { mutableStateOf<String?>("Finding the video…") }
    DisposableEffect(picture) { onDispose { picture.release() } }
    DisposableEffect(picture, song.url) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (picture.currentMediaItem?.mediaId == song.url) message = null
            }
            override fun onPlayerError(error: PlaybackException) {
                if (picture.currentMediaItem?.mediaId == song.url) message = "Music video unavailable"
            }
        }
        picture.addListener(listener)
        onDispose { picture.removeListener(listener) }
    }
    LaunchedEffect(audio, song.url) {
        picture.stop()
        picture.clearMediaItems()
        val length = song.durationSeconds.takeIf { it > 0 } ?: (audio.duration / 1000).coerceAtLeast(0)
        val source = withContext(Dispatchers.IO) {
            // H.264 decodes in hardware on every supported device; a small picture doesn't need more than 720p.
            runCatching { Yt.cachedVideoSource(song, maxHeight = if (context.isTelevisionDevice()) 1080 else 720, h264Only = true, targetSeconds = length) }.getOrNull()
        }
        if (source == null) { message = "Music video unavailable"; return@LaunchedEffect }
        picture.setMediaItem(MediaItem.Builder().setUri(source.videoUrl).setMediaId(song.url).build())
        picture.prepare()
        picture.seekTo(audio.currentPosition + PICTURE_JUMP_LEAD_MS)
        while (true) {
            if (picture.playbackState == Player.STATE_READY) {
                val speed = pictureSyncSpeed(picture.currentPosition - audio.currentPosition)
                if (speed == null) picture.seekTo(audio.currentPosition + PICTURE_JUMP_LEAD_MS)
                else {
                    picture.playWhenReady = audio.isPlaying && speed > 0f
                    if (speed > 0f && abs(picture.playbackParameters.speed - speed) > .001f) picture.setPlaybackSpeed(speed)
                }
            }
            delay(100)
        }
    }
    Box(modifier) {
        AndroidView(factory = { PlayerView(it).apply { useController = false; player = picture } },
            onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
        message?.let { VideoPlaceholder(song, it, Modifier.fillMaxSize()) }
    }
}
