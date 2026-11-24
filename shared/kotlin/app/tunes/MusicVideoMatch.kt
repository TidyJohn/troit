package app.tunes

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** The catalogue also marks some official lyric and audio uploads as OMVs, so check their titles too. */
internal object MusicVideoMatch {
    private val officialVideo = Regex("""\b(?:official\s+(?:(?:4k|hd|hd remastered)\s+)?(?:music\s+)?video(?:\s+clip)?|music\s+video|official\s+m/?v|m/?v)\b""", RegexOption.IGNORE_CASE)
    private val nonVideo = Regex("""\b(?:lyrics?|lyrical|lirik|letras?|paroles|karaoke|audio|visuali[sz]er|teaser|trailer|shorts?|reaction|behind the scenes|color coded|cover|live|acoustic|remix|instrumental|sped up|slowed)\b|歌词|歌詞|가사""", RegexOption.IGNORE_CASE)
    private val feature = Regex("""\s*[(\[]?\s*\b(?:feat(?:uring)?|ft)\.?\s+[^)\]]*[)\]]?""", RegexOption.IGNORE_CASE)
    private val credits = Regex("""\s*(?:,|&|\bfeat(?:uring)?\.?\s+|\bft\.?\s+|\s+x\s+)\s*""", RegexOption.IGNORE_CASE)
    private val separator = Regex("""\s+[-–—|]\s+""")
    private val sourceLabel = Regex("""\s*[(\[]\s*(?:official\s+)?(?:lyrics?(?:\s+video)?|audio|visuali[sz]er)\s*[)\]]""", RegexOption.IGNORE_CASE)
    private val channelSuffix = Regex("""(?:\s*[-–]\s*Topic|\s*VEVO|\s+Official(?:\s+Channel)?)$""", RegexOption.IGNORE_CASE)
    private val latinAccents = Regex("""(?<=\p{IsLatin})\p{M}+""")
    private val punctuation = Regex("""[^\p{L}\p{M}\p{N}]+""")
    private val resolution = Regex("""\b(?:4k|1080p|hd|(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?)\b""", RegexOption.IGNORE_CASE)
    private val parenthetical = Regex("""(.+?)\s*[(\[]([^()\[\]]+)[)\]]""")
    private val emptyBrackets = Regex("""[(\[]\s*[)\]]""")
    private val edition = Regex("""\b(?:version|ver|edit|mix|edition)\b""", RegexOption.IGNORE_CASE)

    private fun words(value: String): String {
        val unaccented = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD).replace(latinAccents, "")
        return unaccented.replace(punctuation, " ").trim()
    }

    fun query(song: Song) = "${song.title.replace(sourceLabel, "")} ${song.artist.replace(channelSuffix, "")} official music video".trim().take(256)

    private fun titles(value: String): Set<String> {
        val clean = value.replace(feature, " ").replace(officialVideo, " ").replace(resolution, " ").replace(emptyBrackets, "").trim()
        val names = mutableSetOf(words(clean))
        val parts = parenthetical.matchEntire(clean)
        if (parts != null) {
            val (main, translation) = parts.destructured
            fun scripts(text: String) = text.filter(Char::isLetter).map { Character.UnicodeScript.of(it.code) }.toSet()
            val primary = scripts(main)
            val secondary = scripts(translation)
            if (primary.isNotEmpty() && secondary.isNotEmpty() && primary.intersect(secondary).isEmpty() &&
                !nonVideo.containsMatchIn(translation) && !edition.containsMatchIn(translation)) {
                names += words(main)
                names += words(translation)
            }
        }
        return names.filter(String::isNotEmpty).toSet()
    }

    private fun trackPhrase(names: Set<String>) = Regex(names.sortedByDescending { it.length }
        .joinToString("|", prefix = "(?<!\\S)(?:", postfix = ")(?!\\S)", transform = Regex::escape))

    private fun artistNames(artist: String): Set<String> {
        val credit = artist.replace(channelSuffix, "")
        return (credits.split(credit) + credit).map(::words).filter(String::isNotEmpty).toSet()
    }

    private fun withoutCredit(value: String, artists: Set<String>): String {
        val parts = value.split(separator, limit = 2)
        return if (parts.size == 2 && words(parts.first()) in artists) parts.last() else value
    }

    fun ranked(song: Song, results: List<Song>, targetSeconds: Long, videoTypes: Map<String, String>): List<Song> {
        val artists = artistNames(song.artist)
        val wanted = titles(withoutCredit(song.title.replace(sourceLabel, ""), artists))
        if (wanted.isEmpty()) return emptyList()
        val duration = targetSeconds.takeIf { it > 0 } ?: song.durationSeconds
        // Only remove the track name once: "Lyrics (Lyric Video)" must still encounter the second marker.
        val trackPhrase = trackPhrase(wanted)
        return results.mapNotNull { candidate ->
            val name = withoutCredit(candidate.title, artists)
            val extras = words(name).replaceFirst(trackPhrase, " ")
            if (nonVideo.containsMatchIn(extras) || titles(name).none { it in wanted }) return@mapNotNull null
            val uploader = candidate.artist.replace(channelSuffix, "")
            val sameArtist = (credits.split(uploader) + uploader).any { words(it) in artists } ||
                candidate.artist.endsWith("VEVO", ignoreCase = true) && artists.any { it.replace(" ", "") == words(uploader).replace(" ", "") }
            val creditedTitle = name != candidate.title
            val official = videoTypes[candidate.url] == "MUSIC_VIDEO_TYPE_OMV"
            val marked = officialVideo.containsMatchIn(candidate.title)
            val original = candidate.url == song.url
            if (!sameArtist && !creditedTitle && !original) return@mapNotNull null
            if (!official && !(sameArtist && marked)) return@mapNotNull null
            if (candidate.artist.endsWith(" - Topic", ignoreCase = true) || videoTypes[candidate.url] == "MUSIC_VIDEO_TYPE_ATV") return@mapNotNull null
            val difference = if (duration > 0 && candidate.durationSeconds > 0) abs(candidate.durationSeconds - duration) else Long.MAX_VALUE
            Ranked(candidate, official, marked, sameArtist, difference)
        }.sortedWith(compareByDescending<Ranked> { it.official }.thenByDescending { it.marked }
            .thenByDescending { it.sameArtist }.thenBy { it.difference }).map { it.song }.distinctBy { it.url }
    }

    /** The search row can hide "(Official Lyric Video)" behind the clean album title. */
    fun acceptsUpload(song: Song, uploadTitle: String): Boolean {
        val artists = artistNames(song.artist)
        val names = titles(withoutCredit(song.title.replace(sourceLabel, ""), artists))
        if (names.isEmpty()) return false
        val creditsRemoved = words(withoutCredit(uploadTitle, artists))
        val match = trackPhrase(names).find(creditsRemoved) ?: return false
        val extras = creditsRemoved.replaceRange(match.range, " ")
        return !nonVideo.containsMatchIn(extras)
    }

    private data class Ranked(val song: Song, val official: Boolean, val marked: Boolean, val sameArtist: Boolean, val difference: Long)
}
