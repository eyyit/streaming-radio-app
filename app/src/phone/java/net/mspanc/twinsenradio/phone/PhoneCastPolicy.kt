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
            .trimEnd('/')
        val label = variantLabel.lowercase(Locale.ROOT)
        val codec = measuredCodec.orEmpty().uppercase(Locale.ROOT)
        return when {
            containerMimeType in setOf(
                "application/x-mpegURL",
                "application/vnd.apple.mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl"
            ) || path.endsWith(".m3u8") || Regex("\\bhls\\b").containsMatchIn(label) ->
                "application/vnd.apple.mpegurl"
            containerMimeType in setOf("audio/mp4", "video/mp4", "application/mp4") ||
                path.endsWith(".m4a") || path.endsWith(".mp4") ->
                if (sampleMimeType == null ||
                    sampleMimeType in setOf("audio/mp4a-latm", "audio/aac", "audio/aacp")
                ) "audio/mp4" else null
            containerMimeType in setOf("audio/ogg", "application/ogg") ||
                path.endsWith(".ogg") || path.endsWith(".oga") || path.endsWith(".opus") ||
                hasTerminalPathToken(path, "ogg") || hasTerminalPathToken(path, "opus") ->
                when (sampleMimeType) {
                    null, "audio/opus" -> "audio/ogg"
                    "audio/vorbis" -> "audio/ogg; codecs=vorbis"
                    "audio/flac" -> "audio/ogg; codecs=flac"
                    else -> null
                }
            hasTerminalPathToken(path, "flacm") ->
                if (containerMimeType in setOf(null, "audio/ogg", "application/ogg") &&
                    (sampleMimeType == null || sampleMimeType == "audio/flac")
                ) "audio/ogg; codecs=flac" else null
            containerMimeType in setOf(
                "audio/webm",
                "video/webm",
                "audio/x-matroska",
                "video/x-matroska"
            ) || path.endsWith(".webm") ->
                if (sampleMimeType == null || sampleMimeType in setOf("audio/opus", "audio/vorbis")) "audio/webm"
                else null
            containerMimeType in setOf("audio/wav", "audio/x-wav", "audio/vnd.wave") ||
                path.endsWith(".wav") ->
                if (sampleMimeType == null ||
                    sampleMimeType in setOf("audio/raw", "audio/wav", "audio/x-wav")
                ) "audio/wav" else null
            sampleMimeType in setOf("audio/mp4a-latm", "audio/aac", "audio/aacp") -> "audio/aac"
            sampleMimeType in setOf("audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2", "audio/mp3") -> "audio/mpeg"
            sampleMimeType == "audio/opus" && containerMimeType == null -> "audio/ogg"
            sampleMimeType == "audio/vorbis" && containerMimeType == null -> "audio/ogg; codecs=vorbis"
            sampleMimeType == "audio/flac" && containerMimeType != null &&
                containerMimeType !in setOf("audio/flac", "application/flac", "application/x-flac") -> null
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

    private fun hasTerminalPathToken(path: String, token: String): Boolean {
        val esc = Regex.escape(token)
        return Regex("(?:^|[._/-])(?:$esc(?:[._-]?\\d+k?)?|\\d+k?[._-]?$esc)$").containsMatchIn(path)
    }

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
