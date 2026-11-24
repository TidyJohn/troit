package app.tunes

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.DataInputStream
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

internal const val FRAME_WIDTH = 480
internal const val FRAME_HEIGHT = 270
internal const val FRAME_RATE = 25

/**
 * The picture comes from a second, silent mpv that writes plain frames to a pipe. Frame n of a run started
 * at s shows at s + n / [FRAME_RATE], so each one is released when the audio clock reaches it: the music is
 * never touched, and the picture is ordinary pixels that menus and dialogs can draw over.
 * Returns false when the video can't be read at all.
 * ponytail: fixed [FRAME_WIDTH]x[FRAME_HEIGHT] software frames, sized for a side panel. A full-window
 * video would want mpv's own renderer through libmpv instead.
 */
internal suspend fun playVideoFrames(
    url: String, lengthMs: Long, clockMs: () -> Long, playing: () -> Boolean,
    show: suspend (frame: ByteArray, ptsMs: Long) -> Unit,
): Boolean {
    val mpv = locateMpv() ?: return false
    val frame = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 4)
    val filter = "lavfi=[fps=$FRAME_RATE,scale=$FRAME_WIDTH:$FRAME_HEIGHT:force_original_aspect_ratio=decrease," +
        "pad=$FRAME_WIDTH:$FRAME_HEIGHT:(ow-iw)/2:(oh-ih)/2,format=bgra]"
    var failures = 0
    var leadMs = 1_500L
    while (currentCoroutineContext().isActive) {
        // Starting ahead lets the first frames be ready when the sound gets there. The lead grows to what
        // the last start actually took, so a slow connection settles instead of restarting forever.
        val startMs = (clockMs() + if (playing()) leadMs else 0).coerceAtLeast(0)
        val launched = System.nanoTime()
        val process = ProcessBuilder(mpv, "--no-config", "--ytdl=no", "--no-audio", "--msg-level=all=no", "--hr-seek=yes",
            "--start=${startMs / 1000.0}", url, "--vf=$filter", "--ovc=rawvideo", "--of=rawvideo", "--o=-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        var count = 0L
        var ended = false
        coroutineScope {
            // A read blocked on the pipe only returns when the process goes, so cancellation has to end it.
            val stop = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { process.destroy() } }
            try {
                val input = DataInputStream(process.inputStream.buffered(frame.size))
                while (isActive) {
                    val pts = startMs + count * 1000 / FRAME_RATE
                    val sound = clockMs()
                    when {
                        !playing() && count > 0 -> delay(50) // the pipe fills and the reader waits with the music
                        // The listener jumped (or the start was slower than its lead): begin again from the sound.
                        sound < pts - leadMs - 1_000 || (count > 0 && sound > pts + 5_000) -> break
                        sound < pts && count > 0 -> delay((pts - sound).coerceAtMost(40))
                        else -> {
                            try { withContext(Dispatchers.IO) { input.readFully(frame) } } catch (_: IOException) { ended = true; break }
                            if (count == 0L) {
                                leadMs = maxOf(leadMs, (System.nanoTime() - launched) / 1_000_000 + 500).coerceAtMost(15_000)
                                while (playing() && clockMs() < pts) delay(20) // read early, shown at its own moment
                            }
                            count++
                            // Late frames are skipped to catch up, but not all of them, so a slow PC still shows movement.
                            if (clockMs() - pts <= 80 || count % 5 == 0L) show(frame, pts)
                        }
                    }
                }
            } finally { stop.cancel() }
        }
        if (!ended) continue
        val stoppedAt = startMs + count * 1000 / FRAME_RATE
        if (count > 0) failures = 0 else if (++failures >= 3) return false
        if (lengthMs > 0 && stoppedAt >= lengthMs - 3_000) {
            // The video is over. Stay on its last frame unless the listener goes back.
            while (currentCoroutineContext().isActive && clockMs() >= stoppedAt - 1_000) delay(200)
        } else delay(1_000) // a dropped connection or a slow start: try again where the sound is now
    }
    return true
}

/** Finds the video for [song] and draws it in step with [player]. [find] gets the song and its length in seconds. */
@Composable
internal fun MpvSyncedVideo(player: MpvPlayer, song: Song, modifier: Modifier,
    find: suspend (Song, Long) -> VideoSource? = { wanted, length -> runCatching { Yt.cachedVideoSource(wanted, maxHeight = 480, targetSeconds = length) }.getOrNull() }) {
    val bitmap = remember { Bitmap() }
    val image = remember(bitmap) { bitmap.asComposeImageBitmap() }
    var frames by remember(song.url) { mutableIntStateOf(0) }
    var message by remember(song.url) { mutableStateOf("Finding the video…") }
    LaunchedEffect(player, song.url) {
        val length = song.durationSeconds.takeIf { it > 0 } ?: (player.durationMs() / 1000)
        val source = withContext(Dispatchers.IO) { find(song, length) }
        val info = ImageInfo(FRAME_WIDTH, FRAME_HEIGHT, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val played = source != null && playVideoFrames(source.videoUrl, source.durationSeconds * 1000, player::clockMs,
            { player.now.playing && !player.now.loading }) { frame, _ ->
            // Compose draws on this thread, so the pixels are never replaced in the middle of a draw.
            withContext(Dispatchers.Main) { bitmap.installPixels(info, frame, FRAME_WIDTH * 4); frames++ }
        }
        if (!played) { frames = 0; message = "Music video unavailable" }
    }
    if (frames == 0) VideoPlaceholder(song, message, modifier)
    else Canvas(modifier) {
        frames // redraw for each new frame
        val scale = min(size.width / FRAME_WIDTH, size.height / FRAME_HEIGHT)
        val width = (FRAME_WIDTH * scale).roundToInt()
        val height = (FRAME_HEIGHT * scale).roundToInt()
        drawImage(image, dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
            dstSize = IntSize(width, height))
    }
}
