package app.tunes

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WidgetCommandReconciliationTest {
    private val original = ConnectSnapshot("pc", "runtime", "My PC",
        Song("First", "Artist", "https://example.com/first"), true, 9_000, 5,
        "2026-11-11T10:00:00Z", "2099-11-11T10:00:45Z", true, 1)
    private val pending = ConnectCommandResult("same-command", "pending")
    private val executed = pending.copy(status = "executed")
    private val next = original.copy(song = original.song.copy(title = "Next", url = "https://example.com/next"),
        revision = 6, queueIndex = 2, positionMs = 0)

    @Test fun latePublicationAfterAcknowledgementUpdatesWithoutAnotherRefresh() = runBlocking {
        var reads = 0
        var receiptReads = 0
        val seen = mutableListOf<String?>()
        val update = reconcileWidgetCommand(original, "next", pending, { true },
            readReceipt = { receiptReads++; executed },
            readState = { if (++reads < 6) original else next },
            onState = { state, _ -> seen += state?.song?.title }, pause = {})
        assertTrue(update.confirmed)
        assertEquals(6, reads)
        assertEquals("Next", seen.last())
        assertEquals("A final receipt is not repeatedly fetched", 1, receiptReads)
    }

    @Test fun actualTrackIsDrawnEvenBeforeTheReceiptArrives() = runBlocking {
        val statuses = ArrayDeque(listOf(pending, pending, executed))
        val drawnAtStatus = mutableListOf<String?>()
        val update = reconcileWidgetCommand(original, "next", pending, { true },
            readReceipt = { statuses.removeFirst() }, readState = { next },
            onState = { state, receipt ->
                assertEquals("Next", state?.song?.title)
                drawnAtStatus += receipt?.status
            }, pause = {})
        assertEquals(listOf("pending", "pending", "executed"), drawnAtStatus)
        assertTrue(update.confirmed)
    }

    @Test fun heartbeatDoesNotPretendThatNextReachedThePlayer() = runBlocking {
        var reads = 0
        val update = reconcileWidgetCommand(original, "next", executed, { true },
            readReceipt = { fail("An executed command needs no more receipt reads"); null },
            readState = { reads++; original.copy(updatedAt = "2026-11-11T10:00:15Z", positionMs = 24_000) },
            onState = { _, _ -> }, pause = {}, attempts = 4)
        assertFalse(update.confirmed)
        assertEquals(4, reads)
    }

    @Test fun playAndPauseAreConfirmedWithoutRequiringANewTrackRevision() = runBlocking {
        var reads = 0
        val update = reconcileWidgetCommand(original, "pause", executed, { true },
            readReceipt = { executed }, readState = { if (++reads == 1) original else original.copy(playing = false) },
            onState = { _, _ -> }, pause = {})
        assertTrue(update.confirmed)
        assertFalse(update.state!!.playing)
        assertEquals(2, reads)
        assertTrue(widgetActionVisible(original.copy(playing = false), original, "play"))
    }

    @Test fun accountSwitchDuringAReadCannotDrawTheOldAccountsTrack() = runBlocking {
        var ownerCurrent = true
        var draws = 0
        val update = reconcileWidgetCommand(original, "next", executed, { ownerCurrent },
            readReceipt = { executed }, readState = { ownerCurrent = false; next },
            onState = { _, _ -> draws++ }, pause = {})
        assertFalse(update.accountValid)
        assertEquals(0, draws)
    }

    @Test fun pendingCommandsStopAfterABoundedNumberOfReadsAndKeepTheirReceipt() = runBlocking {
        var reads = 0
        val update = reconcileWidgetCommand(original, "next", pending, { true },
            readReceipt = { pending }, readState = { reads++; original },
            onState = { _, _ -> }, pause = {}, attempts = 3)
        assertFalse(update.confirmed)
        assertEquals(3, reads)
        assertEquals(pending, update.receipt)
    }

    @Test fun previousRecognizesPositionResetAndADeviceHandoffUsesActualState() {
        assertTrue(widgetActionVisible(original, original.copy(positionMs = 0), "previous"))
        assertFalse(widgetActionVisible(original, original.copy(positionMs = 12_000), "previous"))
        assertTrue(widgetActionVisible(original, original.copy(instanceId = "new-runtime"), "next"))
        assertFalse(widgetActionVisible(original, next.copy(fresh = false), "next"))
        val atStart = original.copy(positionMs = 0)
        assertTrue(widgetActionVisible(atStart,
            atStart.copy(updatedAt = "2026-11-11T10:00:02Z"), "previous"))
        assertFalse(widgetActionVisible(atStart, atStart, "previous"))
    }
}
