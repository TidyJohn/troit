package app.tunes

import org.junit.Assert.*
import org.junit.Test

class MusicVideoMatchTest {
    private val song = Song("APT.", "ROSÉ & Bruno Mars", "track", durationSeconds = 172)
    private val official = "MUSIC_VIDEO_TYPE_OMV"

    @Test fun aLongerOfficialVideoBeatsOfficialLyricsWithTheExactAudioLength() {
        val video = Song("APT.", "ROSÉ", "official", durationSeconds = 204)
        val lyric = Song("APT. (Official Lyric Video)", "ROSÉ", "lyrics", durationSeconds = 172)
        val fan = Song("ROSÉ & Bruno Mars - APT. (Official Video)", "Fan Uploads", "fan", durationSeconds = 172)
        val types = mapOf(video.url to official, lyric.url to official, fan.url to "MUSIC_VIDEO_TYPE_UGC")
        assertEquals(listOf(video), MusicVideoMatch.ranked(song, listOf(lyric, fan, video), 172, types))
    }

    @Test fun titleArtistAndVersionMustMatchWhenTheOfficialVideoIsMissing() {
        val results = listOf(
            Song("APT. (Official Audio)", "ROSÉ", "audio"),
            Song("APT.", "Another Band", "other-artist"),
            Song("APT. 2 (Official Video)", "ROSÉ", "other-song"),
            Song("APT. (Live)", "Bruno Mars", "live"),
            Song("APT. (Visualizer)", "ROSÉ", "visualizer"),
            Song("APT.", "ROSÉ - Topic", "topic"),
        )
        assertTrue(MusicVideoMatch.ranked(song, results, 0, results.associate { it.url to official }).isEmpty())
    }

    @Test fun artistUploadsAndLabelReleasesSupportCollaborationsAndLocalizedMetadata() {
        val artist = Song("APT. (Official M/V)", "ROSEVEVO", "artist")
        val label = Song("ROSÉ & Bruno Mars - APT.", "Atlantic Records", "label")
        val topic = Song("APT. (Official Video)", "ROSÉ", "art-track")
        val types = mapOf(label.url to official, topic.url to "MUSIC_VIDEO_TYPE_ATV")
        assertEquals(listOf(label, artist), MusicVideoMatch.ranked(song, listOf(topic, artist, label), 0, types))
        val hindi = Song("सफ़र", "गायक", "hindi")
        assertEquals(listOf(hindi), MusicVideoMatch.ranked(hindi, listOf(hindi), 0, mapOf(hindi.url to official)))
    }

    @Test fun cleanCatalogueNamesAreCheckedAgainstTheActualUpload() {
        assertFalse(MusicVideoMatch.acceptsUpload(song, "ROSÉ & Bruno Mars - APT. (Official Lyric Video)"))
        assertFalse(MusicVideoMatch.acceptsUpload(song, "APT. (Lirik dan Terjemahan)"))
        assertFalse(MusicVideoMatch.acceptsUpload(song, "APT. (Official Audio)"))
        assertFalse(MusicVideoMatch.acceptsUpload(song, "ROSÉ - On The Ground (Official Music Video)"))
        assertTrue(MusicVideoMatch.acceptsUpload(song, "ROSÉ & Bruno Mars - APT. (Official Music Video)"))
        val titleWithMarker = song.copy(title = "Live Forever")
        assertTrue(MusicVideoMatch.acceptsUpload(titleWithMarker, "Live Forever (Official Music Video)"))
        assertFalse(MusicVideoMatch.acceptsUpload(titleWithMarker, "Live Forever (Live at Wembley)"))
    }

    @Test fun bilingualTitlesCanUseEitherNameOnTheUpload() {
        val track = Song("꽃 (FLOWER)", "JISOO", "song")
        val video = Song("FLOWER", "JISOO", "official")
        assertEquals(listOf(video), MusicVideoMatch.ranked(track, listOf(video), 0, mapOf(video.url to official)))
        assertTrue(MusicVideoMatch.acceptsUpload(track, "JISOO - ‘꽃’ M/V"))
        assertTrue(MusicVideoMatch.acceptsUpload(video, "JISOO - ‘꽃(FLOWER)’ M/V"))
        assertFalse(MusicVideoMatch.acceptsUpload(track, "JISOO - ‘꽃(FLOWER)’ Lyrics"))
        assertFalse(MusicVideoMatch.acceptsUpload(track, "JISOO - ‘ME’ M/V"))
    }
}
