package net.mspanc.twinsenradio.phone

import java.net.URI
import java.util.Locale

/** Small, Android-free decisions shared by Cast routing and JVM regressions. */
internal object PhoneCastPolicy {
    fun contentType(
        uri: String,
        variantLabel: String = "",
        measuredCodec: String? = null
    ): String? {
        val path = runCatching { URI(uri).path.orEmpty() }.getOrDefault("")
            .lowercase(Locale.ROOT)
        val label = variantLabel.lowercase(Locale.ROOT)
        val codec = measuredCodec.orEmpty().uppercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") || Regex("\\bhls\\b").containsMatchIn(label) ->
                "application/vnd.apple.mpegurl"
            hasTerminalPathToken(path, "aacp") || "aacp" in label || codec.startsWith("AAC+") ->
                "audio/aac"
            hasTerminalPathToken(path, "aac") || Regex("\\baac\\b").containsMatchIn(label) ||
                codec.startsWith("AAC") -> "audio/aac"
            hasTerminalPathToken(path, "mp3") || Regex("\\bmp3\\b").containsMatchIn(label) ||
                codec == "MP3" -> "audio/mpeg"
            else -> null
        }
    }

    private fun hasTerminalPathToken(path: String, token: String): Boolean =
        Regex("(?:^|[._/-])${Regex.escape(token)}$").containsMatchIn(path)

    fun shouldPauseLocalAfterLoad(
        loadSucceeded: Boolean,
        localUri: String?,
        requestedUri: String
    ): Boolean = loadSucceeded && localUri == requestedUri

    fun shouldLoadCurrentStream(
        userSelectedReceiver: Boolean,
        ownsRemoteMedia: Boolean,
        remoteUri: String?,
        requestedUri: String?,
        remoteStationId: String? = null,
        requestedStationId: String? = null
    ): Boolean = requestedUri != null &&
        (userSelectedReceiver || ownsRemoteMedia &&
            (remoteUri != requestedUri || remoteStationId != requestedStationId))

    fun ownsRemoteMedia(owner: String?): Boolean = owner == OWNER_MARKER

    const val OWNER_MARKER = "streaming-radio-phone"
}
