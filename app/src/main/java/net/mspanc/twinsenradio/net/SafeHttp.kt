package net.mspanc.twinsenradio.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Bounded HTTPS requests. HTTP requires an explicit caller opt-in for test fixtures. */
object SafeHttp {
    private const val MAX_REDIRECTS = 5
    private val redirects = setOf(301, 302, 303, 307, 308)

    @Throws(IOException::class)
    fun getBytes(url: String, maxBytes: Int, userAgent: String,
        timeoutMs: Int = 8000, allowHttp: Boolean = false): ByteArray {
        if (maxBytes <= 0 || timeoutMs <= 0) throw IOException("Invalid request bounds")
        return request(url, userAgent, timeoutMs, allowHttp, "GET") { connection, deadline ->
            if (connection.contentLengthLong > maxBytes) throw IOException("Response too large")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
                val buffer = ByteArray(8192)
                while (true) {
                    if (System.nanoTime() >= deadline) throw IOException("Response deadline exceeded")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > maxBytes - output.size()) throw IOException("Response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    @Throws(IOException::class)
    fun getText(url: String, maxBytes: Int, userAgent: String,
        timeoutMs: Int = 8000, allowHttp: Boolean = false): String =
        getBytes(url, maxBytes, userAgent, timeoutMs, allowHttp).toString(Charsets.UTF_8)

    fun headOk(url: String, userAgent: String, timeoutMs: Int = 8000,
        allowHttp: Boolean = false): Boolean = try {
        request(url, userAgent, timeoutMs, allowHttp, "HEAD") { _, _ -> true }
    } catch (_: IOException) { false }

    /** Validates every redirect target before opening a connection. */
    internal fun checkedUrl(raw: String, allowHttp: Boolean): URL {
        if (raw.length > 8192) throw IOException("URL too long")
        val uri = try { URI(raw) } catch (error: Exception) { throw IOException("Invalid URL", error) }
        if (uri.scheme != "https" && !(allowHttp && uri.scheme == "http")) throw IOException("HTTPS required")
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.port !in -1..65535 || uri.port == 0) throw IOException("Invalid URL authority")
        return try { uri.toURL() } catch (error: Exception) { throw IOException("Invalid URL", error) }
    }

    internal fun redirectTarget(current: URL, location: String, allowHttp: Boolean): URL {
        val target = try { current.toURI().resolve(location).toString() }
            catch (error: Exception) { throw IOException("Invalid redirect", error) }
        val checked = checkedUrl(target, allowHttp)
        if (current.protocol == "https" && checked.protocol != "https") throw IOException("HTTPS downgrade denied")
        return checked
    }

    private fun <T> request(raw: String, userAgent: String, timeoutMs: Int, allowHttp: Boolean,
        method: String, consume: (HttpURLConnection, Long) -> T): T {
        if (timeoutMs <= 0) throw IOException("Invalid timeout")
        val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000L
        var url = checkedUrl(raw, allowHttp)
        var followed = 0
        while (true) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L)
                .coerceAtMost(timeoutMs.toLong()).toInt()
            if (remaining <= 0) throw IOException("Request deadline exceeded")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = remaining
                readTimeout = remaining
                requestMethod = method
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "identity")
                useCaches = false
            }
            try {
                val status = connection.responseCode
                if (status in redirects) {
                    if (followed++ >= MAX_REDIRECTS) throw IOException("Too many redirects")
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect target")
                    url = redirectTarget(url, location, allowHttp)
                } else {
                    if (status !in 200..299) throw IOException("HTTP $status")
                    return consume(connection, deadline)
                }
            } finally { connection.disconnect() }
        }
    }
}
