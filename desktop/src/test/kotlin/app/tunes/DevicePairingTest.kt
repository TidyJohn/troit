package app.tunes

import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Offline fixtures only: no real account, email, Supabase request or authentication token. */
class DevicePairingTest {
    private val owner = "00000000-0000-0000-0000-000000000001"
    private val requestId = "00000000-0000-0000-0000-000000000010"
    private val time = Instant.parse("2026-11-15T00:00:00Z").toEpochMilli()
    private fun session(id: String = owner) = JSONObject().put("access_token", "fixture-access-".repeat(10)).put("refresh_token", "new-tv-refresh-fixture")
        .put("user", JSONObject().put("id", id).put("email", "unverified-reply@example.invalid"))
    private class Fixture(signedIn: Boolean = false, val user: String = "00000000-0000-0000-0000-000000000001", interceptor: Interceptor? = null) : AutoCloseable {
        val dir = Files.createTempDirectory("troit-pairing-fixture").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val client = OkHttpClient.Builder().addInterceptor(interceptor ?: Interceptor { error("Unexpected network request in pairing fixture") }).build()
        init { if (signedIn) File(dir,"session.json").writeText(JSONObject().put("access","phone-fixture-access").put("refresh","phone-fixture-refresh")
            .put("userId",user).put("email","phone@example.invalid").toString()) }
        val sync = Sync(dir,"TV fixture",Library(File(dir,"library.json")),scope,client)
        override fun close() { scope.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    private fun created() = JSONObject().put("expires_at",Instant.ofEpochMilli(time+600_000).toString()).put("status","pending").toString()
    private fun inspected() = JSONObject().put("id",requestId).put("device_name","Living room TV").put("expires_at",Instant.ofEpochMilli(time+600_000).toString()).toString()
    private fun response(chain: Interceptor.Chain, body: String, code: Int = 200) = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun codesNormalizeWithoutAmbiguousCharactersAndHashConsistently() {
        assertTrue(validPairingCode("abcd-efgh"))
        assertTrue(validPairingCode("ABCD EFGH"))
        assertFalse(validPairingCode("ABCD-O01I"))
        assertFalse(validPairingCode("ABCDEFG"))
        assertEquals(pairingCodeHash("abcdefgh"),pairingCodeHash("ABCD-EFGH"))
        assertEquals(64,pairingCodeHash("ABCDEFGH").length)
    }

    @Test fun tvPollUsesOnePossessionSecretAndAcceptsOnlyItsApprovedSession() = runBlocking {
        Fixture().use { f ->
            val requests = mutableListOf<JSONObject>()
            var accepted: JSONObject? = null
            val state = DevicePairingState(f.sync,"Living room TV", { text ->
                val body = JSONObject(text); requests += body
                when(body.getString("action")) {
                    "create" -> created()
                    "poll" -> JSONObject().put("status","ready").put("session",session()).toString()
                    else -> "{}"
                }
            }, {time}, { reply, generation, allowed -> assertEquals(f.sync.accountGeneration,generation); assertTrue(allowed()); accepted=reply; true })
            state.begin()
            assertTrue(state.waiting)
            assertTrue(validPairingCode(state.code!!))
            state.poll()
            assertTrue(state.completed)
            assertEquals("new-tv-refresh-fixture",accepted!!.getString("refresh_token"))
            assertEquals(listOf("create","poll","complete"),requests.map { it.getString("action") })
            assertEquals(1,requests.map { it.getString("id") }.distinct().size)
            assertEquals(1,requests.map { it.getString("secret") }.distinct().size)
            assertEquals(64,requests.first().getString("secret").length)
            assertTrue(requests.none { it.has("access_token") || it.has("refresh_token") })
        }
    }

    @Test fun logoutGenerationOrClosingTvDiscardsAnInflightApprovedSession() = runBlocking {
        for (close in listOf(false,true)) Fixture().use { f ->
            val requested = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var adoptions = 0
            val state = DevicePairingState(f.sync,"TV", { text ->
                if(JSONObject(text).getString("action")=="create") created() else {
                    requested.complete(Unit); release.await()
                    JSONObject().put("status","ready").put("session",session()).toString()
                }
            }, {time}, { _,_,allowed -> if (allowed()) { adoptions++; true } else false })
            state.begin()
            val poll = async { state.poll() }
            requested.await()
            if(close) state.dismiss() else f.sync.signOut()
            release.complete(Unit); poll.await()
            assertEquals(0,adoptions)
            assertFalse(state.completed)
        }
    }

    @Test fun expiryStopsNetworkPollingAndMissingServerIsVisible() = runBlocking {
        Fixture().use { f ->
            var current=time; var requests=0
            val state = DevicePairingState(f.sync,"TV", { requests++; created() }, {current})
            state.begin(); current+=600_001; state.poll(); state.poll()
            assertEquals(1,requests)
            assertFalse(state.waiting)
            assertTrue(state.status.contains("expired"))
            val missing = DevicePairingState(f.sync,"TV", { throw PairingApiException(true,"not installed") }, {time})
            missing.begin()
            assertTrue(missing.setupRequired)
            assertFalse(missing.busy)
        }
    }

    @Test fun approvalShowsAnInspectedDeviceBeforeSendingExactCodeAndRequestId() = runBlocking {
        Fixture(true).use { f ->
            val requests = mutableListOf<Pair<String,JSONObject>>()
            val state = PairingApprovalState(f.sync,{path,body ->
                requests += path to JSONObject(body)
                if(path.endsWith("inspect")) inspected() else "{\"status\":\"approved\"}"
            }, {time})
            state.inspect("abcd-efgh")
            assertEquals("Living room TV",state.inspected?.deviceName)
            assertEquals(1,requests.size)
            state.approve()
            assertTrue(state.approved)
            assertEquals("rpc/troit_pairing_approve",requests.last().first)
            assertEquals(requestId,requests.last().second.getString("p_pairing_id"))
            assertEquals(pairingCodeHash("ABCDEFGH"),requests.last().second.getString("p_code_hash"))
        }
    }

    @Test fun lateInspectionCannotBeApprovedByAnotherAccountGeneration() = runBlocking {
        Fixture(true).use { f ->
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); var approvals=0
            val state = PairingApprovalState(f.sync,{ path,_ ->
                if(path.endsWith("inspect")) { entered.complete(Unit); release.await(); inspected() }
                else { approvals++; "{}" }
            }, {time})
            val pending=async { state.inspect("ABCDEFGH") }
            entered.await(); f.sync.signOut(); release.complete(Unit); pending.await()
            state.approve()
            assertNull(state.inspected)
            assertFalse(state.approved)
            assertEquals(0,approvals)
        }
    }

