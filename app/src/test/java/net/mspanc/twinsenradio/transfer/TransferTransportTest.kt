package net.mspanc.twinsenradio.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch

class TransferTransportTest {
    @Test fun disablesRedirectsAndUsesFirebasePatchOverride() = runBlocking {
        val connection = FakeConnection()
        val transport = UrlConnectionTransferTransport { connection }
        assertEquals("{}", transport.request("PATCH", "https://test.firebaseio.com/.json", "{}", "application/json"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("PATCH", connection.getRequestProperty("X-HTTP-Method-Override"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertEquals("{}", connection.output.toString("UTF-8"))
        withTimeout(2_000) { connection.disconnected.await() }
        assertTrue(connection.disconnected.isCompleted)
    }

    @Test fun rejectsRedirectsAndHttpErrors() = runBlocking {
        for (code in listOf(301, 302, 307, 400, 401, 403, 500)) {
            val connection = FakeConnection().apply { status = code }
            rejected(connection)
            assertFalse(connection.readStarted.isCompleted)
            withTimeout(2_000) { connection.disconnected.await() }
            assertTrue(connection.disconnected.isCompleted)
        }
    }

    @Test fun enforcesDeclaredAndStreamingResponseCaps() = runBlocking {
        val declared = FakeConnection().apply { length = 8L * 1024 * 1024 + 1 }
        rejected(declared)
        assertFalse(declared.readStarted.isCompleted)
        rejected(FakeConnection().apply { bytes = ByteArray(8 * 1024 * 1024 + 1) { 'a'.code.toByte() } })
    }

    @Test fun rejectsMalformedUtf8() = runBlocking {
        rejected(FakeConnection().apply { bytes = byteArrayOf(0xc3.toByte(), 0x28) })
    }

    @Test fun acceptsEscapingHeavyOneMiBPlaylistThroughRealJsonWireFormat() = runBlocking {
        val prefix = "#EXTM3U\n#"
        val suffix = "\nhttps://example.com/audio\n"
        val playlist = prefix + "\\".repeat(PhoneTransferClient.MAX_FILE_BYTES - prefix.length - suffix.length) + suffix
        assertEquals(PhoneTransferClient.MAX_FILE_BYTES, playlist.toByteArray(Charsets.UTF_8).size)
        val payload = JSONObject().put("kind", "file").put("value", playlist).put("name", "Fixture")
        val submitted = JSONObject().put("ownerUid", "fixture-owner").put("claimSecret", "a".repeat(64))
            .put("createdAt", 1000000L).put("expiresAt", 1300000L).put("status", "submitted")
            .put("payload", payload).toString()
        val wire = submitted.toByteArray(Charsets.UTF_8)
        assertTrue("Fixture must exceed the old two-MiB wire cap", wire.size > 2 * 1024 * 1024)
        val connection = FakeConnection().apply { bytes = wire; length = wire.size.toLong() }
        val response = try {
            UrlConnectionTransferTransport { connection }
                .request("GET", "https://test.firebaseio.com/.json", null, "application/json")
        } catch (error: TransferException) {
            throw AssertionError("Valid one-MiB playlist rejected after JSON escaping", error)
        }
        val received = PhoneTransferClient.parsePayload(JSONObject(response).getJSONObject("payload"))
        assertEquals(playlist, received.value)
        assertEquals(TransferKind.FILE, received.kind)
    }

    @Test fun cancellationDisconnectsActiveRequest() = runBlocking {
        val connection = FakeConnection().apply { blockRead = true }
        val transport = UrlConnectionTransferTransport { connection }
        val job = launch { transport.request("GET", "https://test.firebaseio.com/.json", null, "application/json") }
        withTimeout(2_000) { connection.readStarted.await() }
        job.cancelAndJoin()
        withTimeout(2_000) { connection.disconnected.await() }
        assertTrue(job.isCancelled)
    }

    private suspend fun rejected(connection: FakeConnection) {
        val failure = runCatching {
            UrlConnectionTransferTransport { connection }.request("GET", "https://test.firebaseio.com/.json", null, "application/json")
        }.exceptionOrNull()
        assertTrue("Unsafe response accepted", failure is TransferException)
    }

    private class FakeConnection : HttpURLConnection(URL("https://test.firebaseio.com")) {
        var status = 200
        var length = -1L
        var bytes = "{}".toByteArray()
        var blockRead = false
        val output = ByteArrayOutputStream()
        val readStarted = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        private val unblock = CountDownLatch(1)
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getOutputStream() = output
        override fun getInputStream(): InputStream {
            readStarted.complete(Unit)
            return if (blockRead) object : InputStream() {
                override fun read(): Int { unblock.await(); return -1 }
            } else ByteArrayInputStream(bytes)
        }
        override fun disconnect() { disconnected.complete(Unit); unblock.countDown() }
        override fun usingProxy() = false
        override fun connect() { }
    }
}
