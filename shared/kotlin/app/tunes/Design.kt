package app.tunes

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val TroitColors = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF242424), onPrimaryContainer = Color.White,
    inversePrimary = Color(0xFF333333),
    secondary = Color(0xFFD9D9D9), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF242424), onSecondaryContainer = Color.White,
    primaryFixed = Color.White, primaryFixedDim = Color(0xFFD9D9D9),
    onPrimaryFixed = Color.Black, onPrimaryFixedVariant = Color(0xFF333333),
    secondaryFixed = Color(0xFFE8E8E8), secondaryFixedDim = Color(0xFFCCCCCC),
    onSecondaryFixed = Color.Black, onSecondaryFixedVariant = Color(0xFF333333),
    tertiary = Color(0xFFCCCCCC), onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF242424), onTertiaryContainer = Color.White,
    tertiaryFixed = Color(0xFFE8E8E8), tertiaryFixedDim = Color(0xFFCCCCCC),
    onTertiaryFixed = Color.Black, onTertiaryFixedVariant = Color(0xFF333333),
    background = Color.Black, onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF0B0B0B), onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF202020), onSurfaceVariant = Color(0xFFAAAAAA),
    surfaceDim = Color(0xFF080808), surfaceBright = Color(0xFF303030),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF161616), surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF2A2A2A),
    outline = Color(0xFF555555), outlineVariant = Color(0xFF292929),
    inverseSurface = Color(0xFFE8E8E8), inverseOnSurface = Color(0xFF111111),
    surfaceTint = Color.Transparent, scrim = Color.Black,
    error = Color.White, onError = Color.Black,
    errorContainer = Color(0xFF333333), onErrorContainer = Color.White,
)

@Composable
fun TroitTheme(settings: ThemeSettings = ThemeSettings(), content: @Composable () -> Unit) {
    val family = rememberTroitFontFamily()
    val typography = remember(family) { troitTypography(family) }
    val colors = remember(settings) { troitColorScheme(settings) }
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/** A finite validated palette keeps controls readable and the native frame in the same theme. */
internal fun troitColorScheme(settings: ThemeSettings): ColorScheme {
    val selected = settings.normalized()
    val base = when (selected.themeId) {
        "midnight" -> TroitColors.copy(
            background = Color(0xFF080B14), surface = Color(0xFF101521),
            surfaceDim = Color(0xFF080B14), surfaceBright = Color(0xFF30384C),
            surfaceContainerLowest = Color(0xFF060910), surfaceContainerLow = Color(0xFF111725),
            surfaceContainer = Color(0xFF171E2E), surfaceContainerHigh = Color(0xFF202A3E),
            surfaceContainerHighest = Color(0xFF29344A), surfaceVariant = Color(0xFF242E42),
            outline = Color(0xFF63718A), outlineVariant = Color(0xFF2E3B51),
        )
        "paper" -> TroitColors.copy(
            primary = Color(0xFF111111), onPrimary = Color.White,
            primaryContainer = Color(0xFFE1E1DF), onPrimaryContainer = Color.Black,
            secondary = Color(0xFF353535), onSecondary = Color.White,
            secondaryContainer = Color(0xFFE5E5E2), onSecondaryContainer = Color.Black,
            tertiary = Color(0xFF454545), onTertiary = Color.White,
            tertiaryContainer = Color(0xFFE5E5E2), onTertiaryContainer = Color.Black,
            background = Color(0xFFF7F7F4), onBackground = Color(0xFF111111),
            surface = Color(0xFFFEFEFC), onSurface = Color(0xFF111111),
            surfaceVariant = Color(0xFFE8E8E4), onSurfaceVariant = Color(0xFF555550),
            surfaceDim = Color(0xFFE0E0DB), surfaceBright = Color.White,
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F3EF),
            surfaceContainer = Color(0xFFEEEEE9), surfaceContainerHigh = Color(0xFFE7E7E2),
            surfaceContainerHighest = Color(0xFFE0E0DA),
            outline = Color(0xFF858580), outlineVariant = Color(0xFFD5D5CF),
            inverseSurface = Color(0xFF242424), inverseOnSurface = Color(0xFFF5F5F2),
            inversePrimary = Color.White, error = Color(0xFF333333), onError = Color.White,
            errorContainer = Color(0xFFE4E4DF), onErrorContainer = Color(0xFF111111),
        )
        "rose" -> TroitColors.copy(
            background = Color(0xFF130B10), surface = Color(0xFF1C1118),
            surfaceDim = Color(0xFF130B10), surfaceBright = Color(0xFF45313E),
            surfaceContainerLowest = Color(0xFF10090D), surfaceContainerLow = Color(0xFF21151D),
            surfaceContainer = Color(0xFF291B25), surfaceContainerHigh = Color(0xFF34232F),
            surfaceContainerHighest = Color(0xFF412E3B), surfaceVariant = Color(0xFF382631),
            outline = Color(0xFF9F7B8F), outlineVariant = Color(0xFF4E3543),
        )
        "ocean" -> TroitColors.copy(
            background = Color(0xFF061015), surface = Color(0xFF0D1B22),
            surfaceDim = Color(0xFF061015), surfaceBright = Color(0xFF27454F),
            surfaceContainerLowest = Color(0xFF040D11), surfaceContainerLow = Color(0xFF10222B),
            surfaceContainer = Color(0xFF162B35), surfaceContainerHigh = Color(0xFF1C3540),
            surfaceContainerHighest = Color(0xFF24424D), surfaceVariant = Color(0xFF203B46),
            outline = Color(0xFF7297A6), outlineVariant = Color(0xFF2C4D5B),
        )
        else -> TroitColors
    }
    val accent = themeAccent(selected)
    if (selected.themeId == "monochrome" && selected.accentId == "neutral") return base
    if (selected.themeId == "paper" && selected.accentId == "neutral") return base
    val light = selected.themeId == "paper"
    val onAccent = if (light) Color.White else Color(0xFF081010)
    return base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = 1f).let { blendColors(base.surfaceContainer, it, if (light) .15f else .18f) },
        onPrimaryContainer = if (light) Color(0xFF141414) else Color.White,
        secondary = accent, onSecondary = onAccent,
        secondaryContainer = blendColors(base.surfaceContainer, accent, .12f),
        onSecondaryContainer = if (light) Color(0xFF141414) else Color.White,
        tertiary = accent, onTertiary = onAccent,
        tertiaryContainer = blendColors(base.surfaceContainer, accent, .12f),
        onTertiaryContainer = if (light) Color(0xFF141414) else Color.White,
        primaryFixed = if (light) blendColors(Color.White, accent, .18f) else accent,
        primaryFixedDim = if (light) blendColors(Color.White, accent, .3f) else blendColors(accent, Color.Black, .15f),
        onPrimaryFixed = Color.Black, onPrimaryFixedVariant = Color(0xFF242424),
    )
}

