package net.mspanc.twinsenradio.phone

import net.mspanc.twinsenradio.data.CatalogLimits
import net.mspanc.twinsenradio.data.PlaylistLibrary
import net.mspanc.twinsenradio.data.Station
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Bounded, strict reader for a user-selected local M3U file. */
internal object PhoneM3uFile {
    fun read(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        input.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(out.size().toLong() + count <= CatalogLimits.MAX_BYTES) {
                    "M3U file exceeds 1 MiB. Choose a smaller file."
                }
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): Parsed {
        require(bytes.size <= CatalogLimits.MAX_BYTES) {
            "M3U file exceeds 1 MiB. Choose a smaller file."
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val body = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("M3U file must use valid UTF-8.")
        }
        val stations = PlaylistLibrary.parseCachedPlaylist(body, PlaylistLibrary.LOCAL_SNAPSHOT_URL)
        return Parsed(body, stations)
    }

    data class Parsed(val body: String, val stations: List<Station>)
}
