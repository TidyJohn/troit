package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File

/** [normalize] evens out loudness between songs; off by default because it alters the original dynamics. */
data class TransitionSettings(val gapless: Boolean = true, val crossfadeSeconds: Int = 0, val normalize: Boolean = false) {
    fun sanitized() = copy(crossfadeSeconds = crossfadeSeconds.coerceIn(0, 12))
    internal fun save(file: File) = file.writeAtomically(JSONObject().put("gapless", gapless)
        .put("crossfade_seconds", crossfadeSeconds.coerceIn(0, 12)).put("normalize", normalize).toString())
    companion object {
        internal fun load(file: File?): TransitionSettings = runCatching {
            val json = JSONObject(requireNotNull(file).readText())
            TransitionSettings(json.optBoolean("gapless", true), json.optInt("crossfade_seconds", 0),
                json.optBoolean("normalize", false)).sanitized()
        }.getOrDefault(TransitionSettings())
    }
}

@Composable
fun TransitionSettingsDialog(player: MusicPlayer, onDismiss: () -> Unit) {
    var seconds by remember { mutableFloatStateOf(player.transitionSettings.crossfadeSeconds.toFloat()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Playback settings") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("On this device", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Gapless playback", style = MaterialTheme.typography.titleMedium)
                    Text("Prepare the next queued song before this one finishes.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(player.transitionSettings.gapless, onCheckedChange = {
                    player.setTransitionSettings(player.transitionSettings.copy(gapless = it))
                })
            }
            Text(if (seconds.toInt() == 0) "Crossfade · Off" else "Crossfade · ${seconds.toInt()} seconds")
            Slider(seconds, onValueChange = { seconds = it }, valueRange = 0f..12f, steps = 11,
                onValueChangeFinished = { player.setTransitionSettings(player.transitionSettings.copy(crossfadeSeconds = seconds.toInt())) })
            Text("Crossfade overlaps the end and start of queued songs. At 0 seconds, gapless playback applies. These settings apply when this device plays audio.",
                style = MaterialTheme.typography.bodySmall)
            if (player.canNormalize) Row {
                Column(Modifier.weight(1f)) {
                    Text("Normalize volume", style = MaterialTheme.typography.titleMedium)
                    Text("Evens out loud and quiet songs. Slightly changes the original dynamics.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(player.transitionSettings.normalize, onCheckedChange = {
                    player.setTransitionSettings(player.transitionSettings.copy(normalize = it))
                })
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
