// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.playback

import java.text.Normalizer

/**
 * Sets the stream against the title API, for a station where the API alone
 * decides what is on screen. Nothing here changes what the driver sees - it
 * only says whether the two agree and how far apart they said it, so the trace
 * can tell when (if ever) the stream is worth listening to again.
 *
 * **Why the stream has no say.** Until 30.10.2026 a `TitleArbiter` let the
 * stream take over once it named a real track. RNS's stream broke three ways
 * in four days, each defeating the rule written for the one before: Revma's
 * placeholder (27.10), a fixed "Radio Nowy Swiat - Pion i poziom!" on connect
 * (27.10, 18:12), and on 30.10 a frozen real track - every mount sends
 * "Trent Reznor and Atticus Ross - Normal Life" once on connect and then
 * nothing. That one *looks* like proof of a working stream, so the arbiter
 * trusted it, covered the API's correct track with it for 3 minutes, and then
 * let each next API change wait 60 s for the stream to follow - twice that
 * morning a title came 76-78 s late. The station's own app plays the same
 * mounts and shows what its API says; so do we now.
 *
 * All times are on one monotonic clock, passed in, so this is pure logic.
 */
class StreamWitness {

    enum class Source { ICY, HLS, API }

    /**
     * @param agrees whether this matches what the other source last said;
     *   null when the other one has said nothing on this connection yet.
     * @param apartMs how long after the other source this one said it - only
     *   when they agree.
     */
    data class Result(val agrees: Boolean?, val apartMs: Long?)

    private class Said(val key: String, val atMs: Long)

    private var lastIcy: Said? = null
    private var lastApi: Said? = null

    /** A block from the stream, already parsed; null = it named nothing. */
    fun fromIcy(now: NowPlaying?, atMs: Long): Result {
        if (now == null) return Result(null, null)
        val said = Said(key(now), atMs)
        lastIcy = said
        return compare(said, lastApi)
    }

    /** A changed answer from the title API, already parsed. */
    fun fromApi(now: NowPlaying, atMs: Long): Result {
        val said = Said(key(now), atMs)
        lastApi = said
        return compare(said, lastIcy)
    }

    private fun compare(said: Said, other: Said?): Result {
        val agrees = other?.let { it.key == said.key }
        return Result(agrees, if (agrees == true) said.atMs - other.atMs else null)
    }

    /** A new station or a fresh connection - nothing either source said still holds. */
    fun reset() {
        lastIcy = null
        lastApi = null
    }

    companion object {
        /**
         * What has to match for two sources to mean the same thing: artist and
         * title, without case, accents or punctuation - the API and the stream
         * need not agree on "Swiat" against "Świat", or on a stray dot.
         */
        fun key(now: NowPlaying): String =
            fold(now.artist) + "|" + fold(now.songTitle) + "|" + now.isAd

        private fun fold(s: String?): String =
            Normalizer.normalize(s.orEmpty().lowercase().replace("ł", "l"), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]"), "")
    }
}
