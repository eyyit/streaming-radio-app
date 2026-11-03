package net.mspanc.twinsenradio.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Admission limits, never truncation: validate before replacing any last-good data. */
object CatalogLimits {
    const val MAX_BYTES = 1_048_576
    const val MAX_SOURCES = 16
    const val MAX_STATIONS_PER_SOURCE = 500
    // Counts source rows, including shared streams, so deduplication cannot hide work.
    const val MAX_CATALOG_STATIONS = 2_000
    const val MAX_URL = 4_096
    const val MAX_NAME = 512
    const val MAX_GROUP = 256
    const val MAX_ID = 512
    const val MAX_LINE = 16_384

    fun field(value: String?, maximum: Int, label: String) {
        require(value == null || value.length <= maximum) { "$label exceeds $maximum characters; keeping saved playlist" }
    }

    fun body(body: String) {
        require(body.length <= MAX_BYTES && body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Playlist exceeds $MAX_BYTES bytes; keeping saved playlist"
        }
    }

    fun sources(sources: List<PlaylistSource>) {
        require(sources.size <= MAX_SOURCES) { "At most $MAX_SOURCES playlists are supported" }
        sources.forEach {
            field(it.id, MAX_ID, "Playlist ID")
            field(it.url, MAX_URL, "Playlist URL")
            field(it.name, MAX_NAME, "Playlist name")
            val scheme = runCatching { java.net.URI(it.url).scheme?.lowercase() }.getOrNull()
            require(scheme !in setOf("local", "file", "content") || it.isLocal) {
                "Unsupported local playlist source"
            }
        }
    }

    fun catalog(stations: Collection<List<Station>>) {
        require(stations.sumOf { it.size.toLong() } <= MAX_CATALOG_STATIONS) {
            "Combined playlists exceed $MAX_CATALOG_STATIONS station entries; keeping saved playlists"
        }
    }

    /** No mutation if validation fails; used before disk writes and publication. */
    fun replacement(current: Map<String, List<Station>>, url: String, stations: List<Station>): Map<String, List<Station>> {
        val next = current + (url to stations)
        catalog(next.values)
        return next
    }

    fun commitReplacement(current: Map<String, List<Station>>, url: String,
                          stations: List<Station>, persist: () -> Unit): Map<String, List<Station>> {
        val next = replacement(current, url, stations)
        persist()
        return next
    }

    /** Cached input gets exactly the same byte cap as downloaded input. */
    fun readBody(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES) { "Saved playlist exceeds $MAX_BYTES bytes" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
