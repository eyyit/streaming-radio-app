package net.mspanc.twinsenradio.net

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SafeHttpTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
    }

    @Test fun validatesUrlsAndRejectsNonHttpsByDefault() {
        assertEquals("https", SafeHttp.checkedUrl("https://images.example/logo.jpg", false).protocol)
        listOf("http://localhost:1234/a", "file:///private", "content://private/a",
            "https://user:secret@example.com/a", "https://example.com/a#fragment", "https:///missing-host",
            "https://example.com:0/a", "https://example.com:65536/a", "https://example.com/" + "a".repeat(8192))
            .forEach { rejects { SafeHttp.checkedUrl(it, false) } }
    }

    @Test fun validatesRedirectsAndNeverDowngradesHttps() {
        val current = SafeHttp.checkedUrl("https://images.example/path/a", false)
        assertEquals("https://images.example/next", SafeHttp.redirectTarget(current, "../next", false).toString())
        assertEquals("https://cdn.example/a", SafeHttp.redirectTarget(current, "https://cdn.example/a", false).toString())
        listOf("http://images.example/a", "file:///private", "content://private/a",
            "https://user:pass@images.example/a", "https://images.example/a#fragment")
            .forEach { rejects { SafeHttp.redirectTarget(current, it, true) } }
    }

    @Test fun enforcesLengthHeaderAndStreamingByteLimits() {
        serve(listOf("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n")) { url ->
            rejects { SafeHttp.getBytes(url, 3, "test", allowHttp = true) }
        }
        serve(listOf("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nfour")) { url ->
            rejects { SafeHttp.getBytes(url, 3, "test", allowHttp = true) }
        }
        serve(listOf("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nfour")) { url ->
            assertArrayEquals("four".toByteArray(), SafeHttp.getBytes(url, 4, "test", allowHttp = true))
        }
    }

    @Test fun followsRelativeRedirectAndBoundsRedirectLoops() {
        val redirect = "HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        serve(listOf(redirect, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")) { url ->
            assertEquals("ok", SafeHttp.getText(url, 10, "test", allowHttp = true))
        }
        serve(List(6) { redirect }) { url -> rejects { SafeHttp.getText(url, 10, "test", allowHttp = true) } }
    }

    @Test fun handlesHeadFailureAndUtf8Bodies() {
        serve(listOf("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")) { url ->
            assertFalse(SafeHttp.headOk(url, "test", allowHttp = true))
        }
        serve(listOf("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\né")) { url ->
            assertEquals("é", SafeHttp.getText(url, 2, "test", allowHttp = true))
        }
    }

    private fun serve(responses: List<String>, block: (String) -> Unit) {
        val error = AtomicReference<Throwable?>()
        ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val worker = thread(isDaemon = true) {
                try {
                    responses.forEach { response ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            while (true) { if (reader.readLine().isNullOrEmpty()) break }
                            socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                        }
                    }
                } catch (failure: Throwable) { error.set(failure) }
            }
            try { block("http://127.0.0.1:${server.localPort}/art") }
            finally { worker.join(5500) }
            error.get()?.let { throw AssertionError("Fixture server failed", it) }
            assertFalse("Fixture server still running", worker.isAlive)
        }
    }
}
