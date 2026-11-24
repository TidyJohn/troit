package app.tunes

import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests marked "live" hit the real YouTube Music. If they fail, the app is broken too. */
class YtTest {
    /** Live. On failure: bump NewPipeExtractor. */
    @Test
    fun searchFindsASongWhoseAudioCanBeFetched() {
        val song = Yt.search("daft punk get lucky").filterIsInstance<Song>().first()
        println("first result: $song")

        val url = Yt.audioUrl(song.url)
        val request = Request.Builder().url(url).header("Range", "bytes=0-1023").build()
        Yt.http.newCall(request).execute().use { assertEquals("audio fetch status", 206, it.code) }

        // The link cache relies on real links carrying an `expire` parameter.
        assertTrue("real audio link has no usable expiry", Yt.isFresh(url))
        assertEquals("second lookup should come from the cache", url, Yt.audioUrl(song.url))
    }

    /** Live. Walks the catalogue the way a person would. On failure: check Music.CLIENT_VERSION and the parser. */
    @Test
    fun catalogueCanBeBrowsedFromHomeToASong() {
        val home = Music.home()
        println("home shelves: " + home.joinToString(" | ") { "${it.title} (${it.items.size})" })
        assertTrue("home has too few shelves: ${home.size}", home.size >= 3)
        assertTrue("home has no tiles with artwork", home.flatMap { it.items }.any { it.artwork != null })
        assertFalse("music-video shelf leaked in", home.any { it.title.contains("music videos", ignoreCase = true) })

        val album = home.flatMap { it.items }.filterIsInstance<Playlist>().first { it.browseId.startsWith("MPREb_") }
        val tracks = Music.songsIn(album.browseId)
        println("album '${album.title}': ${tracks.size} tracks, first: ${tracks.firstOrNull()}")
        assertTrue("album has no tracks", tracks.isNotEmpty())
        assertTrue("album track lost its artist", tracks.all { it.artist.isNotEmpty() })
        assertNotNull("album track lost its cover", tracks.first().artwork)
        assertTrue("catalogue song has no audio", Yt.audioUrl(tracks.first().url).startsWith("http"))
    }

    /** Live. */
    @Test
    fun moodsAndGenresOpenIntoPlaylists() {
        val categories = Music.categories().flatMap { it.items }.filterIsInstance<Category>()
        println("categories: ${categories.size}, first: ${categories.firstOrNull()}")
        assertTrue("too few categories", categories.size >= 10)

        val shelves = Music.sections(categories.first().browseId, categories.first().params)
        val playlist = shelves.flatMap { it.items }.filterIsInstance<Playlist>().first()
        val songs = Music.songsIn(playlist.browseId)
        println("playlist '${playlist.title}': ${songs.size} songs")
        assertTrue("curated playlist has no songs", songs.isNotEmpty())
    }

    /** Live. Covers the ids built from search results, which take a different route into the same pages. */
    @Test
    fun searchResultsOpenTheirPages() {
        val artist = Yt.search("daft punk", Yt.ARTISTS).filterIsInstance<Artist>().first()
        val shelves = Music.sections(artist.browseId)
        println("artist '${artist.title}': " + shelves.joinToString(" | ") { it.title })
        assertTrue("artist page is empty", shelves.isNotEmpty())

        val album = Yt.search("random access memories", Yt.ALBUMS).filterIsInstance<Playlist>().first()
        assertTrue("searched album has no songs", Music.songsIn(album.browseId).isNotEmpty())

        val playlist = Yt.search("pop hits", Yt.PLAYLISTS).filterIsInstance<Playlist>().first()
        val songs = Music.songsIn(playlist.browseId)
        println("playlist '${playlist.title}': ${songs.size} songs")
        assertTrue("searched playlist has no songs", songs.isNotEmpty())
    }

