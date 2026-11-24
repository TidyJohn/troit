package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val LocalOpenDownloads = staticCompositionLocalOf<() -> Unit> { {} }

/** Everything saved for offline listening on this device, newest first. */
@Composable
fun DownloadsDialog(onPlay: Play, onDismiss: () -> Unit) {
    val entries = Downloads.entries
    val songs = remember(entries) { entries.asReversed().map { it.song } }
    var confirmRemoveAll by remember { mutableStateOf(false) }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 760.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloads", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text(if (songs.isEmpty()) "Downloaded songs play without a connection. Use Download in a song's menu, or Download all on a playlist."
                    else "${songs.size} songs · ${downloadSize(Downloads.totalBytes)} on this device",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (Downloads.progress.isNotEmpty()) {
                    Text("Downloading ${Downloads.progress.size} more. Keep Troit open until they finish.", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Downloads.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (songs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPlay(songs, 0) }) { Text("Play all") }
                    TextButton(onClick = {
                        if (confirmRemoveAll) { Downloads.remove(songs.map { it.url }); confirmRemoveAll = false } else confirmRemoveAll = true
                    }) { Text(if (confirmRemoveAll) "Tap again to remove all" else "Remove all") }
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    itemsIndexed(songs, key = { _, song -> song.url }) { index, song ->
                        ItemRow(song, trailing = { TextButton(onClick = { Downloads.remove(listOf(song.url)) }) { Text("Remove") } }) { onPlay(songs, index) }
                    }
                }
            }
        }
    }
}

internal fun downloadSize(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "%.0f MB".format(bytes / (1L shl 20).toDouble())
