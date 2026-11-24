package app.tunes

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibraryTest {
    @Test
    fun conversionTombstoneAlwaysWinsOverCurrentAndFutureDatedPersonalVersions() {
        val directory = Files.createTempDirectory("troit-conversion-tombstone").toFile()
        try {
            val library = Library(File(directory, "library.json"))
            val source = SavedPlaylist("Personal", listOf(Song("Song", "Artist", "url")),
                updatedAt = System.currentTimeMillis() + 60_000)
            library.add(source)
            library.rename(source.id, "Edited while sharing")
            val edited = library.playlists.single()
            library.remove(source)
            val tombstone = library.all.single()
            assertTrue(tombstone.deleted)
            assertEquals(edited.title, tombstone.title)
            assertTrue(tombstone.updatedAt > edited.updatedAt)
            assertTrue(Sync.merge(library.all, listOf(edited)).all.single().deleted)
        } finally { directory.deleteRecursively() }
    }
    @Test
    fun renamePersistsKeepsSongsAndIdentityAndPushesNewerVersionToSync() {
        val directory = Files.createTempDirectory("troit-library-test").toFile()
        try {
            val file = File(directory, "library.json")
            val library = Library(file)
            val original = SavedPlaylist("Old name", listOf(Song("Song", "Artist", "url")),
                updatedAt = System.currentTimeMillis() + 1000)
            library.add(original)
            var changes = 0
            library.onChange = { changes++ }

            assertTrue(library.rename(original.id, "  Evening favorites  "))

            val renamed = library.playlists.single()
            assertEquals("Evening favorites", renamed.title)
            assertEquals(original.id, renamed.id)
            assertEquals(original.songs, renamed.songs)
            assertTrue(renamed.updatedAt > original.updatedAt)
            assertEquals(1, changes)
            assertEquals(renamed, Library(file).playlists.single())
            val merged = Sync.merge(library.playlists, listOf(original))
            assertEquals(listOf(renamed), merged.all)
            assertEquals(listOf(renamed), merged.push)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun renameRejectsBlankUnchangedMissingAndDeletedPlaylistsWithoutWriting() {
        val directory = Files.createTempDirectory("troit-library-test").toFile()
        try {
            val file = File(directory, "library.json")
            val library = Library(file)
            val original = SavedPlaylist("Favorites", emptyList())
            library.add(original)
            var changes = 0
            library.onChange = { changes++ }
            val initial = file.readText()
            assertFalse(library.rename(original.id, " \n "))
            assertFalse(library.rename(original.id, " Favorites "))
            assertFalse(library.rename("missing", "New name"))
            assertEquals(initial, file.readText())
            assertEquals(0, changes)

            assertTrue(library.rename(original.id, "x".repeat(120)))
            assertEquals(100, library.playlists.single().title.length)
            library.remove(library.playlists.single())
            changes = 0
            val deleted = file.readText()
            assertFalse(library.rename(original.id, "Revive"))
            assertEquals(deleted, file.readText())
            assertEquals(0, changes)
        } finally {
            directory.deleteRecursively()
        }
    }
}
