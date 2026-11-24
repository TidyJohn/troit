package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class MusicPreferenceOption(val code: String, val name: String)

object MusicRegions {
    val options = listOf(
        MusicPreferenceOption("ID", "Indonesia"), MusicPreferenceOption("US", "United States"),
        MusicPreferenceOption("GB", "United Kingdom"), MusicPreferenceOption("IN", "India"),
        MusicPreferenceOption("KR", "South Korea"), MusicPreferenceOption("JP", "Japan"),
        MusicPreferenceOption("BR", "Brazil"), MusicPreferenceOption("MX", "Mexico"),
        MusicPreferenceOption("ES", "Spain"), MusicPreferenceOption("FR", "France"),
        MusicPreferenceOption("DE", "Germany"), MusicPreferenceOption("AU", "Australia"),
        MusicPreferenceOption("CA", "Canada"), MusicPreferenceOption("PH", "Philippines"),
        MusicPreferenceOption("TH", "Thailand"), MusicPreferenceOption("VN", "Vietnam"),
        MusicPreferenceOption("MY", "Malaysia"), MusicPreferenceOption("TR", "Türkiye"),
        MusicPreferenceOption("NG", "Nigeria"), MusicPreferenceOption("ZA", "South Africa"),
    )

    internal val musicTags = mapOf(
        "ID" to listOf("indonesian music", "indonesian pop", "indo pop", "indopop", "dangdut"),
        "US" to listOf("american music", "american hits"),
        "GB" to listOf("british music", "british hits", "uk hits", "uk charts"),
        "IN" to listOf("indian music", "indian songs", "indian hits", "bollywood", "tollywood", "kollywood", "mollywood", "sandalwood"),
        "KR" to listOf("k-pop", "kpop", "korean music", "korean pop"),
        "JP" to listOf("j-pop", "jpop", "japanese music", "japanese pop"),
        "BR" to listOf("brazilian music", "brazilian hits", "sertanejo", "bossa nova"),
        "MX" to listOf("mexican music", "regional mexicano", "música mexicana", "musica mexicana"),
        "ES" to listOf("spanish music", "spanish hits"),
        "FR" to listOf("french music", "french pop", "french hits"),
        "DE" to listOf("german music", "german pop", "german hits", "schlager"),
        "AU" to listOf("australian music", "australian hits"),
        "CA" to listOf("canadian music", "canadian hits"),
        "PH" to listOf("filipino music", "pinoy", "opm hits", "original pilipino music"),
        "TH" to listOf("thai music", "thai pop", "t-pop", "tpop"),
        "VN" to listOf("vietnamese music", "v-pop", "vpop"),
        "MY" to listOf("malaysian music", "malaysian hits"),
        "TR" to listOf("turkish music", "turkish pop", "turkish hits"),
        "NG" to listOf("nigerian music", "nigerian hits"),
        "ZA" to listOf("south african music", "south african hits"),
    )
}

object MusicLanguages {
    val options = listOf(
        MusicPreferenceOption("en", "English"), MusicPreferenceOption("id", "Indonesian"),
        MusicPreferenceOption("hi", "Hindi"), MusicPreferenceOption("ta", "Tamil"),
        MusicPreferenceOption("te", "Telugu"), MusicPreferenceOption("pa", "Punjabi"),
        MusicPreferenceOption("bn", "Bengali"), MusicPreferenceOption("mr", "Marathi"),
        MusicPreferenceOption("gu", "Gujarati"), MusicPreferenceOption("kn", "Kannada"),
        MusicPreferenceOption("ml", "Malayalam"), MusicPreferenceOption("or", "Odia"),
        MusicPreferenceOption("as", "Assamese"), MusicPreferenceOption("ko", "Korean"),
        MusicPreferenceOption("ja", "Japanese"), MusicPreferenceOption("es", "Spanish"),
        MusicPreferenceOption("pt", "Portuguese"), MusicPreferenceOption("fr", "French"),
        MusicPreferenceOption("de", "German"), MusicPreferenceOption("ar", "Arabic"),
        MusicPreferenceOption("zh", "Chinese"), MusicPreferenceOption("th", "Thai"),
        MusicPreferenceOption("vi", "Vietnamese"), MusicPreferenceOption("tr", "Turkish"),
    )

