package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val LocalOpenListeningLibrary = staticCompositionLocalOf<() -> Unit> { {} }
val LocalOpenLyrics = staticCompositionLocalOf<() -> Unit> { {} }
val LocalOpenSleepTimer = staticCompositionLocalOf<() -> Unit> { {} }
val LocalOpenParty = staticCompositionLocalOf<() -> Unit> { {} }
val LocalOpenTransitions = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Everything without a tab of its own, in three short groups: your music, what's playing, settings.
 * Each label is the title of the place it opens.
 */
@Composable
fun ListeningFeaturesMenu(television: Boolean = false, onAppearance: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    // Downloading starts from song menus, which the remote-driven TV screens don't have.
    val groups = listOf(
        listOfNotNull("Your listening" to LocalOpenListeningLibrary.current, if (television) null else "Downloads" to LocalOpenDownloads.current),
        listOf("Lyrics" to LocalOpenLyrics.current, "Sleep timer" to LocalOpenSleepTimer.current, "Party queue" to LocalOpenParty.current),
        listOfNotNull("Playback settings" to LocalOpenTransitions.current, onAppearance?.let { "Appearance" to it }),
    )
    Box {
        if (television) TvAction("More", onClick = { expanded = true })
        else Hint("More") {
            IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "More" }) {
                TuneIcon("more", Modifier.size(22.dp))
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            groups.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider()
                group.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() })
                }
            }
        }
    }
}

@Composable
fun ListeningLibraryDialog(onPlay: Play, television: Boolean, onDismiss: () -> Unit) {
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 900.dp).fillMaxWidth().heightIn(max = if (television) 500.dp else 760.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Text("Your listening", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    EcosystemAction("Done", television, onClick = onDismiss)
                }
                Box(Modifier.weight(1f)) { ListeningLibraryScreen(onPlay, television) }
            }
        }
    }
}