    /** Live. "See all" and paged search are what make the catalogue reachable beyond the first few results. */
    @Test
    fun catalogueGoesBeyondTheFirstResults() {
        val artist = Yt.search("lana del rey", Yt.ARTISTS).filterIsInstance<Artist>().first()
        assertTrue("wrong artist: ${artist.title}", artist.title.contains("Lana", ignoreCase = true))
        val shelves = Music.sections(artist.browseId)

        val albums = shelves.first { it.title == "Albums" }
        val allAlbums = when (val more = assertNotNullAndGet(albums.more)) {
            is Category -> Music.sections(more.browseId, more.params).flatMap { it.items }
            else -> error("unexpected See all target: $more")
        }
        println("albums: shelf=${albums.items.size} seeAll=${allAlbums.size}")
        assertTrue("See all found no albums", allAlbums.filterIsInstance<Playlist>().isNotEmpty())

        val topSongs = shelves.first { it.title == "Top songs" }
        val allSongs = Music.songsIn((assertNotNullAndGet(topSongs.more) as Playlist).browseId)
        println("songs: shelf=${topSongs.items.size} seeAll=${allSongs.size}")
        assertTrue("See all should list more songs than the shelf", allSongs.size > topSongs.items.size)

        val first = Yt.searchPage("lana del rey", Yt.SONGS)
        val second = assertNotNullAndGet(first.more).invoke()
        println("search: page1=${first.items.size} page2=${second.items.size}")
        assertTrue("second search page is empty", second.items.isNotEmpty())
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    /** Live. This link once matched a karaoke version. */
    @Test
    fun spotifyLinkFindsTheRealRecording() {
        val links = Spotify.links("see https://open.spotify.com/track/2Yy0FsAV2I5VXU4xpph2WN?si=abc")
        assertEquals(listOf("track" to "2Yy0FsAV2I5VXU4xpph2WN"), links)
        val match = Spotify.match(Spotify.tracks("track", links.single().second).single())
        println("match: ${match.song} exact=${match.exact}")
        assertTrue("wrong artist: ${match.song}", match.song!!.artist.contains("Rihanna"))
    }

    @Test
    fun matcherRejectsOtherVersionsOfASong() {
        val track = SpotifyTrack("Stay", listOf("Rihanna", "Mikky Ekko"), 240_000)
        fun song(title: String, artist: String, seconds: Long = 240) = Song(title, artist, "u", null, seconds)
        assertTrue(Spotify.isSameTrack(track, song("Stay", "Rihanna")))
        assertTrue("featured artist should count", Spotify.isSameTrack(track, song("Stay (feat. Mikky Ekko)", "Mikky Ekko")))
        assertFalse("karaoke label", Spotify.isSameTrack(track, song("Stay (Rihanna)", "Party Tyme Karaoke")))
        assertFalse("cover", Spotify.isSameTrack(track, song("Stay - Rihanna Cover", "Someone")))
        assertFalse("different song", Spotify.isSameTrack(track, song("Diamonds", "Rihanna")))
        assertFalse("different artist", Spotify.isSameTrack(track, song("Stay", "Justin Bieber")))
        assertFalse("much too long", Spotify.isSameTrack(track, song("Stay", "Rihanna", 400)))

        assertFalse("live version", Spotify.isSameTrack(track, song("Stay (Live)", "Rihanna")))

        val oliver = SpotifyTrack("Life Goes On", listOf("Oliver Tree"), 160_000)
        assertTrue("'live' inside a name isn't a live version", Spotify.isSameTrack(oliver, song("Life Goes On", "Oliver Tree", 160)))

        val remix = SpotifyTrack("Si Ai - Marshmello & Ukay Remix", listOf("Tayna"), 183_000)
        assertTrue("a remix asked for is a remix found", Spotify.isSameTrack(remix, song("Si Ai (Marshmello & Ukay Remix)", "Tayna", 184)))
    }

    @Test
    fun syncKeepsTheNewestVersionOfEachPlaylist() {
        fun playlist(id: String, title: String, at: Long, deleted: Boolean = false) =
            SavedPlaylist(title, emptyList(), id, at, deleted)
        val local = listOf(
            playlist("newer-here", "mine", 200),
            playlist("newer-there", "old", 100),
            playlist("only-here", "new", 50),
            playlist("deleted-here", "gone", 300, deleted = true),
            playlist("same", "same", 10),
        )
        val remote = listOf(
            playlist("newer-here", "stale", 100),
            playlist("newer-there", "fresh", 200),
            playlist("only-there", "theirs", 70),
            playlist("deleted-here", "still there", 100),
            playlist("same", "same", 10),
        )
        val merged = Sync.merge(local, remote)
        val titles = merged.all.associate { it.id to it.title }
        assertEquals("mine", titles["newer-here"])
        assertEquals("fresh", titles["newer-there"])
        assertEquals("theirs", titles["only-there"])
        assertTrue("delete must win when newer", merged.all.first { it.id == "deleted-here" }.deleted)
        assertEquals(setOf("newer-here", "only-here", "deleted-here"), merged.push.map { it.id }.toSet())
        assertTrue("a second sync has nothing left to send", Sync.merge(merged.all, merged.all).push.isEmpty())
    }

    @Test
    fun savingToAPlaylistSkipsDuplicatesPersistsAndTriggersSync() {
        val file = File.createTempFile("library", ".json").apply { delete(); deleteOnExit() }
        val library = Library(file)
        var changes = 0
        library.onChange = { changes++ }
        val a = Song("A", "Band", "ua")
        val b = Song("B", "Band", "ub")
        val playlist = SavedPlaylist("Mine", listOf(a), updatedAt = 1)
        library.add(playlist)

        assertEquals("only the new song counts", 1, library.addSongs(playlist.id, listOf(a, b, b)))
        assertEquals(listOf(a, b), library.playlists.single().songs)
        assertTrue("an edit must look newer to other devices", library.playlists.single().updatedAt > 1)
        assertEquals("nothing new to add", 0, library.addSongs(playlist.id, listOf(a)))
        assertEquals("no-op must not trigger a sync", 2, changes)

        library.removeSong(playlist.id, a)
        assertEquals(listOf(b), library.playlists.single().songs)
        assertEquals(listOf(b), Library(file).playlists.single().songs)

        library.remove(library.playlists.single())
        assertEquals("can't add to a deleted playlist", 0, library.addSongs(playlist.id, listOf(a)))
    }

    @Test
    fun libraryFileFromBeforeSyncStillLoads() {
        val old = """[{"title":"Old","songs":[{"title":"T","artist":"A","url":"u","artwork":null,"duration":5}]}]"""
        val loaded = Library.decode(old).single()
        assertEquals("Old", loaded.title)
        assertEquals(1, loaded.songs.size)
        assertTrue("needs an id to sync", loaded.id.isNotEmpty())
    }

    @Test
    fun libraryFileRoundTrips() {
        val saved = listOf(
            SavedPlaylist("Mine", listOf(Song("A \"quoted\" title", "Band", "u1", null, 100), Song("八方來財", "B", "u2", "art", 0))),
            SavedPlaylist("Empty", emptyList()),
        )
        assertEquals(saved, Library.decode(Library.encode(saved)))
    }

    @Test
    fun parserSkipsWhatTheAppCannotOpen() {
        val podcast = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Show"}]},"navigationEndpoint":{"browseEndpoint":{
            "browseId":"X","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
            "pageType":"MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"}}}}}}"""
        assertNull("podcasts are not music", Music.parseItem(JSONObject(podcast)))

        val unavailable = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{
            "text":{"runs":[{"text":"Greyed out"}]}}}]}}"""
        assertNull("a track with no video id can't play", Music.parseItem(JSONObject(unavailable)))

        val song = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Song"}]},"subtitle":{"runs":[{"text":"Band"},
            {"text":" • "},{"text":"1M views"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"abc"}}}}"""
        assertEquals(Song("Song", "Band", "https://music.youtube.com/watch?v=abc"), Music.parseItem(JSONObject(song)))
    }

    @Test
    fun audioLinkFreshnessFollowsItsExpireParameter() {
        val now = 1_700_000_000_000L
        val nowSeconds = now / 1000
        assertTrue(Yt.isFresh("https://host/videoplayback?expire=${nowSeconds + 3600}&id=1", now))
        assertFalse("expires within the slack", Yt.isFresh("https://host/videoplayback?id=1&expire=${nowSeconds + 60}", now))
        assertFalse("already expired", Yt.isFresh("https://host/videoplayback?expire=${nowSeconds - 1}", now))
        assertFalse("no expiry means stale", Yt.isFresh("https://host/videoplayback?id=1", now))
    }
}
