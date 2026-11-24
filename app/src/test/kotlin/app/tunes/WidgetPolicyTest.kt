package app.tunes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WidgetPolicyTest {
    private fun remoteTarget() = ConnectSnapshot("pc", "runtime-a", "Computer",
        Song("Track", "Artist", "https://example.com/track"), true, 1_000, 8,
        "2026-11-11T10:00:00Z", "2026-11-11T10:00:45Z", true, queueIndex = 2)

    @Test fun staleTapCanRenewOnlyTheExactDeviceRuntimeAndQueueItem() {
        val original = remoteTarget()
        assertTrue(WidgetPolicy.sameTarget(original, original.copy(updatedAt = "2026-11-11T10:01:00Z",
            expiresAt = "2026-11-11T10:01:45Z", positionMs = 61_000, playing = false)))
        assertFalse(WidgetPolicy.sameTarget(original, original.copy(deviceId = "other-pc")))
        assertFalse(WidgetPolicy.sameTarget(original, original.copy(instanceId = "restarted-runtime")))
        assertFalse(WidgetPolicy.sameTarget(original, original.copy(revision = 9)))
        assertFalse(WidgetPolicy.sameTarget(original, original.copy(song = original.song.copy(url = "https://example.com/next"))))
        assertFalse(WidgetPolicy.sameTarget(original, original.copy(queueIndex = 3)))
    }

    @Test fun activityRecreationPreservesCommandsButSignOutAndSignInCancelThem() {
        assertFalse(WidgetPolicy.needsAccountReset(null, "account-a"))
        assertFalse(WidgetPolicy.needsAccountReset(WidgetPolicy.accountMarker("account-a"), "account-a"))
        assertTrue(WidgetPolicy.needsAccountReset(WidgetPolicy.accountMarker("account-a"), null))
        assertTrue(WidgetPolicy.needsAccountReset(WidgetPolicy.accountMarker(null), "account-a"))
        assertTrue(WidgetPolicy.needsAccountReset(WidgetPolicy.accountMarker("account-a"), "account-b"))
        assertFalse(WidgetPolicy.needsAccountReset(WidgetPolicy.accountMarker(null), null))
    }

    @Test fun delayedLocalTapCannotControlAnotherOccurrenceOfTheSameSong() {
        assertTrue(WidgetPolicy.sameLocalTrack("same-song", 1, "same-song", 1))
        assertFalse(WidgetPolicy.sameLocalTrack("same-song", 1, "same-song", 2))
        assertFalse(WidgetPolicy.sameLocalTrack("old-song", 1, "next-song", 1))
        assertFalse(WidgetPolicy.sameLocalTrack("same-song", -1, "same-song", 1))
    }

    @Test fun controlsNeverCrossAccountsOrRunAfterAStaleTap() {
        assertTrue(WidgetPolicy.owns("account-a", "account-a"))
        assertFalse(WidgetPolicy.owns("account-a", "account-b"))
        assertFalse(WidgetPolicy.owns(null, null))
        assertTrue(WidgetPolicy.commandFresh(1_000, 30_999))
        assertFalse(WidgetPolicy.commandFresh(1_000, 31_001))
        assertFalse(WidgetPolicy.commandFresh(10_000, 9_999))
    }

    @Test fun automaticTargetFavorsPlayingPhoneThenAnActiveRemoteDevice() {
        assertTrue(WidgetPolicy.chooseLocal(true, true, true, true))
        assertFalse(WidgetPolicy.chooseLocal(true, false, true, true))
        assertTrue(WidgetPolicy.chooseLocal(true, false, false, true))
        assertTrue(WidgetPolicy.chooseLocal(true, false, true, false))
        assertFalse(WidgetPolicy.chooseLocal(false, false, true, true))
    }

    @Test fun invalidOrExpiredDeviceSnapshotsCannotReceiveControls() {
        assertTrue(WidgetPolicy.remoteFresh("2026-11-11T10:00:01Z", Instant.parse("2026-11-11T10:00:00Z").toEpochMilli()))
        assertFalse(WidgetPolicy.remoteFresh("2026-11-11T10:00:00Z", Instant.parse("2026-11-11T10:00:01Z").toEpochMilli()))
        assertFalse(WidgetPolicy.remoteFresh("broken", 1))
        assertFalse("toggle" in WidgetPolicy.actions)
        assertFalse("delete" in WidgetPolicy.actions)
    }
}