internal fun themeAccent(settings: ThemeSettings): Color {
    val light = settings.themeId == "paper"
    return when (settings.accentId) {
        "coral" -> if (light) Color(0xFF9D3140) else Color(0xFFFFB4AB)
        "mint" -> if (light) Color(0xFF14664F) else Color(0xFF96E4BE)
        "blue" -> if (light) Color(0xFF245CA6) else Color(0xFFA8CEFF)
        "violet" -> if (light) Color(0xFF6842A3) else Color(0xFFD2BCFF)
        else -> when (settings.themeId) {
            "midnight" -> Color(0xFFBBC8FF)
            "rose" -> Color(0xFFFFB3CA)
            "ocean" -> Color(0xFF8CCFFF)
            "paper" -> Color(0xFF111111)
            else -> Color.White
        }
    }
}

private fun blendColors(from: Color, to: Color, amount: Float) = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
    alpha = 1f,
)

/** Constructed once per platform font family, rather than during scrolling recompositions. */
private fun troitTypography(family: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp),
        displayMedium = base.displayMedium.copy(fontFamily = family, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.3).sp),
        displaySmall = base.displaySmall.copy(fontFamily = family, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp),
        titleLarge = base.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = family, lineHeight = 25.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
    )
}

/** Names an icon-only control on hover (PC) or long-press (touch), so its meaning never has to be guessed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Hint(text: String, content: @Composable () -> Unit) = TooltipBox(
    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
    tooltip = { PlainTooltip { Text(text) } }, state = rememberTooltipState(), content = content)

/** Small code-native icons shared by Android and desktop, without a font or network dependency. */
@Composable
fun TuneIcon(name: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x, y), Offset(x2, y2), 1.7f, StrokeCap.Round)
            fun path(vararg points: Float, fill: Boolean = false) {
                val p = Path().apply {
                    moveTo(points[0], points[1])
                    for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                    if (fill) close()
                }
                drawPath(p, tint, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
            }
            when (name) {
                "home" -> { path(3f, 11f, 12f, 3f, 21f, 11f); path(5f, 10f, 5f, 21f, 10f, 21f, 10f, 15f, 14f, 15f, 14f, 21f, 19f, 21f, 19f, 10f) }
                "search" -> { drawCircle(tint, 7f, Offset(10.5f, 10.5f), style = stroke); line(16f, 16f, 21f, 21f) }
                "library" -> { line(4f, 4f, 4f, 20f); line(9f, 4f, 9f, 20f); path(14f, 5f, 18f, 4f, 22f, 19f, 18f, 20f, 14f, 5f) }
                "globe" -> { drawCircle(tint, 9f, Offset(12f, 12f), style = stroke); drawOval(tint, Offset(8f, 3f), androidx.compose.ui.geometry.Size(8f, 18f), style = stroke); line(3f, 12f, 21f, 12f) }
                "settings" -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f); drawCircle(tint, 2f, Offset(8f, 6f), style = stroke); drawCircle(tint, 2f, Offset(16f, 12f), style = stroke); drawCircle(tint, 2f, Offset(10f, 18f), style = stroke) }
                "friends" -> { drawCircle(tint, 3.5f, Offset(9f, 7f), style = stroke); drawCircle(tint, 2.5f, Offset(18f, 8f), style = stroke); path(2f, 21f, 2f, 18f, 5f, 14f, 13f, 14f, 16f, 18f, 16f, 21f); path(18f, 14f, 21f, 16f, 22f, 21f) }
                "chat" -> path(3f, 4f, 21f, 4f, 21f, 17f, 9f, 17f, 3f, 21f, 3f, 4f)
                "send" -> { path(21f, 3f, 3f, 10f, 11f, 13f, 14f, 21f, 21f, 3f); line(11f, 13f, 21f, 3f) }
                "sticker" -> { path(4f, 3f, 21f, 3f, 21f, 14f, 14f, 21f, 4f, 21f, 4f, 3f); path(21f, 14f, 14f, 14f, 14f, 21f); drawCircle(tint, .8f, Offset(9f, 9f)); drawCircle(tint, .8f, Offset(16f, 9f)); path(9f, 13f, 12f, 15f, 15f, 13f) }
                "theme" -> { drawCircle(tint, 8f, Offset(12f, 12f), style = stroke); drawArc(tint, -90f, 180f, true, Offset(4f, 4f), androidx.compose.ui.geometry.Size(16f, 16f)) }
                "play" -> path(8f, 5f, 20f, 12f, 8f, 19f, fill = true)
                "pause" -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
                "next" -> { path(5f, 5f, 16f, 12f, 5f, 19f, fill = true); line(19f, 5f, 19f, 19f) }
                "previous" -> { path(19f, 5f, 8f, 12f, 19f, 19f, fill = true); line(5f, 5f, 5f, 19f) }
                "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
                "check" -> path(5f, 12f, 10f, 17f, 20f, 7f)
                "close" -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
                "more" -> { drawCircle(tint, 1.5f, Offset(12f, 5f)); drawCircle(tint, 1.5f, Offset(12f, 12f)); drawCircle(tint, 1.5f, Offset(12f, 19f)) }
                "back" -> { path(12f, 5f, 5f, 12f, 12f, 19f); line(5f, 12f, 21f, 12f) }
                "arrow" -> { path(14f, 5f, 21f, 12f, 14f, 19f); line(3f, 12f, 21f, 12f) }
                "queue" -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 15f, 12f); line(4f, 18f, 12f, 18f); path(18f, 15f, 22f, 18f, 18f, 21f, fill = true) }
                "devices" -> { path(3f, 4f, 18f, 4f, 18f, 15f, 3f, 15f, 3f, 4f); line(8f, 15f, 8f, 20f); line(4f, 20f, 12f, 20f); path(15f, 10f, 22f, 10f, 22f, 21f, 15f, 21f, 15f, 10f) }
                "shuffle" -> { path(3f, 6f, 7f, 6f, 17f, 18f, 21f, 18f); path(3f, 18f, 7f, 18f, 17f, 6f, 21f, 6f); path(18f, 3f, 21f, 6f, 18f, 9f); path(18f, 15f, 21f, 18f, 18f, 21f) }
                "volume" -> { path(4f, 9f, 8f, 9f, 13f, 5f, 13f, 19f, 8f, 15f, 4f, 15f, 4f, 9f); drawArc(tint, -50f, 100f, false, Offset(9f, 6f), Size(12f, 12f), style = stroke) }
                "video" -> { drawRoundRect(tint, Offset(3f, 5f), Size(18f, 14f), CornerRadius(3f, 3f), style = stroke); path(10f, 9f, 15f, 12f, 10f, 15f, fill = true) }
                "download" -> { line(12f, 4f, 12f, 15f); path(7f, 11f, 12f, 16f, 17f, 11f); line(5f, 20f, 19f, 20f) }
                "stop" -> drawRoundRect(tint, Offset(6f, 6f), Size(12f, 12f), CornerRadius(2f, 2f), style = stroke)
                "repeat", "repeat_one" -> {
                    path(4f, 10f, 4f, 6f, 20f, 6f); path(17f, 3f, 20f, 6f, 17f, 9f)
                    path(20f, 14f, 20f, 18f, 4f, 18f); path(7f, 15f, 4f, 18f, 7f, 21f)
                    if (name == "repeat_one") { path(10f, 11f, 12f, 10f, 12f, 14f); line(10f, 14f, 14f, 14f) }
                }
                "radio" -> {
                    drawCircle(tint, 2f, Offset(12f, 12f))
                    drawArc(tint, -55f, 110f, false, Offset(5f, 5f), Size(14f, 14f), style = stroke)
                    drawArc(tint, 125f, 110f, false, Offset(5f, 5f), Size(14f, 14f), style = stroke)
                    drawArc(tint, -55f, 110f, false, Offset(1f, 1f), Size(22f, 22f), style = stroke)
                    drawArc(tint, 125f, 110f, false, Offset(1f, 1f), Size(22f, 22f), style = stroke)
                }
                else -> { path(9f, 18f, 9f, 5f, 19f, 3f, 19f, 16f); drawCircle(tint, 3f, Offset(6f, 18f)); drawCircle(tint, 3f, Offset(16f, 16f)) }
            }
        }
    }
}
