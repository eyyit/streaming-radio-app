package net.mspanc.twinsenradio.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.mspanc.twinsenradio.data.StationRepository
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks up cover art for the track currently playing.
 *
 * How we know this is the right approach: the ICY stream **carries no artwork
 * at all** - it only ever carries text (StreamTitle and optionally StreamUrl).
 * Stations bolt cover art on client-side by querying an external catalogue.
 * Radio Nowy Swiat's web player does exactly what's below - its
 * `neoplayer-min.js` contains:
 *
 *     $.ajax({ url: `https://itunes.apple.com/search?term=${title} ${artist}&media=music` })
 *
 * The iTunes catalogue is free, needs no key or registration, and returns the
 * artwork address in the artworkUrl100 field, which can be swapped for a
 * higher resolution.
 *
 * Fallbacks in case hits are poor: MusicBrainz + Cover Art Archive (free,
 * slower), Deezer API (free), Last.fm and Spotify (require a key).
 */
object CoverArtLookup {
    /** Diagnostic counter used to verify the opt-out prevents even cached catalog lookups. */
    val lookupCalls = java.util.concurrent.atomic.AtomicInteger()

    private const val TAG = "CoverArt"
    private const val ENDPOINT = "https://itunes.apple.com/search"
    private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2/recording"
    private const val COVER_ART = "https://coverartarchive.org"
    private const val MUSICBRAINZ_AGENT = "StreamingRadio/0.3 (impostor.labs@gmail.com)"

