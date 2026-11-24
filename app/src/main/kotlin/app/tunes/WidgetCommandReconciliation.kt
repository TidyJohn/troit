package app.tunes

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

internal data class WidgetCommandUpdate(
    val receipt: ConnectCommandResult?,
    val state: ConnectSnapshot?,
    val confirmed: Boolean,
    val accountValid: Boolean,
)

/** An acknowledgement and the next playback publication are separate network events. Wait for both. */
internal suspend fun reconcileWidgetCommand(
    original: ConnectSnapshot,
    action: String,
    initialReceipt: ConnectCommandResult?,
    isCurrentAccount: () -> Boolean,
    readReceipt: suspend () -> ConnectCommandResult?,
    readState: suspend () -> ConnectSnapshot?,
    onState: suspend (ConnectSnapshot?, ConnectCommandResult?) -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
    attempts: Int = 12,
    timeoutMs: Long = 20_000,
): WidgetCommandUpdate {
    require(attempts in 1..20)
    var receipt = initialReceipt
    var state: ConnectSnapshot? = null
    var valid = true
    val completed = withTimeoutOrNull(timeoutMs) {
        repeat(attempts) { attempt ->
            if (!isCurrentAccount()) { valid = false; return@withTimeoutOrNull false }
            if (receipt?.pending != false) receipt = readReceipt() ?: receipt
            if (!isCurrentAccount()) { valid = false; return@withTimeoutOrNull false }
            state = readState()
            if (!isCurrentAccount()) { valid = false; return@withTimeoutOrNull false }
            // Draw actual metadata as soon as it arrives, even if its receipt is still in flight.
            onState(state, receipt)
            if (receipt != null && receipt?.pending == false) {
                if (receipt?.executed != true) return@withTimeoutOrNull true
                if (widgetActionVisible(original, state, action)) return@withTimeoutOrNull true
            }
            if (attempt < attempts - 1) pause(1_000)
        }
        false
    } ?: false
    return WidgetCommandUpdate(receipt, state, completed, valid && isCurrentAccount())
}

/** Position/heartbeat changes alone cannot prove that a next command reached the player. */
internal fun widgetActionVisible(original: ConnectSnapshot, current: ConnectSnapshot?, action: String): Boolean {
    if (current == null || !current.fresh) return false
    if (current.deviceId != original.deviceId || current.instanceId != original.instanceId) return true
    val trackChanged = current.revision != original.revision || current.queueIndex != original.queueIndex ||
        current.song.url != original.song.url
    val publishedAfter = runCatching {
        Instant.parse(current.updatedAt).isAfter(Instant.parse(original.updatedAt))
    }.getOrDefault(false)
    return when (action) {
        "next" -> trackChanged
        "previous" -> trackChanged || current.positionMs < original.positionMs ||
            (publishedAfter && current.positionMs in 0..3_000)
        "play" -> current.playing
        "pause" -> !current.playing
        else -> false
    }
}
