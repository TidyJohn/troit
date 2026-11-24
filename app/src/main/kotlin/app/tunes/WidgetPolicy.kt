package app.tunes

import java.time.Instant

/** Small shared rules keep background button work bounded and tied to the displayed account. */
internal object WidgetPolicy {
    const val COMMAND_TTL_MS = 30_000L
    val actions = setOf("previous", "next", "play", "pause")

    fun owns(expected: String?, current: String?) = expected != null && expected == current

    fun accountMarker(owner: String?) = owner ?: "<guest>"

    fun needsAccountReset(previousMarker: String?, currentOwner: String?) =
        previousMarker != null && previousMarker != accountMarker(currentOwner)

    fun sameLocalTrack(expectedUrl: String, expectedIndex: Int, currentUrl: String?, currentIndex: Int) =
        expectedUrl.isNotBlank() && expectedIndex >= 0 && expectedUrl == currentUrl && expectedIndex == currentIndex

    fun sameTarget(original: ConnectSnapshot, renewed: ConnectSnapshot) =
        original.deviceId == renewed.deviceId && original.instanceId == renewed.instanceId &&
            original.revision == renewed.revision && original.song.url == renewed.song.url &&
            original.queueIndex == renewed.queueIndex

    fun commandFresh(issuedAtMs: Long, nowMs: Long) = issuedAtMs > 0 && nowMs - issuedAtMs in 0..COMMAND_TTL_MS

    fun remoteFresh(expiresAt: String, nowMs: Long): Boolean = runCatching {
        Instant.parse(expiresAt).toEpochMilli() > nowMs
    }.getOrDefault(false)

    fun chooseLocal(hasLocal: Boolean, localPlaying: Boolean, remoteFresh: Boolean, remotePlaying: Boolean) =
        hasLocal && (localPlaying || !remoteFresh || !remotePlaying)
}
