package app.tunes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

@Immutable
data class ChatSticker(val id: String, val pack: String, val caption: String, val description: String, internal val expression: Int)

/** Only these compact IDs travel over chat. Bundled photo GIFs are decoded locally on both platforms. */
object StickerCatalog {
    val entries = listOf(
ChatSticker("jomok-waduh", "Jomok", "WADUH!", "Steve Harvey surprised reaction GIF: Waduh!", 0),
ChatSticker("jomok-santuy", "Jomok", "SANTUY", "Dreamy relaxed smile GIF: Santuy", 1),
ChatSticker("jomok-wkwk", "Jomok", "WKWKWK", "Eddie Murphy laughing GIF: Wkwkwk", 2),
ChatSticker("jomok-bruh", "Jomok", "BRUH…", "Conceited skeptical reaction GIF: Bruh", 3),
ChatSticker("jawir-gas", "Jawir", "GASKEUN!", "Khaby Lame palms-up reaction GIF: Gaskeun!", 4),
ChatSticker("jawir-ngopi", "Jawir", "NGOPI DULU", "Morgan Freeman coffee toast GIF: Ngopi dulu", 5),
ChatSticker("jawir-siap", "Jawir", "SIAP, BOS!", "Roll Safe temple-tap reaction GIF: Siap, bos!", 6),
ChatSticker("jawir-mantap", "Jawir", "MANTAP!", "Denzel Washington approving smile GIF: Mantap!", 7),
    )
    val ids: Set<String> = entries.map { it.id }.toSet()
    private val indexed = entries.associateBy { it.id }
    fun byId(id: String): ChatSticker? = indexed[id]
}

@Composable
fun Sticker(stickerId: String, modifier: Modifier = Modifier, animate: Boolean = true) {
    val sticker = StickerCatalog.byId(stickerId)
    if (sticker == null) {
        Text("Sticker unavailable", modifier, style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = sticker.description }, horizontalAlignment = Alignment.CenterHorizontally) {
        StickerGifOrFallback(sticker, Modifier.fillMaxWidth().aspectRatio(1f), animate) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.35f)) {
            scale(size.width / 160f, size.height / 118f, pivot = Offset.Zero) {
                val ink = Color(0xFF151515)
                val paper = Color(0xFFFAFAFA)
                val line = Stroke(3.5f, cap = StrokeCap.Round)
                fun segment(x: Float, y: Float, x2: Float, y2: Float, width: Float = 3.5f) = drawLine(ink, Offset(x, y), Offset(x2, y2), width, StrokeCap.Round)
                fun outline(path: Path) { drawPath(path, paper); drawPath(path, ink, style = line) }
                fun arc(x: Float, y: Float, width: Float, height: Float, start: Float = 0f, sweep: Float = 180f) =
                    drawArc(ink, start, sweep, false, Offset(x, y), Size(width, height), style = line)
                val head = Path().apply {
                    moveTo(38f, 42f)
                    if (sticker.pack == "Jomok") {
                        lineTo(37f, 12f); quadraticTo(48f, 11f, 62f, 27f)
                        quadraticTo(80f, 19f, 98f, 27f); quadraticTo(113f, 11f, 123f, 12f); lineTo(121f, 43f)
                    } else {
                        cubicTo(35f, 20f, 59f, 15f, 70f, 24f)
                        cubicTo(84f, 7f, 101f, 21f, 101f, 28f)
                        cubicTo(116f, 19f, 130f, 33f, 122f, 47f)
                    }
                    cubicTo(142f, 88f, 117f, 108f, 80f, 108f)
                    cubicTo(40f, 108f, 18f, 87f, 38f, 42f)
                    close()
                }
                outline(head)
                when (sticker.expression) {
                    0 -> {
                        drawOval(ink, Offset(55f, 49f), Size(9f, 16f)); drawOval(ink, Offset(96f, 49f), Size(9f, 16f))
                        drawOval(ink, Offset(72f, 75f), Size(16f, 20f))
                        segment(15f, 29f, 23f, 43f); segment(137f, 29f, 145f, 19f)
                    }
                    1 -> {
                        drawRoundRect(ink, Offset(46f, 48f), Size(31f, 23f), CornerRadius(6f))
                        drawRoundRect(ink, Offset(83f, 48f), Size(31f, 23f), CornerRadius(6f))
                        segment(76f, 53f, 85f, 53f); segment(48f, 55f, 39f, 52f); segment(112f, 55f, 121f, 52f)
                        arc(67f, 73f, 29f, 13f)
                    }
                    2 -> {
                        arc(48f, 48f, 23f, 17f, 190f, 160f); arc(89f, 48f, 23f, 17f, 190f, 160f)
                        drawOval(ink, Offset(65f, 68f), Size(31f, 28f)); drawOval(paper, Offset(70f, 71f), Size(21f, 7f))
                        segment(26f, 66f, 18f, 74f); segment(135f, 66f, 143f, 74f)
                    }
                    3 -> { segment(49f, 53f, 70f, 53f); segment(91f, 53f, 112f, 53f); segment(60f, 53f, 60f, 65f); segment(101f, 53f, 101f, 65f); segment(70f, 84f, 91f, 84f) }
                    else -> {
                        if (sticker.expression == 5) {
                            arc(49f, 52f, 22f, 13f, 190f, 160f); arc(91f, 52f, 22f, 13f, 190f, 160f)
                        } else { drawCircle(ink, 5f, Offset(60f, 56f)); drawCircle(ink, 5f, Offset(101f, 56f)) }
                        arc(64f, 67f, 33f, 20f)
                    }
                }
                when (sticker.expression) {
                    4 -> {
                        segment(13f, 48f, 27f, 48f); segment(7f, 66f, 23f, 66f); segment(14f, 84f, 26f, 84f)
                        val star = Path().apply { moveTo(133f, 19f); lineTo(138f, 29f); lineTo(149f, 32f); lineTo(138f, 37f); lineTo(134f, 48f); lineTo(130f, 37f); lineTo(120f, 32f); lineTo(130f, 29f); close() }
                        outline(star)
                    }
                    5 -> {
                        drawRoundRect(paper, Offset(53f, 79f), Size(54f, 31f), CornerRadius(7f))
                        drawRoundRect(ink, Offset(53f, 79f), Size(54f, 31f), CornerRadius(7f), style = line)
                        arc(100f, 83f, 21f, 17f, -90f, 180f)
                        segment(70f, 70f, 67f, 61f, 2f); segment(82f, 70f, 85f, 60f, 2f)
                    }
                    6 -> {
                        val hand = Path().apply { moveTo(117f, 57f); lineTo(139f, 38f); quadraticTo(146f, 37f, 144f, 44f); lineTo(127f, 66f); close() }
                        outline(hand)
                    }
                    7 -> {
                        val hand = Path().apply { moveTo(118f, 90f); lineTo(118f, 73f); lineTo(128f, 67f); lineTo(130f, 52f); quadraticTo(139f, 49f, 139f, 60f); lineTo(137f, 73f); lineTo(147f, 73f); quadraticTo(153f, 76f, 148f, 94f); lineTo(124f, 97f); close() }
                        outline(hand)
                    }
                }
            }
        }
        Text(sticker.caption, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        }
    }
}