    @Test fun sessionIsValidatedWithAuthServerBeforeUsingCanonicalIdentityAndPersisting() = runBlocking {
        var validationCalls=0
        Fixture(interceptor=Interceptor { chain ->
            if(chain.request().url.encodedPath=="/auth/v1/user") {
                validationCalls++
                response(chain,JSONObject().put("id",owner).put("email","canonical@example.invalid").toString())
            } else response(chain,"[]")
        }).use { f ->
            assertTrue(f.sync.acceptPairedSession(session(),f.sync.accountGeneration))
            assertEquals(1,validationCalls)
            assertEquals(owner,f.sync.userId)
            assertEquals("canonical@example.invalid",f.sync.email)
            val stored=JSONObject(File(f.dir,"session.json").readText())
            assertEquals("new-tv-refresh-fixture",stored.getString("refresh"))
            assertNotEquals("phone-fixture-refresh",stored.getString("refresh"))
        }
    }

    @Test fun logoutWhileAuthVerificationIsPendingCannotWriteAPairedSession() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        Fixture(interceptor=Interceptor { chain ->
            entered.complete(Unit); runBlocking { release.await() }
            response(chain,JSONObject().put("id",owner).put("email","canonical@example.invalid").toString())
        }).use { f ->
            val epoch=f.sync.accountGeneration
            val pending=async { f.sync.acceptPairedSession(session(),epoch) }
            entered.await(); f.sync.signOut(); release.complete(Unit)
            assertFalse(pending.await())
            assertNull(f.sync.userId)
            assertFalse(File(f.dir,"session.json").exists())
        }
    }

    @Test fun closingThePairingDialogDuringAuthVerificationPreventsSessionAdoption() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        Fixture(interceptor=Interceptor { chain ->
            entered.complete(Unit); runBlocking { release.await() }
            response(chain,JSONObject().put("id",owner).put("email","canonical@example.invalid").toString())
        }).use { f ->
            val state=DevicePairingState(f.sync,"TV", { text ->
                if(JSONObject(text).getString("action")=="create") created()
                else JSONObject().put("status","ready").put("session",session()).toString()
            }, {time})
            state.begin()
            val pending=async { state.poll() }
            entered.await(); state.dismiss(); release.complete(Unit); pending.await()
            assertNull(f.sync.userId)
            assertFalse(File(f.dir,"session.json").exists())
            assertFalse(state.completed)
        }
    }
}
