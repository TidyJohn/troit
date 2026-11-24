package app.tunes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

val LocalListeningPreferences = staticCompositionLocalOf { ListeningPreferences() }
val LocalRegionPreferences = staticCompositionLocalOf<RegionPreferences?> { null }
val LocalOpenPreferences = staticCompositionLocalOf<() -> Unit> { {} }

/** A persistent, visible entry point wherever music is discovered. */
@Composable
fun TasteSummary(modifier: Modifier = Modifier) {
    val settings = LocalListeningPreferences.current
    val open = LocalOpenPreferences.current
    val market = remember(settings.market) { MusicRegions.options.firstOrNull { it.code == settings.market }?.name ?: "Automatic region" }
    val hidden = settings.excludedRegions.size + settings.excludedLanguages.size
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TuneIcon("globe", Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(market, style = MaterialTheme.typography.labelLarge)
                Text(if (hidden == 0) "Discovery region · Change" else "$hidden active music filters · Edit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TuneIcon("arrow", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasteScreen(preferences: RegionPreferences) {
    val settings = preferences.settings
    var countryQuery by rememberSaveable { mutableStateOf("") }
    var languageQuery by rememberSaveable { mutableStateOf("") }
    var marketMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun change(action: () -> Unit) {
        runCatching(action).onSuccess { error = null }.onFailure { error = "Couldn't save your preferences. Please try again." }
    }
    val countryOptions = remember(countryQuery) { MusicRegions.options.filter { it.name.contains(countryQuery, true) || it.code.equals(countryQuery, true) } }
    val languageOptions = remember(languageQuery) { MusicLanguages.options.filter { it.name.contains(languageQuery, true) } }
    val market = remember(settings.market) { MusicRegions.options.firstOrNull { it.code == settings.market }?.name ?: "Automatic · Based on your network" }
    val presetActive = remember(settings.excludedRegions, settings.excludedLanguages) {
        "IN" in settings.excludedRegions && MusicLanguages.indianMusicCodes.all { it in settings.excludedLanguages }
    }
    val listState = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "preferences-header", contentType = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Music preferences", style = MaterialTheme.typography.headlineLarge)
                Text("Choose a discovery region and the music you want to hear.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "indian-music-preset", contentType = "preset") {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Less Indian music", style = MaterialTheme.typography.titleLarge)
                    Text("Hide India-labeled music and filter Indian music languages with one tap. You can adjust each filter below.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { change {
                        preferences.setIndianMusicExcluded(!presetActive)
                    } }) {
                        TuneIcon(if (presetActive) "check" else "plus", Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (presetActive) "Filters on · Restore" else "Hide Indian music")
                    }
                }
            }
        }
        item(key = "discovery-region", contentType = "market") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Discovery region", style = MaterialTheme.typography.titleLarge)
                Text("Choose which country's recommendations you explore. This does not restrict where artists are from.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { marketMenu = true }) {
                        TuneIcon("globe", Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                        Text(market)
                        Text("  ▾")
                    }
                    DropdownMenu(expanded = marketMenu, onDismissRequest = { marketMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        DropdownMenuItem(text = { Text("Automatic") }, onClick = { change { preferences.setMarket(null) }; marketMenu = false })
                        MusicRegions.options.forEach { country -> DropdownMenuItem(text = { Text(country.name) }, onClick = { change { preferences.setMarket(country.code) }; marketMenu = false }) }
                    }
                }
                if (settings.market in settings.excludedRegions) Text("This discovery region is hidden. Choose another region for more recommendations.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        item(key = "hidden-regions", contentType = "exclusions") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hidden regions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("${settings.excludedRegions.size} hidden", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                Text("Tap a region to hide it. Tap again to bring it back.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(countryQuery, { countryQuery = it }, placeholder = { Text("Find a region, e.g. India") }, singleLine = true, leadingIcon = { TuneIcon("search", Modifier.size(18.dp)) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    countryOptions.forEach { option ->
                        key(option.code) {
                        val hidden = option.code in settings.excludedRegions
                        FilterChip(selected = hidden, onClick = { change { preferences.setRegionExcluded(option.code, !hidden) } }, label = { Text(option.name) }, leadingIcon = if (hidden) ({ TuneIcon("close", Modifier.size(14.dp)) }) else null)
                        }
                    }
                }
                if (countryOptions.isEmpty()) Text("No matching region. Try its country name.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "hidden-languages", contentType = "exclusions") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hidden languages", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("${settings.excludedLanguages.size} hidden", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                Text("Fine-tune music languages separately from regions.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(languageQuery, { languageQuery = it }, placeholder = { Text("Find a language") }, singleLine = true, leadingIcon = { TuneIcon("search", Modifier.size(18.dp)) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    languageOptions.forEach { option ->
                        key(option.code) {
                        val hidden = option.code in settings.excludedLanguages
                        FilterChip(selected = hidden, onClick = { change { preferences.setLanguageExcluded(option.code, !hidden) } }, label = { Text(option.name) }, leadingIcon = if (hidden) ({ TuneIcon("close", Modifier.size(14.dp)) }) else null)
                        }
                    }
                }
                if (languageOptions.isEmpty()) Text("No matching language.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "preferences-help", contentType = "help") {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How music filters work", style = MaterialTheme.typography.titleSmall)
                    Text("Filters use available metadata, music labels, and recognizable writing scripts. The catalog often omits a song's country or language, so some music may still appear. Artist names alone are never treated as nationality.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Your saved playlists stay intact. Filters apply when browsing and starting playback. Preferences are saved on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { change { preferences.reset() } }, enabled = settings != ListeningPreferences()) { Text("Reset all music preferences") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
