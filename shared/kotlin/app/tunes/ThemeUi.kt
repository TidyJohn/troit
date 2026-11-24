package app.tunes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeScreen(state: UserThemeState, onOpenAccount: () -> Unit = {}) {
    val settings = state.settings
    val scroll = rememberLazyListState()
    LazyColumn(
        state = scroll, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "theme-heading", contentType = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A look that's yours.", style = MaterialTheme.typography.headlineLarge)
                Text("Choose your canvas. Pick your accent. Keep the same look on every device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "theme-preview", contentType = "preview") { ThemePreview(settings) }
        item(key = "theme-choice", contentType = "choices") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your canvas", style = MaterialTheme.typography.titleLarge)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                val tileWidth = if (maxWidth < 350.dp) (maxWidth - 12.dp) / 2 else 160.dp
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeCatalog.themes.forEach { option ->
                        ThemeOptionCard(option, settings.accentId, selected = settings.themeId == option.id, width = tileWidth) { state.selectTheme(option.id) }
                    }
                }
                }
            }
        }
        item(key = "theme-accent", contentType = "choices") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your accent", style = MaterialTheme.typography.titleLarge)
                Text("Control colors adapt to keep text readable in light and dark themes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeCatalog.accents.forEach { option ->
                        val accent = remember(settings.themeId, option.id) { themeAccent(ThemeSettings(settings.themeId, option.id)) }
                        FilterChip(
                            selected = settings.accentId == option.id,
                            onClick = { state.selectAccent(option.id) },
                            label = { Text(option.title) },
                            leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(accent)) },
                        )
                    }
                }
            }
        }
        item(key = "theme-account", contentType = "status") {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else TuneIcon("theme", Modifier.size(21.dp), MaterialTheme.colorScheme.primary)
                        Text(if (state.signedIn) "Your account, your look" else "Make it follow you", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.signedIn) TextButton(onClick = state::retry, enabled = !state.syncing) { Text("Sync theme now") }
                    else TextButton(onClick = onOpenAccount) { Text("Connect your account") }
                }
            }
        }
        if (state.conflict != null) item(key = "theme-conflict", contentType = "conflict") {
            Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Another device changed your theme", style = MaterialTheme.typography.titleMedium)
                    Text("Your local choice is still applied. Use the other device's theme, or keep this one for your account.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = state::useCloudTheme) { Text("Use other device's theme") }
                        TextButton(onClick = state::keepLocalTheme) { Text("Keep mine") }
                    }
                }
            }
        }
        item(key = "theme-reset", contentType = "footer") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme choices are separate for each account. When signed out, the device uses its own theme.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = state::reset, enabled = settings != ThemeSettings()) { Text("Reset to Troit's original look") }
            }
        }
    }
}

@Composable
private fun ThemeOptionCard(choice: ThemeChoice, accentId: String, selected: Boolean, width: Dp, onClick: () -> Unit) {
    val colors = remember(choice.id, accentId) { troitColorScheme(ThemeSettings(choice.id, accentId)) }
    Surface(
        modifier = Modifier.width(width).semantics { this.selected = selected; contentDescription = "${choice.title} theme" }
            .clickable(role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(10.dp)).background(colors.background).padding(9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(20.dp).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(colors.surfaceContainerHigh))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(13.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceVariant))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(colors.primary))
                        Box(Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(5.dp)).background(colors.surfaceContainer))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(choice.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                if (selected) TuneIcon("check", Modifier.size(17.dp), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ThemePreview(settings: ThemeSettings) {
    val colors = remember(settings) { troitColorScheme(settings) }
    Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainer, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("LIVE PREVIEW", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                    TuneIcon("music", Modifier.size(26.dp), colors.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text("Your soundtrack", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.onSurface)
                    Text("Same music. A different mood.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Box(Modifier.size(46.dp).clip(CircleShape).background(colors.primary), contentAlignment = Alignment.Center) {
                    TuneIcon("play", Modifier.size(22.dp), colors.onPrimary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.weight(.4f).height(4.dp).clip(CircleShape).background(colors.primary))
                Box(Modifier.weight(.6f).height(4.dp).clip(CircleShape).background(colors.surfaceVariant))
            }
        }
    }
}
