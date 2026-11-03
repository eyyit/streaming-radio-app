package net.mspanc.twinsenradio.playback

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/**
 * A short description of what's actually coming through the stream - codec,
 * bitrate, sample rate, and channel count.
 *
 * The data is taken from the format the decoder settled on, not from the
 * station's description: what the station declares in the icy-br header can
 * be stale, and for AAC+ it may describe only the base layer. The bitrate
 * from the header is used purely as a fallback, when the decoder doesn't
 * report one (typical for AAC in an ADTS container).
 *
 * We only show this on the phone - in Android Auto we have no field of our
 * own to write it into, and appending it to the title would corrupt the
 * metadata.
 */
data class StreamQuality(
    val codec: String,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val channels: Int
) {
    fun compactLabel(): String = listOf(codec, bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" }.orEmpty())
        .filter { it.isNotBlank() }.joinToString(" · ")

    fun technicalLabel(): String = listOf(
        compactLabel(),
        sampleRateHz.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f kHz", it / 1000.0) }.orEmpty(),
        when (channels) { 1 -> "mono"; 2 -> "stereo"; in 3..Int.MAX_VALUE -> "$channels channels"; else -> "" }
    ).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * What the playback screen's quality chip says, e.g. "MP3 · 128 kbit ·
     * stereo" - for every station, not only the ones with a choice of streams:
     * without it, a single-stream station never said what it was sending.
     * Without the sample rate - it doesn't say much to a listener.
     *
     * @param kbps the bitrate to show - the chosen variant's declared one where
     *   the catalogue has it, else what the stream itself reports; 0 leaves it out.
     */
    fun chipLabel(kbps: Int = bitrateKbps): String = buildList {
        if (codec.isNotBlank()) add(codec)
        if (kbps > 0) add("$kbps kbit")
        when (channels) {
            1 -> add("mono")
            2 -> add("stereo")
            in 3..Int.MAX_VALUE -> add("$channels kan.")
        }
    }.joinToString(" · ")

    /** The same without the bitrate, for the trace. */
    fun label(): String = chipLabel(kbps = 0)

    private fun khz(hz: Int): String {
        val tenths = (hz + 50) / 100
        return if (tenths % 10 == 0) "${tenths / 10} kHz" else "${tenths / 10},${tenths % 10} kHz"
    }

    @UnstableApi
    companion object {
        /**
         * @param icyBitrateKbps bitrate from the icy-br header; used only
         *   when the format itself doesn't carry one.
         */
        fun of(format: Format, icyBitrateKbps: Int): StreamQuality {
            val bitrate = when {
                format.bitrate > 0 -> (format.bitrate + 500) / 1000
                format.averageBitrate > 0 -> (format.averageBitrate + 500) / 1000
                else -> icyBitrateKbps
            }
            val sbr = looksLikeSbr(format)
            val rate = format.sampleRate.takeIf { it > 0 } ?: 0
            return StreamQuality(
                codec = if (sbr) "AAC+" else codecName(format),
                bitrateKbps = bitrate,
                // With SBR the decoder reports twice what the header declares
                sampleRateHz = if (sbr) rate * 2 else rate,
                channels = format.channelCount.takeIf { it > 0 } ?: 0
            )
        }

        /**
         * Whether this is HE-AAC signaled implicitly.
         *
         * With implicit SBR signaling, the ADTS header lies twice over: it
         * reports the AAC-LC profile and **half** the target sample rate,
         * because the decoder only adds the upper band afterward. Radio Nowy
         * Swiat looks like this then:
         *
         *     audio/mp4a-latm codecs=mp4a.40.2 sr=22050 ch=2
         *
         * and without this correction we'd show "AAC 22.1 kHz", a value that
         * sounds like much worse quality than what's actually being heard.
         *
         * We recognize it by the combination: LC profile, stereo, and a
         * sample rate of around 24 kHz or less. Genuine AAC-LC 22 kHz stereo
         * practically never occurs in a music internet stream - a bitrate
         * like that goes through HE-AAC exclusively nowadays.
         */
        private fun looksLikeSbr(format: Format): Boolean {
            val isAac = format.sampleMimeType in setOf("audio/mp4a-latm", "audio/aac", "audio/aacp")
            if (!isAac) return false
            val profile = format.codecs?.substringAfterLast('.')?.toIntOrNull()
            if (profile != null && profile != 2) return false
            return format.sampleRate in 1..24_000 && format.channelCount >= 2
        }

        /**
         * The codec name in a form that means something to a human. The MIME
         * type alone isn't enough: AAC-LC, HE-AAC and HE-AACv2 share the same
         * type and differ only in the profile recorded in the codecs field
         * (mp4a.40.<profile>).
         */
        private fun codecName(format: Format): String {
            val profile = format.codecs?.substringAfterLast('.')?.toIntOrNull()
            return when (format.sampleMimeType) {
                "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2" -> "MP3"
                "audio/mp4a-latm", "audio/aac", "audio/aacp" -> when (profile) {
                    5 -> "AAC+"
                    29 -> "AAC++"
                    2 -> "AAC"
                    else -> "AAC"
                }
                "audio/opus" -> "Opus"
                "audio/vorbis" -> "Vorbis"
                "audio/flac" -> "FLAC"
                "audio/ac3" -> "AC-3"
                null -> ""
                else -> format.sampleMimeType!!.substringAfter('/').uppercase()
            }
        }
    }
}
