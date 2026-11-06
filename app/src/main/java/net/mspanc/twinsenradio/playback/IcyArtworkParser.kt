package net.mspanc.twinsenradio.playback

import java.net.URI
import java.util.Locale

/** Parses the bounded ICY metadata block used for track titles and artwork hints. */
internal object IcyArtworkParser {
    private const val MAX_METADATA_BYTES = 4080
    private const val MAX_ARTWORK_URL_BYTES = 2048

    data class Update(
        val title: String?,
        val artworkUrl: String?,
        val hasTitle: Boolean,
        val hasArtwork: Boolean
    )

    fun parse(raw: String): Update {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES) {
            return Update(null, null, false, false)
        }
        var title: String? = null
        var artworkUrl: String? = null
        var hasTitle = false
        var hasArtwork = false
        var cursor = 0
        while (cursor < raw.length) {
            while (cursor < raw.length && (raw[cursor] == ';' || raw[cursor].isWhitespace())) {
                cursor++
            }
            val nameStart = cursor
            while (cursor < raw.length && (raw[cursor].isLetterOrDigit() || raw[cursor] == '_')) {
                cursor++
            }
            val name = raw.substring(nameStart, cursor)
            while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
            if (name.isEmpty() || cursor >= raw.length || raw[cursor] != '=') {
                cursor = raw.indexOf(';', cursor).let { if (it < 0) raw.length else it + 1 }
                continue
            }
            cursor++
            while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
            val recognizedTitle = name.equals("StreamTitle", ignoreCase = true)
            val recognizedArtwork = name.equals("StreamArtwork", ignoreCase = true)
            if (recognizedTitle) hasTitle = true
            if (recognizedArtwork) hasArtwork = true
            if (!recognizedTitle && !recognizedArtwork) {
                cursor = skipValue(raw, cursor)
                continue
            }
            if (cursor >= raw.length || (raw[cursor] != '\'' && raw[cursor] != '"')) {
                cursor = skipValue(raw, cursor)
                continue
            }
            val quote = raw[cursor++]
            val valueStart = cursor
            var closing = -1
            while (cursor < raw.length) {
                if (raw[cursor] == quote) {
                    var afterQuote = cursor + 1
                    while (afterQuote < raw.length && raw[afterQuote].isWhitespace()) afterQuote++
                    if (afterQuote == raw.length || raw[afterQuote] == ';') {
                        closing = cursor
                        cursor = afterQuote
                        break
                    }
                }
                cursor++
            }
            if (closing < 0) {
                cursor = raw.length
                continue
            }
            val value = raw.substring(valueStart, closing).trim()
            if (recognizedTitle) title = value
            if (recognizedArtwork) artworkUrl = if (value.isEmpty()) "" else validArtworkUrl(value)
        }
        return Update(title, artworkUrl, hasTitle, hasArtwork)
    }

    private fun skipValue(raw: String, start: Int): Int {
        if (start >= raw.length) return raw.length
        val quote = raw[start]
        if (quote != '\'' && quote != '"') {
            val delimiter = raw.indexOf(';', start)
            return if (delimiter < 0) raw.length else delimiter + 1
        }
        var cursor = start + 1
        while (cursor < raw.length) {
            if (raw[cursor] == quote) {
                var afterQuote = cursor + 1
                while (afterQuote < raw.length && raw[afterQuote].isWhitespace()) afterQuote++
                if (afterQuote == raw.length || raw[afterQuote] == ';') {
                    return afterQuote
                }
            }
            cursor++
        }
        return raw.length
    }

    internal fun validArtworkUrl(value: String): String? {
        if (value.toByteArray(Charsets.UTF_8).size > MAX_ARTWORK_URL_BYTES) return null
        return try {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) value else null
        } catch (_: Exception) {
            null
        }
    }
}
