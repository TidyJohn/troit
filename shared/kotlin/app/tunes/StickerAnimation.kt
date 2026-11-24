package app.tunes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class StickerGifFrames(val images: List<ImageBitmap>, val delaysMs: List<Int>) {
    val bytes: Long get() = images.sumOf { it.width.toLong() * it.height * 4 }
}
internal typealias StickerGifLoader = (resource: String, animated: Boolean) -> StickerGifFrames?

/** InputStream.readNBytes is not available on every supported Android version. */
internal fun InputStream.readStickerGifBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, StickerGifMetadata.MAX_ENCODED_BYTES + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= StickerGifMetadata.MAX_ENCODED_BYTES) { "Sticker GIF is too large" }
    }
    return output.toByteArray()
}

/** Packaged assets only. Keep native decoded pixels bounded even in long chat histories. */
internal object StickerGifCache {
    private const val MAX_BYTES = 16_000_000L
    private val entries = LinkedHashMap<String, StickerGifFrames>(16, .75f, true)
    @Synchronized fun load(resource: String, animated: Boolean, loader: StickerGifLoader): StickerGifFrames? {
        val key = "$resource:$animated"
        entries[key]?.let { return it }
        val decoded = loader(resource, animated) ?: return null
        if (decoded.bytes > MAX_BYTES) return null
        entries[key] = decoded
        while (entries.values.sumOf { it.bytes } > MAX_BYTES) entries.remove(entries.keys.first())
        return decoded
    }
    @Synchronized internal fun clear() { entries.clear() }
    @Synchronized internal fun decodedBytes() = entries.values.sumOf { it.bytes }
}

@Composable
internal fun StickerGifOrFallback(sticker: ChatSticker, modifier: Modifier, animate: Boolean, fallback: @Composable () -> Unit) {
    val loader = rememberStickerGifLoader()
    val resource = remember(sticker.id) { "sticker_${sticker.id.replace('-', '_')}" }
    var visible by remember { mutableStateOf(false) }
    val playing = animate && visible && LocalAppActive.current
    val frames by produceState<StickerGifFrames?>(null, resource, playing, loader) {
        value = withContext(Dispatchers.IO) { runCatching { StickerGifCache.load(resource, playing, loader) }.getOrNull() }
    }
    val decoded = frames
    var frameIndex by remember(resource) { mutableIntStateOf(0) }
    LaunchedEffect(decoded, playing) {
        frameIndex = 0
        if (decoded != null && playing && decoded.images.size > 1) while (isActive) {
            delay(decoded.delaysMs[frameIndex].coerceAtLeast(80).toLong())
            frameIndex = (frameIndex + 1) % decoded.images.size
        }
    }
    Box(modifier.onGloballyPositioned { coordinates ->
        val root = coordinates.findRootCoordinates().size
        visible = coordinates.isAttached && coordinates.boundsInRoot().overlaps(Rect(0f, 0f, root.width.toFloat(), root.height.toFloat()))
    }, contentAlignment = Alignment.Center) {
        if (decoded == null) fallback()
        else Image(decoded.images[frameIndex.coerceAtMost(decoded.images.lastIndex)], contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** Parse timing/dimensions before native decoding; malformed or oversized bundled assets use the fallback. */
internal data class StickerGifMetadata(val width: Int, val height: Int, val delaysMs: List<Int>, val sourceTimesMs: List<Int>) {
    companion object {
        const val MAX_ENCODED_BYTES = 2_000_000
        fun read(bytes: ByteArray): StickerGifMetadata {
            require(bytes.size in 14..MAX_ENCODED_BYTES)
            require(String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a"))
            fun byte(index: Int): Int { require(index in bytes.indices); return bytes[index].toInt() and 255 }
            fun short(index: Int) = byte(index) or (byte(index + 1) shl 8)
            val width = short(6); val height = short(8)
            require(width in 1..512 && height in 1..512)
            var position = 13
            val packed = byte(10)
            if (packed and 128 != 0) position += 3 * (1 shl ((packed and 7) + 1))
            val delays = ArrayList<Int>(); val times = ArrayList<Int>()
            var nextDelay = 100; var sourceTime = 0
            fun skipBlocks() {
                while (true) {
                    val size = byte(position++)
                    if (size == 0) return
                    position += size
                    require(position <= bytes.size)
                }
            }
            while (position < bytes.size) when (byte(position++)) {
                0x3B -> break
                0x21 -> {
                    val label = byte(position++)
                    if (label == 0xF9) {
                        require(byte(position) == 4)
                        nextDelay = (short(position + 2) * 10).coerceAtLeast(10)
                        position += 6
                        require(byte(position - 1) == 0)
                    } else skipBlocks()
                }
                0x2C -> {
                    require(position + 9 <= bytes.size)
                    val flags = byte(position + 8)
                    position += 9
                    if (flags and 128 != 0) position += 3 * (1 shl ((flags and 7) + 1))
                    byte(position++) // LZW minimum code size.
                    skipBlocks()
                    times += sourceTime
                    delays += nextDelay.coerceIn(80, 1_000)
                    sourceTime += nextDelay
                    nextDelay = 100
                    require(delays.size <= 32 && width.toLong() * height * delays.size * 4 <= 16_000_000)
                }
                else -> error("Invalid GIF block")
            }
            require(delays.isNotEmpty())
            return StickerGifMetadata(width, height, delays, times)
        }
    }
}
