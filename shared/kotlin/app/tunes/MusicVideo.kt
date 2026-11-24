package app.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** [audioUrl] is null when the picture stream already carries its own sound. */
internal data class VideoSource(val title: String, val videoUrl: String, val audioUrl: String?, val durationSeconds: Long = 0)

/**
 * Draws the song's music video, silent and timed against the audio that is already playing, so turning
 * the picture on or off never interrupts the music. Each platform provides its own.
 */
val LocalVideoSurface = staticCompositionLocalOf<(@Composable (Song, Modifier) -> Unit)?> { null }

/** Whether the video panel is showing. Null where the platform can't draw video, which hides the button. */
val LocalVideoOpen = staticCompositionLocalOf<MutableState<Boolean>?> { null }

/**
 * How a silent picture keeps up with the sound it accompanies. [driftMs] is picture minus sound.
 * Null: too far apart, jump to just ahead of the sound. 0: the picture is ahead, hold its frame until the
 * sound arrives (a jump lands here, so it never chases a moving target). Otherwise the speed to play at;
 * a few percent faster is invisible and a silent picture has no pitch to give it away.
 */
internal fun pictureSyncSpeed(driftMs: Long): Float? = when {
    driftMs > 2_000 || driftMs < -500 -> null
    driftMs > 50 -> 0f
    driftMs < -50 -> 1.05f
    else -> 1f
}

/** How far ahead of the sound a jump lands, leaving the picture time to load before the sound gets there. */
internal const val PICTURE_JUMP_LEAD_MS = 800L

/** One button in the player bar: lit while the picture is showing, and the same button hides it again. */
@Composable
internal fun VideoToggleButton() {
    val open = LocalVideoOpen.current ?: return
    val label = if (open.value) "Hide music video" else "Show music video"
    TextButton(onClick = { open.value = !open.value }, modifier = Modifier.semantics { contentDescription = label }) {
        TuneIcon("video", Modifier.size(18.dp))
        Text(if (open.value) "Hide video" else "Video", Modifier.padding(start = 4.dp))
    }
}

/**
 * The picture beside the music, never over it: a side column on wide windows, a short strip above the
 * player on phones. [compact] picks the strip.
 */
@Composable
internal fun VideoPanel(song: Song, compact: Boolean, modifier: Modifier = Modifier) {
    val surface = LocalVideoSurface.current ?: return
    val open = LocalVideoOpen.current ?: return
    val close = @Composable {
        IconButton(onClick = { open.value = false }, modifier = Modifier.semantics { contentDescription = "Hide music video" }) {
            TuneIcon("close", Modifier.size(18.dp))
        }
    }
    val picture = @Composable { size: Modifier ->
        Box(size.aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) { surface(song, Modifier.fillMaxSize()) }
    }
    val caption = @Composable {
        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (compact) Row(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        picture(Modifier.width(176.dp))
        Column(Modifier.weight(1f)) { caption() }
        close()
    } else Column(modifier.width(300.dp).padding(top = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MUSIC VIDEO", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            close()
        }
        picture(Modifier.fillMaxWidth())
        caption()
    }
}

/** What both platforms show in the picture's place until the first frame arrives, or when there is none. */
@Composable
fun VideoPlaceholder(song: Song, message: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Artwork(song.artwork, Modifier.fillMaxSize(), RectangleShape)
        Text(message, Modifier.background(Color.Black.copy(alpha = .6f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}
