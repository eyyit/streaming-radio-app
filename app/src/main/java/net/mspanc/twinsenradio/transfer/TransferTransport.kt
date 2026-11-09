package net.mspanc.twinsenradio.transfer

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface TransferTransport {
    suspend fun request(method: String, url: String, body: String?, contentType: String): String
}

/** URLs originate solely from TransferConfig and fixed Google authentication endpoints. */
class UrlConnectionTransferTransport internal constructor(
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) : TransferTransport {
    override suspend fun request(method: String, url: String, body: String?, contentType: String): String =
        withTimeout(15_000L) {
            suspendCancellableCoroutine { continuation ->
                val connection = openConnection(url)
                connection.apply {
                    // The Java URLConnection implementation does not accept PATCH.
                    requestMethod = if (method == "PATCH") "POST" else method
                    if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", contentType)
                }
                continuation.invokeOnCancellation { connection.disconnect() }
                Thread({
                    try {
                        if (!continuation.isActive) return@Thread
                        if (body != null) {
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            connection.doOutput = true
                            connection.setFixedLengthStreamingMode(bytes.size)
                            connection.outputStream.use { it.write(bytes) }
                        }
                        if (connection.responseCode !in 200..299) {
                            throw TransferException("Transfer request rejected")
                        }
                        // JSON escapes can expand an allowed one-MiB UTF-8 file sixfold.
                        val maximum = 8 * 1024 * 1024
                        if (connection.contentLengthLong > maximum) {
                            throw TransferException("Transfer response too large")
                        }
                        val output = ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (!continuation.isActive) return@Thread
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > maximum) {
                                    throw TransferException("Transfer response too large")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            TransferException("Transfer request failed"))
                    } finally { connection.disconnect() }
                }, "playlist-transfer").apply { isDaemon = true }.start()
            }
        }
}
