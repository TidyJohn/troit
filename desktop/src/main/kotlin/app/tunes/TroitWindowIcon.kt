package app.tunes

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter

/** Monochrome Troit music mark shared by the native title bar and taskbar. */
internal object TroitWindowIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val ink = Color.Black
        drawRoundRect(Color.White, cornerRadius = CornerRadius(size.width * .28f))
        scale(size.width / 32f, size.height / 32f, pivot = Offset.Zero) {
            val note = Path().apply {
                moveTo(13f, 24f)
                lineTo(13f, 10f)
                lineTo(25f, 7f)
                lineTo(25f, 21f)
            }
            drawPath(note, ink, style = Stroke(2.2f, cap = StrokeCap.Round))
            drawOval(ink, Offset(5f, 21f), Size(8f, 6f))
            drawOval(ink, Offset(17f, 18f), Size(8f, 6f))
        }
    }
}