    /**
     * What we managed to find out about the track. Besides artwork, the
     * catalogue also knows the release name and year - which stations
     * usually don't provide.
     */
    data class TrackInfo(
        val artworkUrl: String?,
        val album: String?,
        val year: Int?,
        val isSingle: Boolean,
        /** Track duration from the catalogue; 0 when unknown. */
        val durationMs: Long = 0,
        /**
         * Title and artist **according to the catalogue**. Used to fix up the
         * text when a station shouts in all caps - see [TextCase.tidy].
         */
        val trackName: String? = null,
        val artistName: String? = null
    ) {
        /**
         * e.g. "Księga [2024]" or "singiel [2024]" (the literal produced below) -
         * or just "Księga" when [includeYear] is off.
         */
        fun albumLabel(includeYear: Boolean = true): String? {
            val name = when {
                isSingle -> "singiel"
                // AlbumNames goes first: the pressing note it removes is exactly
                // the part that made these lines too long for the dashboard, and
                // running TextCase over it afterwards means we never spend a
                // capitalisation decision on text nobody is going to see.
                !album.isNullOrBlank() -> TextCase.tidy(AlbumNames.shorten(album))
                else -> return null
            }
            return if (includeYear && year != null) "$name [$year]" else name
        }

        /**
         * Whether the tail provided by the station is the release name rather
         * than another artist.
         *
         * You can't tell this from the separator alone: RMF writes "Wiktoria
         * Kida / Księga" (artist and album), but it just as happily sends
         * "Shimza / AR/CO / Kasango" (three artists) or "Nico / Vinz" (a band
         * name that contains a slash). Only comparing against what the
         * catalogue knows about the track settles it.
         */
        fun tailIsAlbum(tail: String): Boolean {
            val a = album ?: return false
            return normalize(tail) == normalize(a)
        }

        /**
         * Whether the station gave the artist and title in reverse order.
         *
         * Not everyone sticks to the "Artist - Title" scheme. Jacaranda FM
         * broadcasts it backwards, as seen by sniffing the stream:
         *
         *   StreamTitle='THINKING ABOUT YOU - GOODLUCK'
         *   StreamTitle='WHAT'S LOVE GOT TO DO WITH IT - KYGO [+] TINA TURNER'
         *
         * You can't settle this from the text alone - "Nico / Vinz" could be
         * a band name or two words of a title. Only the catalogue can decide:
         * if what we took for the artist turns out to be the title there, and
         * what we took for the title turns out to be the artist, the fields
         * are swapped.
         *
         * This means stations don't need to be flagged by hand in
         * stations.json, nor guessed at - the fix follows from the data and
         * works for any station that broadcasts this way.
         *
         * Two questions, because one of them is not always answerable. On
         * `GIVE ME EVERYTHING - PITBULL [+] NE-YO [+] AFROJACK [+] NAYER`
         * (Jacaranda, 2026-09-08) the catalogue calls the track "Give Me
         * Everything (feat. Ne-Yo, Afrojack & Nayer)" - the station's half
         * against the catalogue's whole, so an exact comparison said no and
         * the car showed Pitbull as the title for the length of the song. The
         * suffix in brackets is the catalogue's own addition, so we compare
         * against the title with and without it; and independently we ask
         * whether the catalogue's artist is one of the names in what we took
         * for the title, which settles the same question from the other end.
         */
        fun looksSwapped(artist: String?, title: String?): Boolean {
            val a = normalize(artist.orEmpty())
            val t = normalize(title.orEmpty())
            if (a.isEmpty() || t.isEmpty()) return false

            // What we took for the artist is the track title in the catalogue,
            // and what we took for the title is not.
            val whole = normalize(trackName.orEmpty())
            val core = normalize(titleCore(trackName.orEmpty()))
            if (whole.isNotEmpty()) {
                val artistIsTitle = a == whole || a == core
                val titleIsTitle = t == whole || t == core
                if (artistIsTitle && !titleIsTitle) return true
            }

            // The catalogue's artist stands among the names in what we took for
            // the title, and nowhere in what we took for the artist. Compared
            // name by name rather than by substring: "Sia" occurs inside
            // "Anastasia", and that would be a swap declared on a coincidence.
            val catalogueArtist = normalize(artistName.orEmpty())
            if (catalogueArtist.isNotEmpty()) {
                val amongTitle = names(title.orEmpty()).any { it == catalogueArtist }
                val amongArtist = names(artist.orEmpty()).any { it == catalogueArtist }
                if (amongTitle && !amongArtist) return true
            }

            return false
        }

        /**
         * The track title without what the catalogue writes after it -
         * "(feat. ...)", "[Radio Edit]", "- Remastered 2011". A station sends
         * the bare title.
         */
        private fun titleCore(s: String): String =
            s.substringBefore(" (").substringBefore(" [").substringBefore(" - ")

        /**
         * The names in a field, as stations separate them: `[+]` (Jacaranda),
         * a slash (RMF), a comma, an ampersand, "feat"/"ft".
         */
        private fun names(s: String): List<String> =
            s.split(NAME_SEPARATOR)
                .map { normalize(it) }
                .filter { it.isNotEmpty() }

        private fun normalize(s: String) = s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    private val NAME_SEPARATOR =
        Regex("""\s*(\[\+\]|\+|/|,|&|\bfeat\.?\b|\bft\.?\b|\bvs\.?\b)\s*""", RegexOption.IGNORE_CASE)

    /** Small, bounded cache - in the car we cycle through just a few stations anyway. */
    private const val CACHE_LIMIT = 64
    private val cache = object : LinkedHashMap<String, TrackInfo?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackInfo?>) =
            size > CACHE_LIMIT
    }

    /**
     * @return track data, or null if nothing was found. Null is cached too,
     *   so we don't keep re-querying for tracks that aren't in the catalogue
     *   (and there are plenty of those among Polish stations).
     */
    suspend fun find(artist: String?, title: String?): TrackInfo? = withContext(ArtworkWorkScheduler.trackDispatcher) {
        lookupCalls.incrementAndGet()
        val a = artist?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return@withContext null

        val key = "$a|$t".lowercase()
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }

        var result = (if(net.mspanc.twinsenradio.BuildConfig.ENABLE_ITUNES_ARTWORK) runCatching { query("$a $t".trim()) }
            .onFailure { Log.w(TAG, "iTunes nie odpowiedzial: ${it.message}") }
            .getOrNull()
            ?.let { ourTrackOrNull(a, t, it, "iTunes") } else null)

        // iTunes has poor coverage of older Polish repertoire - "Czesław
        // Niemen - Lipowa łyżka" isn't there at all, while MusicBrainz knows
        // both the track and the release. We only query it when Apple
        // doesn't know the track.
        if (result == null && a.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            result = runCatching { queryMusicBrainz(a, t) }
                .onFailure { Log.w(TAG, "MusicBrainz nie odpowiedzial: ${it.message}") }
                .getOrNull()
                ?.let { ourTrackOrNull(a, t, it, "MusicBrainz") }
        }

        currentCoroutineContext().ensureActive()
        synchronized(cache) { cache[key] = result }
        if (net.mspanc.twinsenradio.BuildConfig.DEBUG) Log.i(TAG, "'$a - $t' -> okladka=${result?.artworkUrl != null} album='${result?.albumLabel()}'")
        result
    }

    /**
     * The answer, if it is about the question - see [TrackMatch].
     *
     * A rejection is written to the trace rather than only dropped, because
     * without it "the catalogue knows nothing about this track" and "the
     * catalogue answered about a different track" arrive in the file as the
     * same `znaleziono=false`, and those two call for opposite fixes: one is
     * the world, the other is us.
     */
    private fun ourTrackOrNull(
        artist: String,
        title: String,
        found: TrackInfo,
        source: String
    ): TrackInfo? {
        if (TrackMatch.plausible(artist, title, found.artistName, found.trackName)) return found
        if (net.mspanc.twinsenradio.BuildConfig.DEBUG) Log.i(TAG, "$source odpowiedzial o czym innym: '$artist - $title' -> '${found.artistName} - ${found.trackName}'")
        Trace.write("katalog-odrzucony") {
            put("zrodlo", source)
            put("pytanieWykonawca", artist)
            put("pytanieUtwor", title)
            put("katalogWykonawca", found.artistName)
            put("katalogUtwor", found.trackName)
            put("album", found.album)
        }
        return null
    }

    /**
     * Fallback for tracks not found in iTunes. MusicBrainz is an open
     * database with much better coverage of Polish and older music; artwork
     * is taken from the linked Cover Art Archive.
     */
    private fun queryMusicBrainz(artist: String, title: String): TrackInfo? {
        val query = "artist:\"$artist\" AND recording:\"$title\""
        val url = "$MUSICBRAINZ?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=1"
        val body = httpGet(url) ?: return null

        val recordings = JSONObject(body).optJSONArray("recordings") ?: return null
        if (recordings.length() == 0) return null
        val recording = recordings.getJSONObject(0)
        // Spelling according to the database - useful when a station shouts in all caps
        val catalogueTitle = recording.optString("title").ifBlank { null }
        val catalogueArtist = recording.optJSONArray("artist-credit")
            ?.optJSONObject(0)?.optString("name")?.ifBlank { null }
        val releases = recording.optJSONArray("releases") ?: return null
        if (releases.length() == 0) return null
        val release = releases.getJSONObject(0)

        val album = release.optString("title").ifBlank { null }
        val year = release.optString("date").take(4).toIntOrNull()
        val mbid = release.optString("id").ifBlank { null }

        // Cover Art Archive doesn't have artwork for everything, so we check
        // whether the address returns anything at all - otherwise we'd be
        // sending a dead link to the car.
        val art = mbid?.let { id ->
            val candidate = "$COVER_ART/release/$id/front-500"
            if (headOk(candidate)) candidate else null
        }

        if (album == null && art == null) return null
        return TrackInfo(
            artworkUrl = art,
            album = album,
            year = year,
            isSingle = false,
            durationMs = 0,
            trackName = catalogueTitle,
            artistName = catalogueArtist
        )
    }

    private fun httpGet(url: String): String? {
        return runCatching { net.mspanc.twinsenradio.net.SafeHttp.getText(url,2*1024*1024,MUSICBRAINZ_AGENT) }.getOrNull()
    }

    private fun headOk(url: String): Boolean = net.mspanc.twinsenradio.net.SafeHttp.headOk(url,MUSICBRAINZ_AGENT,6_000)

    /** Explicit user refresh: only accept another matching artist/title image. */
    suspend fun findAlternative(artist: String?, title: String?, excluded: Set<String>): TrackInfo? = withContext(ArtworkWorkScheduler.trackDispatcher) {
        lookupCalls.incrementAndGet()
        val a = artist?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        if (t.isBlank()) return@withContext null
        val next = if (net.mspanc.twinsenradio.BuildConfig.ENABLE_ITUNES_ARTWORK) {
            runCatching { query("$a $t".trim(), a, t, excluded) }.getOrNull()
        } else if (a.isNotBlank()) {
            runCatching { queryMusicBrainz(a, t) }.getOrNull()
                ?.let { matchingAlternative(a, t, it, excluded) }
        } else null
        currentCoroutineContext().ensureActive()
        next
    }

    /** A loose catalog search must still match the requested track on refresh. */
    internal fun matchingAlternative(
        artist: String?, title: String?, found: TrackInfo, excluded: Set<String>
    ): TrackInfo? = found.takeIf {
        !it.artworkUrl.isNullOrBlank() && it.artworkUrl !in excluded &&
            TrackMatch.plausible(artist, title, it.artistName, it.trackName)
    }

    private suspend fun query(term: String, artist: String? = null, title: String? = null, excluded: Set<String> = emptySet()): TrackInfo? {
        val url = "$ENDPOINT?term=${URLEncoder.encode(term, "UTF-8")}&media=music&entity=song&limit=${if (title == null) 1 else 20}"
            val body = httpGet(url) ?: return null
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            for (index in 0 until results.length()) {
            currentCoroutineContext().ensureActive()
            val row = results.getJSONObject(index)

            // iTunes returns a 100x100 thumbnail; swapping it in the URL gives full size
            val art = row.optString("artworkUrl100").ifBlank { null }
                ?.replace("100x100bb", "600x600bb")

            val rawAlbum = row.optString("collectionName").ifBlank { null }
            // Singles are named "Title - Single" in the catalogue; in that
            // case the release name adds nothing, and it's better to just
            // write "singiel" outright.
            val isSingle = rawAlbum?.endsWith(" - Single", ignoreCase = true) == true ||
                row.optInt("trackCount", 0) == 1
            val album = rawAlbum?.removeSuffix(" - Single")?.removeSuffix(" - EP")

            val year = row.optString("releaseDate").take(4).toIntOrNull()
            val duration = row.optLong("trackTimeMillis", 0L)

            val candidate = TrackInfo(
                artworkUrl = art,
                album = album,
                year = year,
                isSingle = isSingle,
                durationMs = duration,
                trackName = row.optString("trackName").ifBlank { null },
                artistName = row.optString("artistName").ifBlank { null }
            )
            if (title == null) return candidate
            if (art != null && art !in excluded && ourTrackOrNull(artist.orEmpty(), title, candidate, "iTunes") != null) return candidate
            }
            return null
    }
}
