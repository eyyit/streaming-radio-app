package net.mspanc.twinsenradio.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.metadata.icy.IcyHeaders

/**
 * A wrapper around a data source that can completely strip ICY metadata.
 *
 * Why: in diagnostic mode ExoPlayer normally overwrites the `title`,
 * `station` and `genre` fields with whatever came in the icy-* headers and
 * the StreamTitle block. That would ruin the whole experiment - on the AID
 * you'd see the station's name instead of the field label. So we remove the
 * `Icy-MetaData` request header (the server then stops sending the block in
 * the stream) and filter out the `icy-*` response headers (so ExoPlayer
 * won't build an IcyHeaders object from them).
 */
@UnstableApi
class IcyFilteringDataSource(
    private val upstream: DataSource,
    private val stripIcy: Boolean,
    private val onIcyBitrate: (Int) -> Unit = {},
    private val onIcyTiming: (Int, Int) -> Unit = { _, _ -> }
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (!stripIcy) return upstream.open(dataSpec).also { reportBitrate() }
        val headers = HashMap(dataSpec.httpRequestHeaders)
        headers.keys.removeAll { it.equals(ICY_REQUEST_HEADER, ignoreCase = true) }
        return upstream.open(dataSpec.buildUpon().setHttpRequestHeaders(headers).build())
            .also { reportBitrate() }
    }

    /**
     * The `icy-br` header, read here because nothing downstream ever saw it:
     * the IcyHeaders entry the service listened for never arrived (30.10.2026,
     * no "naglowki ICY" in the log for any station), so a single-stream
     * station like RMF FM - which sends `icy-br: 128` - showed no bitrate.
     * Called on the loader's thread.
     */
    private fun reportBitrate() {
        // Report every connection, including absent headers, to avoid carrying
        // a previous connection's advertised timing into a new response.
        val icy = if (stripIcy) null else IcyHeaders.parse(upstream.responseHeaders)
        onIcyTiming(icy?.metadataInterval ?: -1, icy?.bitrate ?: -1)
        val br = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals(ICY_BITRATE_HEADER, ignoreCase = true) }
            ?.value?.firstOrNull()?.substringBefore(',')?.trim()?.toIntOrNull()
        if (br != null && br > 0) onIcyBitrate(br)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        val headers = upstream.responseHeaders
        if (!stripIcy) return headers
        return headers.filterKeys { !it.lowercase().startsWith("icy-") }
    }

    override fun close() {
        upstream.close()
    }

    @UnstableApi
    class Factory(
        private val delegate: DataSource.Factory,
        private val onIcyBitrate: (Int) -> Unit = {},
        private val onIcyTiming: (Int, Int) -> Unit = { _, _ -> },
        private val stripIcy: () -> Boolean
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            IcyFilteringDataSource(delegate.createDataSource(), stripIcy(), onIcyBitrate, onIcyTiming)
    }

    private companion object {
        const val ICY_REQUEST_HEADER = "Icy-MetaData"
        const val ICY_BITRATE_HEADER = "icy-br"
    }
}
