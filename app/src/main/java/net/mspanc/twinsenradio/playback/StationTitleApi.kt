package net.mspanc.twinsenradio.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.StationRepository
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Now playing" for stations whose stream stopped saying it.
 *
 * Radio Nowy Swiat moved its studio to Minska street (October 2026) and
 * since then every one of its mounts - 256, 128, the backup 128 and the AAC -
 * sends `StreamTitle='Now Playing info goes here'`, which is the hosting
 * platform's (Revma) placeholder: nobody feeds the encoder a title any more.
 * Their web player never read ICY in the first place - it polls
 * `/api/oZeta/stat` every ten seconds - so the title is still on their site and
 * in their app, just not in the stream (measured 27.10.2026). Their mobile app
 * uses `/api/Mobile/...`, but that sits behind a Cloudflare challenge that a
 * plain client doesn't pass; the web endpoint answers anybody.
 *
 * What comes back is turned into an ordinary StreamTitle, so everything
 * downstream - slogans, ads, the catalogue, the stale deadline - treats it
 * exactly like a block from the stream and needs to know nothing about where
 * it came from.
 */
object StationTitleApi {

    class Source(
        val url: String,
        /** The response body as a StreamTitle; null = the answer said nothing usable. */
        val toStreamTitle: (String) -> String?
    )

    /** Null for stations whose stream carries its own titles. */
    fun forStation(stationId: String?): Source? = when (stationId) {
        "rns" -> RNS
        else -> null
    }

    /**
     * The API alone describes the track (see [StreamWitness] for why), and a
     * change waits [net.mspanc.twinsenradio.data.Prefs.apiHoldSeconds] before
     * it goes on.
     *
     * That wait used to be 12 s, worked out on 28.10.2026, the one day the
     * stream had titles and both sources ran side by side: the API named each
     * track 4-13 s (9 s on average) before the stream did, and the stream
     * itself 2-3 s before the song, as RNS's playout names a track when it is
     * cued. But that drive had our playback drifting behind live with every
     * rebuffer; on a fresh connection the stream came first and the API up to
     * 8 s after it. Neither the station's app nor its website waits at all.
     * With the stream mute there is nothing left to measure against but the
     * ear, so it is now the user's setting.
     */
    private val RNS = Source(
        "https://nowyswiat.online/api/oZeta/stat",
        ::rnsStreamTitle
    )

    /**
     * The fields are named the wrong way round: `title` holds the artist and
     * `artist` the track - their own player shows them crossed over, and asks
     * iTunes for "artist title" in that order. Between tracks it says
     * "Radio Nowy Świat" / "Pion i poziom!", which joined back together is
     * exactly the slogan block the stream used to send.
     */
    internal fun rnsStreamTitle(body: String): String? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!o.optBoolean("status", false)) return null
        val data = o.optJSONObject("data") ?: return null
        val who = data.optString("title").trim()
        val what = data.optString("artist").trim()
        return when {
            who.isNotEmpty() && what.isNotEmpty() -> "$who - $what"
            else -> who.ifEmpty { what }.ifEmpty { null }
        }
    }

    /** One question to the station. Null on any failure - the next poll simply tries again. */
    suspend fun fetch(source: Source): String? = withContext(Dispatchers.IO) {
        // The answer comes with Cache-Control max-age of a year, and Cloudflare
        // honours it: a repeated address got a Massive Attack track from days
        // earlier. Their own player busts it with a timestamp, and so do we.
        val url = "${source.url}?tsapi=${System.currentTimeMillis()}"
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("User-Agent", StationRepository.USER_AGENT)
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) null
                else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Their own player asks every 10 s, so the station has already sized for that. */
    const val POLL_MS = 10_000L

    private const val TIMEOUT_MS = 5_000
}
