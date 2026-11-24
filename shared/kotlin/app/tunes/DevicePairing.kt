package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun normalizePairingCode(code: String): String = code.replace(Regex("[-\\s]"), "").uppercase(java.util.Locale.ROOT)
internal fun pairingCodeHash(code: String): String = MessageDigest.getInstance("SHA-256")
    .digest(normalizePairingCode(code).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun validPairingCode(code: String) = normalizePairingCode(code).matches(Regex("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}"))
typealias PairingTransport = suspend (String) -> String

/** TV possession secrets exist in memory only. The phone approves a new, independent auth session. */
class DevicePairingState(
    private val sync: Sync,
    private val deviceName: String,
    private val request: PairingTransport = ::pairingEdgeRequest,
    private val clock: () -> Long = System::currentTimeMillis,
    private val accept: suspend (JSONObject, Long, () -> Boolean) -> Boolean = { reply, generation, allowed -> sync.acceptPairedSession(reply, generation, allowed) },
) {
    private data class Ticket(val id: String, val secret: String, val rawCode: String, val generation: Long, var expiresAt: Long = 0)
    private var ticket: Ticket? = null
    private var operation = 0L
    var code by mutableStateOf<String?>(null)
        private set
    var expiresAt by mutableStateOf(0L)
        private set
    var busy by mutableStateOf(false)
        private set
    var completed by mutableStateOf(false)
        private set
    var status by mutableStateOf("Choose Pair with phone to get a TV code.")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var setupRequired by mutableStateOf(false)
        private set
    val waiting get() = ticket != null && code != null && !completed && clock() < expiresAt

    suspend fun begin() {
        if (busy || sync.userId != null) return
        val epoch = ++operation
        val random = SecureRandom()
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val next = Ticket(UUID.randomUUID().toString(), ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) },
            buildString { repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) } }, sync.accountGeneration)
        ticket = next; busy = true; completed = false; code = null; error = null; setupRequired = false
        try {
            val reply = JSONObject(request(body(next, "create").put("code", next.rawCode).put("device_name", deviceName.take(64)).toString()))
            if (!current(next, epoch)) return
            next.expiresAt = Instant.parse(reply.getString("expires_at")).toEpochMilli()
            expiresAt = next.expiresAt
            code = next.rawCode.take(4) + "-" + next.rawCode.drop(4)
            status = "On your signed-in phone, open Devices → Pair a TV and enter this code."
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { if (current(next, epoch)) fail(failure) }
        finally { if (operation == epoch) busy = false }
    }

    suspend fun poll() {
        val next = ticket ?: return
        if (busy || completed) return
        if (!current(next, operation)) { clear(); return }
        if (clock() >= next.expiresAt) { clear(); status = "This code expired. Get a new code."; return }
        val epoch = operation
        busy = true
        try {
            val reply = JSONObject(request(body(next, "poll").toString()))
            if (!current(next, epoch)) return
            when (reply.optString("status")) {
                "pending", "approved" -> { error = null; status = "Waiting for approval from your phone…" }
                "ready" -> {
                    val adopted = accept(reply.getJSONObject("session"), next.generation) { current(next, epoch) }
                    if (operation != epoch) return
                    if (adopted) {
                        completed = true; code = null; ticket = null
                        status = "TV connected. Your library is ready to sync."
                        runCatching { request(body(next, "complete").toString()) }
                    } else { clear(); status = "Sign-in changed. Get a new TV code if needed." }
                }
                else -> { clear(); status = "This code is unavailable or expired. Get a new code." }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { if (current(next, epoch)) fail(failure) }
        finally { if (operation == epoch) busy = false }
    }

    /** Clear local capability synchronously on closing, before any suspended HTTP cleanup. */
    fun dismiss() {
        clear()
        status = "Pairing closed."
    }

    suspend fun cancel() {
        val old = ticket
        clear()
        status = "Pairing closed."
        if (old != null) runCatching { request(body(old, "cancel").toString()) }
    }

    private fun current(next: Ticket, epoch: Long) = operation == epoch && ticket === next &&
        sync.userId == null && sync.accountGeneration == next.generation
    private fun body(next: Ticket, action: String) = JSONObject().put("action", action).put("id", next.id).put("secret", next.secret)
    private fun clear() { operation++; ticket = null; code = null; expiresAt = 0; busy = false }
    private fun fail(failure: Exception) {
        setupRequired = failure is PairingApiException && failure.setupRequired
        error = if (setupRequired) "TV pairing needs the server update." else failure.message?.take(160) ?: "Couldn't connect. Pairing will retry."
    }
}

internal class PairingApiException(val setupRequired: Boolean, message: String) : Exception(message)
private val pairingHttp by lazy { Yt.http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build() }
private suspend fun pairingEdgeRequest(body: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("${Sync.backendUrl}/functions/v1/device-pairing")
        .header("apikey", Sync.publishableKey).post(body.toRequestBody("application/json".toMediaType())).build()
    pairingHttp.newCall(request).execute().use { response ->
        val source = response.body?.source() ?: throw PairingApiException(false, "The pairing server returned no response.")
        source.request(64_001)
        if (source.buffer.size > 64_000) throw PairingApiException(false, "The pairing response was too large.")
        val text = source.readUtf8()
        if (!response.isSuccessful) {
            val reply = runCatching { JSONObject(text) }.getOrNull()
            throw PairingApiException(response.code == 404 || reply?.optString("code") == "setup_required",
                reply?.optString("error")?.takeIf(String::isNotBlank)?.take(160) ?: "Couldn't reach TV pairing. Try again shortly.")
        }
        text
    }
}

data class PairingApproval(val id: String, val deviceName: String, val expiresAt: Long, internal val codeHash: String)

/** Phone-side approval always targets the exact inspected request and the same signed-in account. */
class PairingApprovalState(private val sync: Sync,
    private val request: suspend (String, String) -> String = { path, body -> sync.accountRequest(path, "POST", body) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var inspected by mutableStateOf<PairingApproval?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var approved by mutableStateOf(false)
        private set
    private var owner: String? = null
    private var generation = Long.MIN_VALUE
    fun reset() { inspected = null; approved = false; error = null }
    suspend fun inspect(code: String) {
        if (busy) return
        reset()
        if (!validPairingCode(code)) { error = "Enter the eight-character code shown on your TV."; return }
        val account = sync.userId ?: run { error = "Sign in on this device first."; return }
        val epoch = sync.accountGeneration
        owner = account; generation = epoch; busy = true
        try {
            val hash = pairingCodeHash(code)
            val text = request("rpc/troit_pairing_inspect", JSONObject().put("p_code_hash", hash).toString())
            if (sync.userId != account || sync.accountGeneration != epoch) { reset(); return }
            if (text.trim() == "null") error = "This code is unavailable or expired. Check the TV and try again."
            else JSONObject(text).let { inspected = PairingApproval(it.getString("id"), it.getString("device_name"), Instant.parse(it.getString("expires_at")).toEpochMilli(), hash) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { if (sync.userId == account && sync.accountGeneration == epoch) error = if (failure is AccountApiException && failure.setupRequired) "TV pairing needs the server update." else "Couldn't check this code. Try again shortly." }
        finally { busy = false }
    }
    suspend fun approve() {
        val target = inspected ?: return
        if (busy) return
        if (sync.userId != owner || sync.accountGeneration != generation) { reset(); return }
        if (clock() >= target.expiresAt) { inspected = null; error = "This code expired. Get a new code on the TV."; return }
        val account = owner; val epoch = generation; busy = true; error = null
        try {
            request("rpc/troit_pairing_approve", JSONObject().put("p_code_hash", target.codeHash).put("p_pairing_id", target.id).toString())
            if (sync.userId == account && sync.accountGeneration == epoch) { approved = true; inspected = null }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (sync.userId == account && sync.accountGeneration == epoch) error = "Couldn't approve this TV. Check its code and try again." }
        finally { busy = false }
    }
}
