package app.tunes

import org.json.JSONArray
import org.json.JSONObject
import java.io.RandomAccessFile

/** A bounded second native decoder used only while preparing/mixing the next song. */
internal class MpvAudioChannel(val process: Process, val pipe: RandomAccessFile, val name: String,
    private val canPlay: () -> Boolean = { true }) : AutoCloseable {
    private var serial = 0
    fun command(vararg args: Any): JSONObject {
        val id = ++serial
        val effective = if (args.size >= 3 && args[0] == "set_property" && args[1] == "pause" && args[2] == false && !canPlay())
            args.toMutableList().also { it[2] = true }.toTypedArray() else args
        pipe.write((JSONObject().put("command", JSONArray(effective)).put("request_id", id).toString() + "\n").toByteArray())
        while (true) {
            val line = pipe.readLine() ?: error("The prepared audio engine closed.")
            val reply = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (reply.optInt("request_id", -1) == id && reply.has("error")) return reply
        }
    }
    fun ready(): Boolean = !command("get_property", "idle-active").optBoolean("data", true) &&
        command("get_property", "time-pos").opt("data") is Number
    override fun close() { process.destroy(); runCatching { pipe.close() } }
}
