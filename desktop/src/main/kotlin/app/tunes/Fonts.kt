package app.tunes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** Bundled static weights avoid network font loading and synthetic bold during scrolling. */
@Composable
fun rememberTroitFontFamily(): FontFamily = remember {
    FontFamily(
        Font("font/manrope_regular.ttf", weight = FontWeight.Normal),
        Font("font/manrope_medium.ttf", weight = FontWeight.Medium),
        Font("font/manrope_semibold.ttf", weight = FontWeight.SemiBold),
        Font("font/manrope_bold.ttf", weight = FontWeight.Bold),
        Font("font/manrope_extrabold.ttf", weight = FontWeight.ExtraBold),
    )
}
