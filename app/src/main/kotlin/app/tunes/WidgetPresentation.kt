package app.tunes

import org.json.JSONArray
import java.time.Instant

internal object WidgetPresentation {
    /** Server revisions span device handoffs; slower requests must not redraw an older track. */
    fun accepts(previous: ConnectSnapshot?, incoming: ConnectSnapshot?): Boolean {
        if (previous == null || incoming == null) return true
        if (incoming.revision != previous.revision) return incoming.revision > previous.revision
        return runCatching { !Instant.parse(incoming.updatedAt).isBefore(Instant.parse(previous.updatedAt)) }
            .getOrDefault(false)
    }

    /** Keep fresh leases cached without sending identical RemoteViews or changing button IDs. */
    fun key(entry: WidgetEntry?, message: String?, nowMs: Long = System.currentTimeMillis()): String {
        val remote = entry?.remote?.let { runCatching { ConnectSnapshot.fromJson(it) }.getOrNull() }
        return JSONArray(listOf(entry?.owner, entry?.local, entry?.title, entry?.artist, entry?.url,
            entry?.playing, entry?.previous, entry?.next, entry?.queueIndex, remote?.deviceId,
            remote?.instanceId, remote?.revision, remote?.queueIndex, remote?.deviceName,
            remote?.isFresh(nowMs), message)).toString()
    }
}
