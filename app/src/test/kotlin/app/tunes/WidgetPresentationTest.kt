package app.tunes

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WidgetPresentationTest {
    private val now = Instant.parse("2026-11-11T10:00:20Z").toEpochMilli()
    private val remote = ConnectSnapshot("pc", "runtime", "My PC",
        Song("First", "Artist", "https://example.com/first"), true, 9_000, 5,
        "2026-11-11T10:00:00Z", "2026-11-11T10:00:45Z", true, 1)
    private fun entry(snapshot: ConnectSnapshot) = WidgetEntry("account", false,
        snapshot.song.title, snapshot.song.artist, snapshot.song.url, snapshot.playing, remote = snapshot.toJson())

    @Test fun heartbeatRefreshesLeaseWithoutChangingTheVisibleWidgetOrButtonIdentity() {
        val first = entry(remote)
        val heartbeat = entry(remote.copy(positionMs = 24_000, updatedAt = "2026-11-11T10:00:15Z",
            expiresAt = "2026-11-11T10:01:00Z"))
        assertNotEquals(first.remote, heartbeat.remote)
        assertEquals(WidgetPresentation.key(first, null, now), WidgetPresentation.key(heartbeat, null, now))
    }

    @Test fun TrackStateDeviceAndLeaseChangesRequireARedraw() {
        val key = WidgetPresentation.key(entry(remote), null, now)
        listOf(remote.copy(playing = false), remote.copy(revision = 6), remote.copy(instanceId = "new-runtime"),
            remote.copy(queueIndex = 2), remote.copy(deviceName = "Other PC"), remote.copy(fresh = false),
            remote.copy(song = remote.song.copy(title = "A full new title"))).forEach {
            assertNotEquals(key, WidgetPresentation.key(entry(it), null, now))
        }
        assertNotEquals(key, WidgetPresentation.key(entry(remote), "Reconnecting…", now))
        assertNotEquals(key, WidgetPresentation.key(entry(remote), null, now + 30_000))
    }

    @Test fun SlowerRequestsCannotOverwriteANewerSongOrMoreRecentHeartbeat() {
        assertFalse(WidgetPresentation.accepts(remote.copy(revision = 6), remote))
        assertFalse(WidgetPresentation.accepts(remote.copy(updatedAt = "2026-11-11T10:00:15Z"), remote))
        assertTrue(WidgetPresentation.accepts(remote, remote.copy(revision = 6)))
        assertTrue(WidgetPresentation.accepts(remote, remote.copy(updatedAt = "2026-11-11T10:00:15Z")))
        assertTrue(WidgetPresentation.accepts(null, remote))
    }
}
