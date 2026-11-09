package net.mspanc.twinsenradio.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneTransferClientTest {
    private val config = TransferConfig("a".repeat(39), "https://test.firebaseio.com", "https://test.web.app")
    private var clock = 1_000_000L
    private val store = MemoryStore()
    private val requests = mutableListOf<Request>()
    private val responses = ArrayDeque<String>()
    private val client = PhoneTransferClient(config, store, object : TransferTransport {
        override suspend fun request(method: String, url: String, body: String?, contentType: String): String {
            requests += Request(method, url, body)
            return responses.removeFirst()
        }
    }, { clock })

    @Test fun rejectsUnsafeConfiguration() {
        for (database in listOf("http://test.firebaseio.com", "https://test.firebaseio.com.evil.test",
            "https://x@test.firebaseio.com", "https://test.firebaseio.com:444",
            "https://test.firebaseio.com/?x=1", "https://test.firebaseio.com/#frag",
            "https://test.firebaseio.com/path")) {
            assertThrows(IllegalArgumentException::class.java) {
                TransferConfig("a".repeat(39), database, "https://test.web.app")
            }
        }
    }

    @Test fun createsOpaquePairingAndPersistsBeforePut() = runBlocking {
        val session = create()
        assertTrue(session.id.matches(Regex("[0-9a-f]{64}")))
        assertTrue(session.claimSecret.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(session.id, session.claimSecret)
        assertEquals("https://test.web.app/#s=${session.id}&k=${session.claimSecret}", session.pairingUrl)
        assertEquals(clock + 300_000, session.expiresAt)
        val body = JSONObject(requests[1].body!!)
        assertEquals("owner", body.getString("ownerUid"))
        assertEquals("waiting", body.getString("status"))
        assertEquals(session.claimSecret, body.getString("claimSecret"))
        assertEquals(1, store.all().size)
        assertFalse(session.toString().contains(session.claimSecret))
        assertTrue(requests.all { it.url.startsWith("https://") })
    }

    @Test fun returnsWaitingAndSubmittedWithoutFetchingPlaylist() = runBlocking {
        val session = create()
        responses += record("waiting").toString()
        assertEquals(TransferPoll.Waiting, client.poll(session))
        responses += record("submitted").put("payload", payload("url", "https://example.com/list.m3u")).toString()
        val result = client.poll(session) as TransferPoll.Submitted
        assertEquals(TransferKind.URL, result.payload.kind)
        assertEquals("https://example.com/list.m3u", result.payload.value)
        assertEquals("", result.payload.name)
        assertTrue(requests.drop(2).all { it.method == "GET" && it.url.startsWith(config.databaseUrl) })
    }

    @Test fun expiryDeniesEvenPreviouslySubmittedPayload() = runBlocking {
        val session = create()
        clock = session.expiresAt
        assertEquals(TransferPoll.Expired, client.poll(session))
        assertEquals(TransferPoll.Expired, client.awaitPayload(session))
        assertEquals(2, requests.size)
    }

    @Test fun rejectsTamperedOwnershipAndMalformedState() = runBlocking {
        val session = create()
        responses += record("submitted").put("ownerUid", "other").toString()
        expectFailure { client.poll(session) }
        responses += record("waiting").put("payload", payload("url", "https://example.com/a")).toString()
        expectFailure { client.poll(session) }
        responses += "null"
        expectFailure { client.poll(session) }
        responses += record("done").toString()
        expectFailure { client.poll(session) }
    }

    @Test fun validatesKindsUrlLimitsAndUtf8FileBytes() {
        fun rejects(kind: String, text: String) {
            assertThrows(TransferException::class.java) { PhoneTransferClient.parsePayload(payload(kind, text)) }
        }
        rejects("url", "javascript:alert(1)")
        rejects("url", "http://example.com/list.m3u")
        val userInfoUrl = java.net.URI("https", "fixture-user", "example.com", -1,
            "/list", null, null).toASCIIString()
        rejects("url", userInfoUrl)
        rejects("url", "https://example.com/" + "x".repeat(4096))
        rejects("other", "abc")
        rejects("file", "\u00e9".repeat(524289))
        rejects("file", "#EXTM3U\u0000")
        rejects("file", "\ud800")
        assertEquals(TransferKind.FILE, PhoneTransferClient.parsePayload(payload("file", "\u00e9".repeat(524288))).kind)
        assertThrows(TransferException::class.java) {
            PhoneTransferClient.parsePayload(payload("url", "https://example.com").put("name", "n".repeat(513)))
        }
        assertThrows(TransferException::class.java) {
            PhoneTransferClient.parsePayload(payload("file", "#EXTM3U").put("extra", true))
        }
    }

    @Test fun closeAtomicallyRemovesBothKnownRecordsThenForgetsCredentials() = runBlocking {
        val session = create()
        responses += "null"
        client.close(session)
        val deletion = requests.last()
        assertEquals("PATCH", deletion.method)
        assertTrue(deletion.url.contains("/.json?auth="))
        val body = JSONObject(deletion.body!!)
        assertEquals(setOf("claims/${session.id}", "transfers/${session.id}"), body.keys().asSequence().toSet())
        assertTrue(body.isNull("claims/${session.id}"))
        assertTrue(body.isNull("transfers/${session.id}"))
        assertTrue(store.all().isEmpty())
    }

    @Test fun failedCleanupRetainsKnownCredentialsForNextAttempt() = runBlocking {
        create()
        assertEquals(1, client.cleanupKnownSessions())
        assertEquals(1, store.all().size)
        responses += "null"
        assertEquals(0, client.cleanupKnownSessions())
        assertTrue(store.all().isEmpty())
    }

    @Test fun returningCleanupRefreshesExpiredIdentity() = runBlocking {
        val session = create()
        clock += 60 * 60_000
        responses += JSONObject().put("user_id", "owner").put("id_token", "new-token")
            .put("refresh_token", "new-refresh").toString()
        responses += "null"
        client.close(session)
        assertTrue(requests[2].url.startsWith("https://securetoken.googleapis.com/"))
        assertEquals("grant_type=refresh_token&refresh_token=refresh", requests[2].body)
        assertTrue(requests.last().url.endsWith("auth=new-token"))
        assertTrue(store.all().isEmpty())
    }

    @Test fun rejectsChangedIdentityDuringRefresh() = runBlocking {
        val session = create()
        clock += 60 * 60_000
        responses += JSONObject().put("user_id", "other").put("id_token", "new-token")
            .put("refresh_token", "new-refresh").toString()
        expectFailure { client.close(session) }
        assertEquals(3, requests.size)
        assertEquals(1, store.all().size)
    }

    @Test fun cancellationPropagatesAndPreservesCleanupRecord() = runBlocking {
        val session = create()
        val started = CompletableDeferred<Unit>()
        val waiting = PhoneTransferClient(config, store, object : TransferTransport {
            override suspend fun request(method: String, url: String, body: String?, contentType: String): String {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
                return "null"
            }
        }, { clock })
        val job = launch { waiting.awaitPayload(session); fail("Cancellation returned a result") }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, store.all().size)
    }

    @Test fun requestTimeoutIsFailureInsteadOfEarlyPairingExpiry() = runBlocking {
        val session = create()
        val timeout = PhoneTransferClient(config, store, object : TransferTransport {
            override suspend fun request(method: String, url: String, body: String?, contentType: String): String =
                withTimeout(1) { CompletableDeferred<String>().await() }
        }, { clock })
        expectFailure { timeout.awaitPayload(session) }
    }

    @Test fun ownDeadlineEndsWaitingWithoutAPlaylist() = runBlocking {
        val session = create()
        val record = store.all().single()
        store.put(StoredTransfer(record.id, record.secret, record.ownerUid, record.idToken,
            record.refreshToken, record.tokenCreatedAt, clock, clock + 20))
        val waiting = PhoneTransferClient(config, store, object : TransferTransport {
            override suspend fun request(method: String, url: String, body: String?, contentType: String) =
                record("waiting").toString()
        }, { clock })
        assertEquals(TransferPoll.Expired, withTimeout(2_000) { waiting.awaitPayload(session) })
    }

    @Test fun failedCreationKeepsCredentialAndIdForReturningCleanup() = runBlocking {
        responses += JSONObject().put("localId", "owner").put("idToken", "token")
            .put("refreshToken", "refresh").toString()
        expectFailure { client.createSession() }
        assertEquals(1, store.all().size)
        assertEquals("PUT", requests.last().method)
    }

    @Test fun fullPendingStoreRejectsCreationBeforeAnonymousAuthentication() = runBlocking {
        repeat(16) { index ->
            store.put(StoredTransfer(index.toString(16).padStart(64, '0'), "secret", "owner",
                "token", "refresh", clock, clock, clock + 300000))
        }
        expectFailure { client.createSession() }
        assertTrue(requests.isEmpty())
        assertEquals(16, store.all().size)
    }

    private suspend fun create(): TransferSession {
        responses += JSONObject().put("localId", "owner").put("idToken", "token")
            .put("refreshToken", "refresh").toString()
        responses += "{}"
        return client.createSession()
    }

    private fun record(status: String): JSONObject {
        val saved = store.all().single()
        return JSONObject().put("ownerUid", saved.ownerUid).put("claimSecret", saved.secret)
            .put("createdAt", saved.createdAt).put("expiresAt", saved.expiresAt).put("status", status)
    }

    private fun payload(kind: String, text: String) = JSONObject().put("kind", kind).put("value", text)

    private suspend fun expectFailure(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected transfer rejection", failure is TransferException)
    }

    private data class Request(val method: String, val url: String, val body: String?)
    private class MemoryStore : TransferStore {
        private val records = mutableListOf<StoredTransfer>()
        override fun all() = records.toList()
        override fun put(value: StoredTransfer) { records.removeAll { it.id == value.id }; records += value }
        override fun remove(id: String) { records.removeAll { it.id == id } }
    }
}
