package app.tunes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
class StickerGifTest {
    private fun bytes(id: String): ByteArray = requireNotNull(StickerCatalog::class.java.classLoader
        .getResourceAsStream("drawable/sticker_${id.replace('-', '_')}.gif")) { "Missing bundled GIF: $id" }.use { it.readStickerGifBytes() }

    @Test
    fun photoVariationsDoNotReuseTheSameBaseImageWithDifferentCaptions() {
        val grid = 16
        fun fingerprint(image: androidx.compose.ui.graphics.ImageBitmap): DoubleArray {
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            // Captions occupy the bottom of the sticker; they must not make a reused photo look new.
            val cropHeight = image.height * 7 / 10
            val values = DoubleArray(grid * grid * 3)
            val means = DoubleArray(3)
            for (gy in 0 until grid) for (gx in 0 until grid) {
                val left = gx * image.width / grid
                val right = (gx + 1) * image.width / grid
                val top = gy * cropHeight / grid
                val bottom = (gy + 1) * cropHeight / grid
                val total = LongArray(3)
                for (y in top until bottom) for (x in left until right) {
                    val pixel = pixels[y * image.width + x]
                    total[0] += ((pixel ushr 16) and 255).toLong()
                    total[1] += ((pixel ushr 8) and 255).toLong()
                    total[2] += (pixel and 255).toLong()
                }
                val count = (right - left) * (bottom - top)
                for (channel in 0..2) {
                    val value = total[channel].toDouble() / count
                    values[(gy * grid + gx) * 3 + channel] = value
                    means[channel] += value / (grid * grid)
                }
            }
            // Small exposure/tint differences are not a genuinely different base photo.
            return DoubleArray(values.size) { (values[it] - means[it % 3]) / 255 }
        }
        val fingerprints = StickerCatalog.entries.associate { sticker ->
            sticker.id to fingerprint(decodeStickerGif(bytes(sticker.id), animated = false).images.single())
        }
        val distances = mutableListOf<String>()
        val ids = StickerCatalog.entries.map { it.id }
        for (left in ids.indices) for (right in left + 1 until ids.size) {
            val a = fingerprints.getValue(ids[left])
            val b = fingerprints.getValue(ids[right])
            val distance = kotlin.math.sqrt(a.indices.sumOf { (a[it] - b[it]) * (a[it] - b[it]) } / a.size)
            distances += "${ids[left]} / ${ids[right]}: $distance"
            assertTrue("Use a distinct caption-free base image for ${ids[left]} and ${ids[right]} (distance=$distance)", distance >= .07)
        }
        File("build/sticker-previews").apply { mkdirs() }.resolve("variation-distances.txt").writeText(distances.joinToString("\n"))
    }

    @Test
    fun everyExistingStickerIdHasARealMultiFramePhotoGifAndOneFramePreview() {
        assertEquals(8, StickerCatalog.ids.size)
        for (sticker in StickerCatalog.entries) {
            val source = bytes(sticker.id)
            val metadata = StickerGifMetadata.read(source)
            assertEquals(320, metadata.width)
            assertEquals(320, metadata.height)
            assertEquals(10, metadata.delaysMs.size)
            assertTrue(metadata.delaysMs.all { it == 140 })
            val preview = decodeStickerGif(source, animated = false)
            assertEquals(1, preview.images.size)
            val decoded = decodeStickerGif(source, animated = true)
            assertEquals(10, decoded.images.size)
            val hashes = decoded.images.map { frame ->
                val pixels = IntArray(frame.width * frame.height)
                frame.readPixels(pixels)
                pixels.contentHashCode()
            }
            assertTrue("GIF must change its image, not just contain duplicate frames: ${sticker.id}", hashes.toSet().size > 1)
            val previewPixels = IntArray(320 * 320)
            preview.images.single().readPixels(previewPixels)
            assertEquals(hashes.first(), previewPixels.contentHashCode())
        }
    }

    @Test
    fun cacheIsPixelBoundedAndMalformedGifMetadataIsRejected() {
        StickerGifCache.clear()
        for (sticker in StickerCatalog.entries) {
            StickerGifCache.load(sticker.id, true) { id, animated -> decodeStickerGif(bytes(id), animated) }
            assertTrue(StickerGifCache.decodedBytes() <= 16_000_000)
        }
        val source = bytes("jomok-waduh")
        assertTrue(runCatching { StickerGifMetadata.read(source.copyOf(20)) }.isFailure)
        assertTrue(runCatching { StickerGifMetadata.read(source.copyOf().also { it[6] = 0; it[7] = 4 }) }.isFailure)
        assertTrue(runCatching { StickerGifMetadata.read(ByteArray(2_000_001)) }.isFailure)
        StickerGifCache.clear()
    }

    @Test
    fun renderedGifAdvancesAndStopsWhenTheAppIsInactive() = runBlocking(Dispatchers.Main) {
        StickerGifCache.clear()
        var active by mutableStateOf(true)
        var time = 0L
        val output = File("build/ui-previews").apply { mkdirs() }
        val scene = ImageComposeScene(180, 180, density = Density(1f), coroutineContext = coroutineContext) {
            CompositionLocalProvider(LocalAppActive provides active) {
                TroitTheme { Surface(Modifier.fillMaxSize()) { Sticker("jomok-waduh", Modifier.width(180.dp)) } }
            }
        }
        fun hash(): Int = scene.render(time.also { time += 100_000_000L }).use { frame ->
            requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes.contentHashCode()
        }
        suspend fun settle(milliseconds: Long) {
            val deadline = System.currentTimeMillis() + milliseconds
            while (System.currentTimeMillis() < deadline) { hash(); delay(20) }
        }
        try {
            withTimeout(5_000) { while (StickerGifCache.decodedBytes() < 1_000_000) { hash(); delay(20) } }
            settle(200)
            val hashes = mutableSetOf(hash())
            repeat(5) { settle(150); hashes += hash() }
            assertTrue("An active visible chat GIF must advance", hashes.size > 1)
            active = false
            settle(400)
            val frozen = hash()
            settle(300)
            assertEquals("Backgrounding the app must stop GIF frame changes", frozen, hash())
            scene.render(time).use { frame -> File(output, "photo-sticker-paused.png")
                .writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes) }
        } finally { scene.close(); StickerGifCache.clear() }
    }
}
