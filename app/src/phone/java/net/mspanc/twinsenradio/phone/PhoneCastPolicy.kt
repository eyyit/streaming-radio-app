package net.mspanc.twinsenradio.phone

import java.net.URI
import java.util.Locale

/** Small, Android-free decisions shared by Cast routing and JVM regressions. */
internal object PhoneCastPolicy {
    fun contentType(
        uri: String,
        variantLabel: String = "",
        measuredCodec: String? = null,
        sampleMimeType: String? = null,
        containerMimeType: String? = null
    ): String? {
        val path = runCatching { URI(uri).path.orEmpty() }.getOrDefault("")
            .lowercase(Locale.ROOT)
        val label = variantLabel.lowercase(Locale.ROOT)
        val codec = measuredCodec.orEmpty().uppercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") || Regex("\\bhls\\b").containsMatchIn(label) ->
                "application/vnd.apple.mpegurl"
            containerMimeType == "audio/mp4" || containerMimeType == "video/mp4" ||
                path.endsWith(".m4a") || path.endsWith(".mp4") ->
                if (sampleMimeType == null || sampleMimeType == "audio/mp4a-latm") "audio/mp4" else null
            containerMimeType in setOf("audio/ogg", "application/ogg") ||
                path.endsWith(".ogg") || path.endsWith(".oga") || path.endsWith(".opus") ->
                if (sampleMimeType == null || sampleMimeType == "audio/opus") "audio/ogg"
                else if (sampleMimeType == "audio/vorbis") "audio/ogg; codecs=vorbis" else null
            containerMimeType in setOf("audio/webm", "video/webm") || path.endsWith(".webm") ->
                if (sampleMimeType == null || sampleMimeType in setOf("audio/opus", "audio/vorbis")) "audio/webm"
                else null
            containerMimeType == "audio/wav" || path.endsWith(".wav") ->
                if (sampleMimeType == null || sampleMimeType == "audio/raw") "audio/wav" else null
            sampleMimeType in setOf("audio/mp4a-latm", "audio/aac", "audio/aacp") -> "audio/aac"
            sampleMimeType == "audio/mpeg" -> "audio/mpeg"
            sampleMimeType == "audio/flac" && containerMimeType != null &&
                containerMimeType != "audio/flac" -> null
            sampleMimeType == "audio/flac" || hasTerminalPathToken(path, "flac") ||
                Regex("\\bflac\\b").containsMatchIn(label) || codec == "FLAC" -> "audio/flac"
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
