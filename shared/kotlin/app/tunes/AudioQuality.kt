package app.tunes

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/** Source information, not a claim about the master recording or the device's output. */
data class AudioQuality(val codec: String, val bitrateKbps: Int?) {
    val description: String
        get() = listOfNotNull(codec, bitrateKbps?.let { "~$it kbps" }).joinToString(" · ")
}

internal data class AudioSource(val url: String, val quality: AudioQuality)

/**
 * Keep the original recording and its dynamics before comparing encodes. Nominal codec rates are
 * in kbps; the stream's bitrate is in bits/s and also covers streams with unknown nominal rates.
 * No resampling or re-encoding can recover detail that the provider's source does not contain.
 */
internal fun bestAudioStream(streams: List<AudioStream>): AudioStream? = streams
    .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
        (it.content.startsWith("https://") || it.content.startsWith("http://")) }
    .maxWithOrNull(compareBy<AudioStream>(
        { if (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL) 1 else 0 },
        { if (it.itagItem?.isDrc() == true) 0 else 1 },
        { if (it.format in setOf(MediaFormat.FLAC, MediaFormat.ALAC, MediaFormat.WAV)) 1 else 0 },
        { it.averageBitrate.takeIf { rate -> rate > 0 } ?: (it.bitrate / 1000).coerceAtLeast(0) },
        { it.bitrate },
        { if (it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.OPUS) 1 else 0 },
    ))

internal fun AudioStream.sourceQuality(): AudioQuality {
    val codecName = when {
        codec?.contains("opus", ignoreCase = true) == true || format == MediaFormat.WEBMA_OPUS || format == MediaFormat.OPUS -> "Opus"
        codec?.startsWith("mp4a", ignoreCase = true) == true || format == MediaFormat.M4A -> "AAC"
        else -> format?.name ?: codec?.takeIf { it.isNotBlank() } ?: "Audio"
    }
    val rate = bitrate.takeIf { it > 0 }?.let { (it + 500) / 1000 }
        ?: averageBitrate.takeIf { it > 0 }
    return AudioQuality(codecName, rate)
}
