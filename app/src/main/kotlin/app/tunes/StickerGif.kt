package app.tunes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun rememberStickerGifLoader(): StickerGifLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        { resource, animated ->
            val id = context.resources.getIdentifier(resource, "drawable", context.packageName)
            if (id == 0) null else context.resources.openRawResource(id).use { input ->
                val bytes = input.readStickerGifBytes()
                decodeStickerGif(bytes, animated)
            }
        }
    }
}

@Suppress("DEPRECATION")
internal fun decodeStickerGif(bytes: ByteArray, animated: Boolean): StickerGifFrames {
    val metadata = StickerGifMetadata.read(bytes)
    val movie = requireNotNull(Movie.decodeByteArray(bytes, 0, bytes.size))
    require(movie.width() == metadata.width && movie.height() == metadata.height)
    val count = if (animated) metadata.delaysMs.size else 1
    val frames = (0 until count).map { index ->
        val bitmap = Bitmap.createBitmap(metadata.width, metadata.height, Bitmap.Config.ARGB_8888)
        movie.setTime(metadata.sourceTimesMs[index])
        movie.draw(Canvas(bitmap), 0f, 0f)
        bitmap.asImageBitmap()
    }
    return StickerGifFrames(frames, metadata.delaysMs.take(count))
}
