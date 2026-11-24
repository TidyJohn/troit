package app.tunes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod

class AudioQualityTest {
    private fun stream(
        rate: Int,
        format: MediaFormat = MediaFormat.WEBMA_OPUS,
        actualBitrate: Int = rate.coerceAtLeast(0) * 1000,
        track: AudioTrackType? = null,
        drc: Boolean = false,
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        url: String = "https://audio.example/stream?expire=4102444800",
        isUrl: Boolean = true,
    ): AudioStream {
        val itag = ItagItem(251, ItagItem.ItagType.AUDIO, format, rate).apply {
            setBitrate(actualBitrate)
            setIsDrc(drc)
        }
        return AudioStream.Builder().setId("test").setContent(url, isUrl)
            .setMediaFormat(format).setAverageBitrate(rate).setItagItem(itag)
            .setAudioTrackType(track).setDeliveryMethod(delivery).build()
    }

    @Test
    fun fullQualityEncodeBeatsDataSavingStreams() {
        val best = stream(160)
        assertSame(best, bestAudioStream(listOf(stream(48, MediaFormat.M4A), best, stream(128, MediaFormat.M4A))))
        val higher = stream(256, MediaFormat.M4A)
        assertSame(higher, bestAudioStream(listOf(best, higher)))
    }

    @Test
    fun streamsWithUnknownNominalRatesUseTheirReportedBitrate() {
        val higher = stream(AudioStream.UNKNOWN_BITRATE, actualBitrate = 256_000)
        assertSame(higher, bestAudioStream(listOf(stream(160), higher)))
    }

    @Test
    fun originalRecordingWinsOverHigherBitrateDubsAndDescriptions() {
        val original = stream(160, track = AudioTrackType.ORIGINAL)
        assertSame(original, bestAudioStream(listOf(
            stream(256, track = AudioTrackType.DUBBED), original,
            stream(320, track = AudioTrackType.DESCRIPTIVE),
        )))
        assertSame(original, bestAudioStream(listOf(original, stream(256, drc = true))))
    }

    @Test
    fun fallbackStillPlaysWhenOnlyCompressedOrUnlabelledAudioExists() {
        val only = stream(128, drc = true)
        assertSame(only, bestAudioStream(listOf(only)))
        val unlabelled = stream(160)
        assertSame(unlabelled, bestAudioStream(listOf(stream(70), unlabelled)))
    }

    @Test
    fun excludesManifestsAndNonHttpContentAndPrefersOpusForAnEqualRate() {
        val opus = stream(128)
        assertSame(opus, bestAudioStream(listOf(
            stream(128, MediaFormat.M4A), opus,
            stream(320, delivery = DeliveryMethod.DASH),
            stream(320, isUrl = false), stream(320, url = "file:///audio"),
        )))
        assertNull(bestAudioStream(emptyList()))
    }

    @Test
    fun qualityShowsTheSourceCodecAndReportedRateWithoutInventingUnknownRates() {
        assertEquals(AudioQuality("Opus", 158), stream(160, actualBitrate = 157_700).sourceQuality())
        assertEquals(AudioQuality("AAC", 128), stream(128, MediaFormat.M4A, actualBitrate = 0).sourceQuality())
        assertNull(stream(AudioStream.UNKNOWN_BITRATE, actualBitrate = 0).sourceQuality().bitrateKbps)
        assertEquals("Opus · ~158 kbps", stream(160, actualBitrate = 157_700).sourceQuality().description)
    }
}
