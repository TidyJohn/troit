package app.tunes

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Two real mpv decoders with local silent PCM, no provider/network dependency. */
class NativeTransitionsTest {
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) { assertTrue("Timed out: $what", System.currentTimeMillis() < deadline); Thread.sleep(20) }
    }
    private class Fixture(duration: Int, onResolve: (String) -> Unit = {}) : AutoCloseable {
        val dir = Files.createTempDirectory("troit-native-transition").toFile()
        val songs = List(3) { Song("Track $it", "Artist", "https://example.invalid/track-$it", durationSeconds = duration.toLong()) }
        private val wave = File(dir, "silence.wav").apply {
            val samples = duration * 16_000
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2).array()
            writeBytes(header + ByteArray(samples * 2))
        }
        val player = MpvPlayer(listOf("--ao=null"), dir, MpvDependencies(audioSource = {
            onResolve(it)
            AudioSource(wave.absolutePath, AudioQuality("PCM", 256))
        }))
        override fun close() { player.close() }
    }
    @Test fun gaplessNativePlaylistTracksNaturalTransitionsAndStopsAtTheEnd() {
        assumeTrue(File("resources/windows/mpv.exe").isFile)
        Fixture(2).use { f ->
            f.player.setTransitionSettings(TransitionSettings(gapless = true))
            f.player.play(f.songs, 0)
            waitFor("native playlist advances") { f.player.completedTrackRevision >= 1 }
            assertEquals(1, f.player.now.index)
            assertNull(f.player.now.error)
            waitFor("queue completes") { f.player.completedTrackRevision == 3L && !f.player.now.playing }
            assertEquals(2, f.player.now.index)
        }
    }
    @Test fun manualSkipUsesAlreadyPreparedNativeSuccessor() {
        assumeTrue(File("resources/windows/mpv.exe").isFile)
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        Fixture(60) { calls.add(it) }.use { f ->
            val appended = MpvPlayer::class.java.getDeclaredField("appended").apply { isAccessible = true }
            f.player.play(f.songs, 0)
            waitFor("prepare successor early") { appended.getBoolean(f.player) }
            f.player.next()
            waitFor("skip to preloaded file") { f.player.now.index == 1 && f.player.now.playing && !f.player.now.loading }
            assertEquals(1, calls.count { it == f.songs[1].url })
            assertEquals(0L, f.player.completedTrackRevision)
            f.player.play(f.songs, 0, positionMs = 40_000)
            waitFor("requested position survives previously preloaded entry") {
                f.player.now.index == 0 && !f.player.now.loading && f.player.positionMs() in 39_800..45_000
            }
        }
    }

    @Test fun slowSourceCannotHoldUpOrReplaceANewerSongSelection() {
        assumeTrue(File("resources/windows/mpv.exe").isFile)
        val resolving = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(20) { url ->
            if (url.endsWith("track-0")) { resolving.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        }.use { f ->
            try {
                f.player.play(f.songs, 0)
                assertTrue(resolving.await(5, TimeUnit.SECONDS))
                f.player.play(f.songs, 2)
                waitFor("latest selection starts despite pending first lookup") {
                    f.player.now.index == 2 && !f.player.now.loading && f.player.now.playing
                }
                release.countDown()
                waitFor("latest selection advances") { f.player.positionMs() > 600 }
                assertEquals(2, f.player.now.index)
                assertNull(f.player.now.error)
            } finally { release.countDown() }
        }
    }
    @Test fun crossfadeMixesBothEnginesPausesTogetherAndPromotesWithoutRestarting() {
        assumeTrue(File("resources/windows/mpv.exe").isFile)
        Fixture(6).use { f ->
            f.player.setTransitionSettings(TransitionSettings(crossfadeSeconds = 2))
            f.player.play(f.songs.take(2), 0)
            waitFor("both decoders overlap") {
                val volumes = f.player.lastFadeVolumes
                volumes.first in 5f..95f && volumes.second in 5f..95f
            }
            assertEquals(0, f.player.now.index)
            f.player.toggle()
            waitFor("pause during fade") { !f.player.now.playing }
            val volumes = f.player.lastFadeVolumes
            Thread.sleep(300)
            assertEquals("A paused fade must not advance either gain", volumes, f.player.lastFadeVolumes)
            assertEquals(0L, f.player.completedTrackRevision)
            f.player.toggle()
            waitFor("incoming decoder promoted") { f.player.completedTrackRevision == 1L && f.player.now.index == 1 }
            assertTrue(f.player.positionMs() > 500)
            assertFalse(f.player.now.loading)
            assertNull(f.player.now.error)
        }
    }
    @Test fun endOfSongFenceOverridesRepeatAndCancelsPreparedSuccessors() {
        assumeTrue(File("resources/windows/mpv.exe").isFile)
        Fixture(2).use { f ->
            f.player.setPlaybackMode(PlaybackMode.REPEAT_ONE)
            f.player.play(f.songs, 0)
            waitFor("first song starts") { f.player.now.playing && !f.player.now.loading }
            f.player.setStopAfterCurrent(true)
            waitFor("end-of-track fence") { f.player.completedTrackRevision == 1L && !f.player.now.playing }
            Thread.sleep(400)
            assertEquals(0, f.player.now.index)
            assertEquals(1L, f.player.completedTrackRevision)
        }
    }
    @Test fun failedStandbyDoesNotLeaveOutgoingAudioQuietOrPretendPaused() {
        assumeTrue(File("resources/windows/mpv.exe").isFile)
        Fixture(6).use { f ->
            f.player.setTransitionSettings(TransitionSettings(crossfadeSeconds = 2))
            f.player.play(f.songs.take(2), 0)
            waitFor("fade begins") { f.player.lastFadeVolumes.second > 2 }
            val field = MpvPlayer::class.java.getDeclaredField("spare").apply { isAccessible = true }
            (field.get(f.player) as MpvAudioChannel).process.destroyForcibly()
            waitFor("outgoing volume restored") { f.player.lastFadeVolumes == (100f to 0f) }
            assertTrue(f.player.now.playing)
            assertNull(f.player.now.error)
        }
    }
    @Test fun nativeDeadlinePausesEvenWhileTheSourceResolverIsBlocked() {
        assumeTrue(File("resources/windows/mpv.exe").isFile)
        val dir = Files.createTempDirectory("troit-native-alarm").toFile()
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val samples = 20 * 16_000
        val wave = File(dir, "silence.wav").apply {
            writeBytes(ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2).array() + ByteArray(samples * 2))
        }
        val first = Song("First", "Artist", "first")
        val second = first.copy(title = "Second", url = "second")
        val player = MpvPlayer(listOf("--ao=null"), dir, MpvDependencies(audioSource = { url ->
            if (url == "second") { blocked.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            AudioSource(wave.absolutePath, AudioQuality("PCM", 256))
        }))
        try {
            player.setTransitionSettings(TransitionSettings(gapless = false))
            player.play(listOf(first), 0)
            waitFor("first starts") { player.now.playing && !player.now.loading }
            player.play(listOf(second), 0)
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            player.setSleepDeadline(System.currentTimeMillis() + 150)
            waitFor("independent native sleep alarm") { !player.now.playing }
            release.countDown()
            waitFor("resolver returns") { !player.now.loading }
            assertFalse("A late resolver may not restart expired playback", player.now.playing)
        } finally { release.countDown(); player.close() }
    }
}