    /** A convenient, editable language group, not a claim that these languages belong to one country. */
    val indianMusicCodes = setOf("hi", "ta", "te", "pa", "bn", "mr", "gu", "kn", "ml", "or", "as")

    internal val aliases = mapOf(
        "id" to listOf("bahasa indonesia"), "bn" to listOf("bangla"), "or" to listOf("oriya"),
        "zh" to listOf("mandarin", "cantonese"), "pt" to listOf("português"),
        "es" to listOf("español"), "fr" to listOf("français"), "de" to listOf("deutsch"),
    )
}

/**
 * Discovery market changes the catalogue's location, not a recording's country of origin.
 * Exclusions use explicit title/language labels. Unknown origins stay visible: the upstream catalogue
 * does not provide reliable country or language metadata, and an artist's name is not nationality.
 */
data class ListeningPreferences(
    val market: String? = null,
    val excludedRegions: Set<String> = emptySet(),
    val excludedLanguages: Set<String> = emptySet(),
) {
    val activeExclusions get() = excludedRegions.size + excludedLanguages.size

    fun allows(item: Item): Boolean {
        // Saved playlists remain available regardless of discovery preferences.
        if (item is SavedPlaylist || item is CollaborativePlaylist || item is Artist) return true
        return allowsText(item.title, if (item is Song) "" else item.subtitle)
    }

    fun filterItems(items: List<Item>): List<Item> = items.filter(::allows)

    fun filterSections(sections: List<Section>): List<Section> = sections.mapNotNull { section ->
        if (!allowsText(section.title)) return@mapNotNull null
        val items = filterItems(section.items)
        if (items.isEmpty()) null else Section(section.title, items, section.more?.takeIf(::allows))
    }

    private fun allowsText(title: String, subtitle: String = ""): Boolean {
        if (excludedRegions.isEmpty() && excludedLanguages.isEmpty()) return true
        val text = "$title $subtitle".lowercase(Locale.ROOT).trim()
        if (excludedRegions.any { code ->
                MusicRegions.musicTags[code].orEmpty().any { text.hasLabel(it) } || matchesCountryLabel(text, code)
            }) return false
        if (excludedLanguages.any { code ->
                val language = MusicLanguages.options.firstOrNull { it.code == code }
                (listOfNotNull(language?.name) + MusicLanguages.aliases[code].orEmpty()).any { text.hasLabel(it) } ||
                    matchesLanguageScript(title, code)
            }) return false
        return true
    }

    private fun matchesCountryLabel(text: String, code: String): Boolean {
        val country = MusicRegions.options.firstOrNull { it.code == code }?.name?.lowercase(Locale.ROOT) ?: return false
        if (text == country) return true
        return countryPatterns[code].orEmpty().any { it.containsMatchIn(text) }
    }

    private fun matchesLanguageScript(text: String, code: String): Boolean = when (code) {
        // Shared scripts (Devanagari, Arabic, Cyrillic, Han) cannot identify a single language.
        "ta" -> text.any { it in '\u0B80'..'\u0BFF' }
        "te" -> text.any { it in '\u0C00'..'\u0C7F' }
        "kn" -> text.any { it in '\u0C80'..'\u0CFF' }
        "ml" -> text.any { it in '\u0D00'..'\u0D7F' }
        "gu" -> text.any { it in '\u0A80'..'\u0AFF' }
        "pa" -> text.any { it in '\u0A00'..'\u0A7F' }
        "or" -> text.any { it in '\u0B00'..'\u0B7F' }
        "ko" -> text.any { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' }
        "ja" -> text.any { it in '\u3040'..'\u30FF' }
        "th" -> text.any { it in '\u0E00'..'\u0E7F' }
        else -> false
    }

    private fun String.hasLabel(label: String): Boolean = labelPatterns.getOrPut(label) {
        Regex("(?:^|[^\\p{L}\\p{N}])${Regex.escape(label.lowercase(Locale.ROOT))}(?:$|[^\\p{L}\\p{N}])")
    }.containsMatchIn(this)

    companion object {
        // Lists are filtered again while composing, so compile patterns once rather than per visible row.
        private val labelPatterns = ConcurrentHashMap<String, Regex>()
        private val countryPatterns by lazy {
            MusicRegions.options.associate { option ->
                val country = option.name.lowercase(Locale.ROOT)
                val aliases = when (option.code) {
                    "GB" -> listOf(country, "uk", "britain")
                    "US" -> listOf(country, "usa")
                    "KR" -> listOf(country, "korea")
                    "TR" -> listOf(country, "turkey")
                    else -> listOf(country)
                }
                option.code to aliases.flatMap { name ->
                    val escaped = Regex.escape(name)
                    listOf(
                        Regex("(?:^|[^\\p{L}\\p{N}])(?:top (?:songs|100|50)|hits|charts|music|sounds|trending|from|in|of)\\s*(?:in|from|of)?\\s*[:–-]?\\s*$escaped(?:$|[^\\p{L}\\p{N}])"),
                        Regex("(?:^|[^\\p{L}\\p{N}])$escaped\\s+(?:top|hits|charts|music|sounds|trending)(?:$|[^\\p{L}\\p{N}])"),
                    )
                }
            }
        }
    }
}

/** Device-local, observable preferences. Writes happen before publishing state so failed saves never pretend to work. */
class RegionPreferences(private val file: File) {
    var settings by mutableStateOf(runCatching { decode(file.readText()) }.getOrDefault(ListeningPreferences()))
        private set

    fun setMarket(code: String?) {
        require(code == null || MusicRegions.options.any { it.code == code }) { "Unknown discovery region" }
        update(settings.copy(market = code))
    }

    fun setRegionExcluded(code: String, excluded: Boolean) {
        require(MusicRegions.options.any { it.code == code }) { "Unknown music region" }
        update(settings.copy(excludedRegions = settings.excludedRegions.withSelection(code, excluded)))
    }

    fun setLanguageExcluded(code: String, excluded: Boolean) {
        require(MusicLanguages.options.any { it.code == code }) { "Unknown music language" }
        update(settings.copy(excludedLanguages = settings.excludedLanguages.withSelection(code, excluded)))
    }

    fun setIndianMusicExcluded(excluded: Boolean) {
        update(settings.copy(
            excludedRegions = settings.excludedRegions.withSelection("IN", excluded),
            excludedLanguages = if (excluded) settings.excludedLanguages + MusicLanguages.indianMusicCodes
                else settings.excludedLanguages - MusicLanguages.indianMusicCodes,
        ))
    }

    fun reset() = update(ListeningPreferences())

    private fun update(changed: ListeningPreferences) {
        if (changed == settings) return
        file.parentFile?.mkdirs()
        file.writeAtomically(encode(changed))
        settings = changed
    }

    private fun Set<String>.withSelection(code: String, selected: Boolean) = if (selected) this + code else this - code

    companion object {
        internal fun encode(settings: ListeningPreferences): String = JSONObject()
            .put("market", settings.market ?: JSONObject.NULL)
            .put("excludedRegions", JSONArray(settings.excludedRegions.sorted()))
            .put("excludedLanguages", JSONArray(settings.excludedLanguages.sorted()))
            .toString()

        internal fun decode(text: String): ListeningPreferences {
            val json = JSONObject(text)
            val regions = MusicRegions.options.map { it.code }.toSet()
            val languages = MusicLanguages.options.map { it.code }.toSet()
            fun selected(key: String, known: Set<String>): Set<String> {
                val array = json.optJSONArray(key) ?: return emptySet()
                return (0 until array.length()).mapNotNull { array.optString(it).takeIf(known::contains) }.toSet()
            }
            return ListeningPreferences(
                market = json.optString("market").takeIf(regions::contains),
                excludedRegions = selected("excludedRegions", regions),
                excludedLanguages = selected("excludedLanguages", languages),
            )
        }
    }
}
