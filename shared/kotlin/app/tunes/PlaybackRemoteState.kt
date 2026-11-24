package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** The account's current playback, independent of whether this device has a local queue. */
class PlaybackRemoteState(
    private val sync: Sync,
    private val client: ConnectClient = ConnectClient(sync),
    private val accountId: () -> String? = { sync.userId },
    private val clock: () -> Long = System::currentTimeMillis,
    private val confirmationTimeoutMs: Long = 12_000L,
    private val receiptPollMs: Long = 750L,
) {
    private data class Request(val id: String, val snapshot: ConnectSnapshot, val action: String, val startedAt: Long)
    private val lock = Mutex()
    private var owner: String? = null
    private var generation = Long.MIN_VALUE
    private var waiting: Request? = null
    private var commandAttempt: Any? = null
    private var failures = 0
    var snapshot by mutableStateOf<ConnectSnapshot?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var pending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var setupRequired by mutableStateOf(false)
        private set

    val pollDelayMs: Long get() = when {
        accountId() == null -> 15_000L
        setupRequired -> 60_000L
        failures > 0 -> (2_000L shl failures.coerceAtMost(4)).coerceAtMost(30_000L)
        pending -> 1_000L
        else -> 2_000L
    }

    fun positionMs(atMillis: Long = clock()): Long = snapshot?.estimatedPositionMs(atMillis) ?: 0L

    /** Call immediately on account changes, even while the UI is in the background. */
    fun resetForAccount() {
        val account = accountId()
        val epoch = sync.accountGeneration
        if (owner == account && generation == epoch) return
        owner = account; generation = epoch
        snapshot = null; waiting = null; commandAttempt = null; pending = false; busy = false
        error = null; message = null; setupRequired = false; failures = 0
    }

    private fun current(account: String?, epoch: Long) = account != null &&
        accountId() == account && owner == account && sync.accountGeneration == epoch && generation == epoch

    suspend fun refresh() {
        if (!lock.tryLock()) return
        try {
            resetForAccount()
            val account = owner ?: return
            val epoch = generation
            waiting?.let { request ->
                val receipt = client.commandStatus(request.id)
                if (!current(account, epoch)) { resetForAccount(); return }
                if (receipt != null && !receipt.pending) finish(receipt)
                else if (clock() - request.startedAt >= 35_000L) {
                    waiting = null; pending = false
                    message = "The device did not confirm this control. Refresh before trying again."
                }
            }
            val state = client.state()
            if (!current(account, epoch)) { resetForAccount(); return }
            snapshot = state
            error = null; setupRequired = false; failures = 0
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            if (owner != accountId() || generation != sync.accountGeneration) resetForAccount()
            else failed(failure)
        } finally { lock.unlock() }
    }

    /** True means the playback device confirmed execution. A timeout is never treated as success. */
    suspend fun sendControl(action: String, refreshAfterConfirmation: Boolean = true): Boolean {
        require(action in setOf("previous", "next", "play", "pause", "toggle")) { "Unsupported playback control." }
        resetForAccount()
        if (busy || pending) return false
        val account = owner
        val epoch = generation
        if (account == null) { error = "Sign in to control your devices."; return false }
        val target = snapshot
        if (target == null || !target.isFresh(clock())) {
            error = "The playback device is offline. Open Troit there and refresh."
            return false
        }
        val explicit = if (action == "toggle") if (target.playing) "pause" else "play" else action
        // Reserve one click before suspending behind a refresh. Further taps cannot queue skips.
        val attempt = Any()
        commandAttempt = attempt; busy = true
        var locked = false
        try {
            lock.lock(); locked = true
            if (!current(account, epoch) || commandAttempt !== attempt) { resetForAccount(); return false }
            if (!target.isFresh(clock())) {
                error = "The playback device is offline. Open Troit there and refresh."
                return false
            }
            val latest = snapshot
            if (latest == null || latest.deviceId != target.deviceId || latest.instanceId != target.instanceId ||
                latest.revision != target.revision || latest.song.url != target.song.url || latest.queueIndex != target.queueIndex) {
                message = "The song or device changed. Refresh and try again."
                return false
            }
            val request = Request(UUID.randomUUID().toString(), target, explicit, clock())
            waiting = request; pending = true; busy = true; error = null; message = "Waiting for ${target.deviceName}…"
            val result = withTimeoutOrNull(confirmationTimeoutMs) {
                var receipt: ConnectCommandResult? = null
                var needsUpload = true
                while (current(account, epoch)) {
                    try {
                        receipt = if (needsUpload) client.command(request.snapshot, request.action, request.id)
                            else client.commandStatus(request.id)
                        if (!current(account, epoch)) return@withTimeoutOrNull null
                        // An uncertain upload is retried with exactly the original UUID and explicit action.
                        needsUpload = receipt == null
                        if (receipt != null && !receipt.pending) return@withTimeoutOrNull receipt
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) {
                        if (!current(account, epoch)) return@withTimeoutOrNull null
                        if (failure is AccountApiException) throw failure
                        // Read the receipt before retrying an upload that may already have succeeded.
                        needsUpload = false
                    }
                    delay(receiptPollMs)
                }
                null
            }
            if (!current(account, epoch)) { resetForAccount(); return false }
            if (result == null) {
                message = "Still waiting for the device. Playback has not been confirmed."
                return false
            }
            finish(result)
            // Publishers report actual playback before ACK; show that state after confirmation.
            if (refreshAfterConfirmation) try {
                val state = client.state()
                if (current(account, epoch)) snapshot = state
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* A confirmed result remains confirmed if the follow-up read fails. */ }
            return current(account, epoch) && result.executed
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            if (!current(account, epoch)) resetForAccount()
            else {
                // Server errors are definitive; transport errors inside the loop retain the receipt.
                waiting = null; pending = false
                failed(failure)
            }
            return false
        } finally {
            if (commandAttempt === attempt) { commandAttempt = null; busy = false }
            if (locked) lock.unlock()
        }
    }

    private fun finish(receipt: ConnectCommandResult) {
        waiting = null; pending = false
        message = when (receipt.status) {
            "executed" -> "Playback updated."
            "stale" -> "The song or device changed. Refresh and try again."
            "expired" -> "The device did not respond in time."
            else -> "The device could not apply this control."
        }
    }

    private fun failed(failure: Exception) {
        failures++
        setupRequired = failure is AccountApiException && failure.setupRequired
        error = if (setupRequired) "Connected playback needs the Troit Connect database update."
            else "Couldn't connect to playback. Troit will retry automatically."
    }
}
