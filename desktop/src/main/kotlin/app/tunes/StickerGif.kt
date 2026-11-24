package app.tunes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

@Composable
internal fun rememberStickerGifLoader(): StickerGifLoader = remember {
    { resource, animated ->
        StickerCatalog::class.java.classLoader.getResourceAsStream("drawable/$resource.gif")?.use { input ->
            val bytes = input.readStickerGifBytes()
            decodeStickerGif(bytes, animated)
        }
    }
}

/** Skia's native GIF codec handles inter-frame blending/disposal; no new decoder dependency. */
internal fun decodeStickerGif(bytes: ByteArray, animated: Boolean): StickerGifFrames {
    val metadata = StickerGifMetadata.read(bytes)
    Data.makeFromBytes(bytes).use { data -> Codec.makeFromData(data).use { codec ->
        require(codec.width == metadata.width && codec.height == metadata.height && codec.frameCount == metadata.delaysMs.size)
        val count = if (animated) codec.frameCount else 1
        val frames = (0 until count).map { frame ->
            val bitmap = Bitmap()
            try {
                require(bitmap.allocPixels(codec.imageInfo))
                codec.readPixels(bitmap, frame)
                bitmap.asComposeImageBitmap()
            } catch (failure: Exception) {
                bitmap.close()
                throw failure
            }
        }
        return StickerGifFrames(frames, metadata.delaysMs.take(count))
    } }
}
