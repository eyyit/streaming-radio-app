// Modified for Streaming Radio by Impostor Labs.
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
internal object ReleaseCatalog {
    const val MAX_RECORDINGS = 5
    const val MAX_RELEASE_CANDIDATES = 4
    const val MAX_ARTWORK_PROBES = 8

    data class Candidate(
        val releaseId: String,
        val releaseGroupId: String?,
        val album: String?,
        val year: Int?,
        val trackName: String?,
        val artistName: String?,
        val artworkUrl: String?
    )

    sealed interface Result {
        data class Found(val candidate: Candidate) : Result
        data object NotFound : Result
        data class TemporaryFailure(val retryAfterSeconds: Long? = null) : Result
    }

    private data class Release(
        val id: String,
        val groupId: String?,
        val album: String?,
        val year: Int?,
        val track: String?,
        val artist: String?
    )

    class ArtworkProbeBudget(maxProbes: Int = MAX_ARTWORK_PROBES) {
        private val limit = maxProbes.coerceIn(0, MAX_ARTWORK_PROBES)
        private var used = 0

        @Synchronized
        fun take(): Boolean {
            if (used >= limit) return false
            used++
            return true
        }

        @Synchronized
        fun exhausted(): Boolean = used >= limit
    }

    fun select(
        body: String,
        artist: String,
        title: String,
        excluded: Set<String> = emptySet(),
        probeBudget: ArtworkProbeBudget = ArtworkProbeBudget(),
        hasArtwork: (String) -> Boolean
    ): Result {
        val candidates = parse(body, artist, title)
        if (candidates.isEmpty()) return Result.NotFound
        var temporary = false
        var retryAfter: Long? = null

        for (release in candidates) {
            val url = releaseUrl(release.id)
            if (url in excluded) continue
            if (!probeBudget.take()) break
            try {
                if (hasArtwork(url)) return Result.Found(release.toCandidate(url))
            } catch (error: java.util.concurrent.CancellationException) {
                throw error
            } catch (error: net.mspanc.twinsenradio.net.SafeHttp.HttpStatusException) {
                if (error.statusCode == 429 || error.statusCode == 503 ||
                    error.retryAfterSeconds != null) {
                    return Result.TemporaryFailure(error.retryAfterSeconds)
                }
                temporary = true
                retryAfter = maxOf(retryAfter ?: 0L, error.retryAfterSeconds ?: 0L)
            } catch (_: java.io.IOException) {
                temporary = true
            }
        }

        val groups = candidates.mapNotNull { it.groupId }.distinct()
        for (group in groups) {
            val release = candidates.first { it.groupId == group }
            val url = groupUrl(group)
            if (url in excluded) continue
            if (!probeBudget.take()) break
            try {
                if (hasArtwork(url)) return Result.Found(release.toCandidate(url))
            } catch (error: java.util.concurrent.CancellationException) {
                throw error
            } catch (error: net.mspanc.twinsenradio.net.SafeHttp.HttpStatusException) {
                if (error.statusCode == 429 || error.statusCode == 503 ||
                    error.retryAfterSeconds != null) {
                    return Result.TemporaryFailure(error.retryAfterSeconds)
                }
                temporary = true
                retryAfter = maxOf(retryAfter ?: 0L, error.retryAfterSeconds ?: 0L)
            } catch (_: java.io.IOException) {
                temporary = true
            }
        }
        return if (temporary) Result.TemporaryFailure(retryAfter) else Result.NotFound
    }

    fun releaseUrl(id: String) = "https://coverartarchive.org/release/$id/front-500"
    fun groupUrl(id: String) = "https://coverartarchive.org/release-group/$id/front-500"

    private fun parse(body: String, artist: String, title: String): List<Release> {
        val recordings = JSONObject(body).optJSONArray("recordings") ?: return emptyList()
        val byRecording = mutableListOf<List<Release>>()
        for (index in 0 until minOf(recordings.length(), MAX_RECORDINGS)) {
            val recording = recordings.getJSONObject(index)
            val track = recording.optString("title").ifBlank { null }
            val artistCredit = recording.optJSONArray("artist-credit")
            val credits = (0 until (artistCredit?.length() ?: 0)).mapNotNull {
                artistCredit?.optJSONObject(it)?.optString("name")?.ifBlank { null }
            }
            val artistName = credits.firstOrNull()
            val directArtist = TrackMatch.plausible(null, artist, null, artistName)
            val sortedArtist = CatalogSearchTerms.sameArtist(artist, artistName) &&
                CatalogSearchTerms.sameRecordingTitle(title, track)
            val swapped = TrackMatch.plausible(null, title, null, artistName) &&
                TrackMatch.plausible(null, artist, null, track)
            val featuredMatch = CatalogSearchTerms.matchesFeaturedRecording(
                artist, title, credits, track
            )
            if ((!directArtist && !sortedArtist && !swapped && !featuredMatch) ||
                (!TrackMatch.plausible(artist, title, artistName, track) &&
                    !sortedArtist && !featuredMatch)) continue
            val releases = recording.optJSONArray("releases") ?: continue
            val candidates = mutableListOf<Release>()
            for (releaseIndex in 0 until minOf(releases.length(), MAX_RELEASE_CANDIDATES)) {
                val release = releases.getJSONObject(releaseIndex)
                val id = release.optString("id").ifBlank { null } ?: continue
                val album = release.optString("title").ifBlank { null }
                if (CatalogSearchTerms.hasFeaturedArtist(artist) &&
                    CatalogSearchTerms.isLiveRelease(album)) continue
                val group = release.optJSONObject("release-group")
                    ?.optString("id")?.ifBlank { null }
                if (candidates.none { it.id == id }) candidates += Release(
                    id = id,
                    groupId = group,
                    album = album,
                    year = release.optString("date").take(4).toIntOrNull(),
                    track = track,
                    artist = if (featuredMatch) credits.joinToString(" feat. ") else artistName
                )
            }
            byRecording += candidates
        }
        val result = mutableListOf<Release>()
        for (slot in 0 until MAX_RELEASE_CANDIDATES) {
            for (candidates in byRecording) {
                val candidate = candidates.getOrNull(slot) ?: continue
                if (result.none { it.id == candidate.id }) result += candidate
                if (result.size == MAX_RELEASE_CANDIDATES) return result
            }
        }
        return result
    }

    private fun Release.toCandidate(url: String) = Candidate(
        releaseId = id,
        releaseGroupId = groupId,
        album = album,
        year = year,
        trackName = track,
        artistName = artist,
        artworkUrl = url
    )
}

/** Query and verify explicit featured credits without broadening TrackMatch. */
internal object CatalogSearchTerms {
    private val featuredMarker = Regex("""(?i)\b(?:feat(?:uring)?|ft|f\s*/)\.?\s""")
    private val featuredSeparator = Regex(
        """(?i)\s*\b(?:feat(?:uring)?|ft|f\s*/)\.?\s*"""
    )
    private val trailingFeaturedCredit = Regex(
        """(?i)\s*(?:\(\s*|\[\s*)?(?:feat(?:uring)?|ft|f\s*/)\.?\s+[^)\]]*[)\]]?\s*$"""
    )
    private val translatedAliasBeforeMovement = Regex("""\s*\([^()]*\)(?=\s*:)""")
    private val versionQualifier = Regex(
        """(?i)\b(live|remaster(?:ed)?|radio edit|instrumental|mono|stereo|demo|alternate|mix|edit)\b"""
    )
    private val liveRelease = Regex("""(?i)\b(live|concert|tour|unplugged)\b""")
    private val combiningMarks = Regex("""\p{Mn}""")
    private val nonAlphanumeric = Regex("""[^a-z0-9]""")

    fun musicBrainzQueries(artist: String, title: String): List<String> {
        val primary = query(artist, title)
        if (hasFeaturedArtist(artist)) {
            val credits = splitFeaturedArtists(artist)
            if (credits.size > 1) {
                val artistTerms = credits.joinToString(" AND ") {
                    "artist:\"${escapeLucene(it)}\""
                }
                return listOf(primary, "$artistTerms AND recording:\"${escapeLucene(title)}\"")
            }
        }

        val reordered = reorderedCommaName(artist) ?: return listOf(primary)
        if (normalize(reordered) == normalize(artist)) return listOf(primary)
        val normalizedTitle = withoutTranslatedAlias(title)
        val fallback = query(reordered, normalizedTitle)
        return listOf(primary, fallback)
    }

    fun hasFeaturedArtist(value: String): Boolean = featuredMarker.containsMatchIn(value)

    fun matchesFeaturedRecording(
        queryArtist: String,
        queryTitle: String,
        creditedArtists: List<String>,
        catalogTitle: String?
    ): Boolean {
        if (!hasFeaturedArtist(queryArtist) || creditedArtists.isEmpty()) return false
        return hasAllFeaturedCredits(queryArtist, creditedArtists, queryTitle, catalogTitle)
    }

    fun matchesFeaturedTrack(
        queryArtist: String?,
        queryTitle: String?,
        catalogArtist: String?,
        catalogTitle: String?
    ): Boolean {
        if (queryArtist.isNullOrBlank() || catalogArtist.isNullOrBlank()) return false
        return hasAllFeaturedCredits(
            queryArtist, splitFeaturedArtists(catalogArtist), queryTitle, catalogTitle
        )
    }

    private fun hasAllFeaturedCredits(
        queryArtist: String,
        creditedArtists: List<String>,
        queryTitle: String?,
        catalogTitle: String?
    ): Boolean {
        val queryCredits = splitFeaturedArtists(queryArtist).map(::normalize)
        val catalogCredits = creditedArtists.map(::normalize)
        return queryCredits.size > 1 && queryCredits.all { it in catalogCredits } &&
            sameRecordingTitle(queryTitle, catalogTitle)
    }

    fun sameArtist(query: String?, catalog: String?): Boolean {
        if (query.isNullOrBlank() || catalog.isNullOrBlank()) return false
        val queryKey = normalize(query)
        val catalogKey = normalize(catalog)
        if (queryKey == catalogKey) return true
        val reordered = reorderedCommaName(query) ?: return false
        return normalize(reordered) == catalogKey
    }

    fun sameRecordingTitle(query: String?, catalog: String?): Boolean {
        if (query.isNullOrBlank() || catalog.isNullOrBlank()) return false
        val withoutFeature = trailingFeaturedCredit.replace(catalog, "").trim()
        val queryVariants = titleVariants(query).map(::normalize)
        val catalogVariants = titleVariants(withoutFeature).map(::normalize)
        return queryVariants.any { it in catalogVariants }
    }

    fun isLiveRelease(title: String?): Boolean =
        !title.isNullOrBlank() && liveRelease.containsMatchIn(title)

    private fun splitFeaturedArtists(value: String): List<String> {
        val credits = value.split(featuredSeparator)
        return credits.first().let(::listOf) + credits.drop(1).flatMap { credit ->
            credit.split(Regex("""\s*&\s*"""))
        }.map(String::trim).filter(String::isNotEmpty)
    }

    private fun reorderedCommaName(value: String): String? {
        val comma = value.indexOf(',')
        if (comma <= 0 || comma != value.lastIndexOf(',')) return null
        val family = value.substring(0, comma).trim()
        val given = value.substring(comma + 1).trim()
        if (family.isEmpty() || given.isEmpty()) return null
        return "$given $family"
    }

    private fun titleVariants(value: String): List<String> = listOf(
        value,
        withoutTranslatedAlias(value)
    ).distinct()

    private fun withoutTranslatedAlias(value: String): String {
        val match = translatedAliasBeforeMovement.find(value) ?: return value
        if (versionQualifier.containsMatchIn(match.value)) return value
        return value.removeRange(match.range).trim()
    }

    private fun query(artist: String, title: String): String =
        "artist:\"${escapeLucene(artist)}\" AND recording:\"${escapeLucene(title)}\""

    private fun normalize(value: String): String {
        val flattened = value.lowercase(java.util.Locale.ROOT).replace('ł', 'l')
        return java.text.Normalizer.normalize(flattened, java.text.Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .replace(nonAlphanumeric, "")
    }

    private fun escapeLucene(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

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

    sealed interface CatalogLookupResult {
        data class Found(val info: TrackInfo) : CatalogLookupResult
        data object NotFound : CatalogLookupResult
        data class TemporaryFailure(val retryAfterSeconds: Long? = null) : CatalogLookupResult
    }

    internal class MusicBrainzRequestGate(
        private val nowNanos: () -> Long = System::nanoTime,
        private val wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
    ) {
        private companion object {
            const val MIN_INTERVAL_MS = 1_000L
            const val MAX_BACKOFF_MS = 30_000L
        }

        private var lastRequestNanos = 0L
        private var hasRequested = false
        private var blockedUntilNanos = 0L
        private var failures = 0

        suspend fun beforeRequest(): Boolean {
            while (true) {
                val pause = synchronized(this) {
                    val now = nowNanos()
                    if (now < blockedUntilNanos) return false
                    val spacing = if (!hasRequested) 0L else
                        (lastRequestNanos + MIN_INTERVAL_MS * 1_000_000L - now)
                            .coerceAtLeast(0L)
                    if (spacing == 0L) {
                        lastRequestNanos = now
                        hasRequested = true
                        return true
                    }
                    (spacing + 999_999L) / 1_000_000L
                }
                wait(pause)
            }
        }

        @Synchronized fun succeeded() {
            failures = 0
            blockedUntilNanos = 0L
        }

        @Synchronized fun failed(retryAfterSeconds: Long? = null) {
            failures = (failures + 1).coerceAtMost(30)
            val backoffNanos = (1_000L shl (failures - 1))
                .coerceAtMost(MAX_BACKOFF_MS) * 1_000_000L
            val serverNanos = retryAfterSeconds?.coerceAtLeast(0L)?.let {
                if (it > Long.MAX_VALUE / 1_000_000_000L) Long.MAX_VALUE
                else it * 1_000_000_000L
            } ?: 0L
            val delay = maxOf(backoffNanos, serverNanos)
            val now = nowNanos()
            blockedUntilNanos = if (delay > Long.MAX_VALUE - now) {
                Long.MAX_VALUE
            } else now + delay
        }

        @Synchronized fun retryAfterSeconds(): Long {
            val remaining = (blockedUntilNanos - nowNanos()).coerceAtLeast(0L)
            return if (remaining > Long.MAX_VALUE - 999_999_999L) {
                Long.MAX_VALUE / 1_000_000_000L
            } else (remaining + 999_999_999L) / 1_000_000_000L
        }
    }

    private val MusicBrainzGate = MusicBrainzRequestGate()

    /** A transient catalog failure is returned to callers and never cached as a miss. */
    suspend fun find(artist: String?, title: String?): TrackInfo? =
        when (val result = findDetailed(artist, title)) {
            is CatalogLookupResult.Found -> result.info
            CatalogLookupResult.NotFound, is CatalogLookupResult.TemporaryFailure -> null
        }

    suspend fun findDetailed(artist: String?, title: String?): CatalogLookupResult =
        withContext(ArtworkWorkScheduler.trackDispatcher) {
            lookupCalls.incrementAndGet()
            val a = artist?.trim().orEmpty()
            val t = title?.trim().orEmpty()
            if (t.isEmpty()) return@withContext CatalogLookupResult.NotFound
            val key = "$a|$t".lowercase()
            cachedLookup(key) {
            var result: CatalogLookupResult? = null
            var appleFailed = false
            if (net.mspanc.twinsenradio.BuildConfig.ENABLE_ITUNES_ARTWORK) {
                try {
                    val apple = query("$a $t".trim())
                    if (apple != null) result = ourTrackOrNull(a, t, apple, "iTunes")
                        ?.let(CatalogLookupResult::Found)
                } catch (error: java.util.concurrent.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    appleFailed = true
                    Log.w(TAG, "iTunes nie odpowiedzial: ${error.message}")
                }
            }
            if (result == null && a.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                result = queryMusicBrainz(a, t).let { lookup ->
                    if (lookup is CatalogLookupResult.Found) {
                        ourTrackOrNull(a, t, lookup.info, "MusicBrainz")
                            ?.let(CatalogLookupResult::Found) ?: CatalogLookupResult.NotFound
                    } else lookup
                }
            }
            currentCoroutineContext().ensureActive()
            val final = if (appleFailed && (result == null || result == CatalogLookupResult.NotFound)) {
                CatalogLookupResult.TemporaryFailure()
            } else result ?: CatalogLookupResult.NotFound
            if (net.mspanc.twinsenradio.BuildConfig.DEBUG) {
                val found = (final as? CatalogLookupResult.Found)?.info
                Log.i(TAG, "'$a - $t' -> okladka=${found?.artworkUrl != null} album='${found?.albumLabel()}'")
            }
            final
            }
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
        if (matchesCatalogTrack(artist, title, found.artistName, found.trackName)) return found
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

    private fun matchesCatalogTrack(
        artist: String?,
        title: String?,
        catalogArtist: String?,
        catalogTitle: String?
    ): Boolean = if (CatalogSearchTerms.hasFeaturedArtist(artist.orEmpty())) {
        CatalogSearchTerms.matchesFeaturedTrack(artist, title, catalogArtist, catalogTitle)
    } else {
        TrackMatch.plausible(artist, title, catalogArtist, catalogTitle) ||
            (CatalogSearchTerms.sameArtist(artist, catalogArtist) &&
                CatalogSearchTerms.sameRecordingTitle(title, catalogTitle))
    }

    /**
     * Fallback for tracks not found in iTunes. MusicBrainz is an open
     * database with much better coverage of Polish and older music; artwork
     * is taken from the linked Cover Art Archive.
     */
    internal fun shouldCache(result: CatalogLookupResult): Boolean =
        result !is CatalogLookupResult.TemporaryFailure

    internal suspend fun cachedLookup(
        key: String,
        lookup: suspend () -> CatalogLookupResult
    ): CatalogLookupResult {
        synchronized(cache) {
            if (cache.containsKey(key)) {
                return cache[key]?.let(CatalogLookupResult::Found)
                    ?: CatalogLookupResult.NotFound
            }
        }
        val result = lookup()
        currentCoroutineContext().ensureActive()
        if (shouldCache(result)) synchronized(cache) {
            cache[key] = (result as? CatalogLookupResult.Found)?.info
        }
        return result
    }

    private fun escapeLucene(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private suspend fun queryMusicBrainz(
        artist: String,
        title: String,
        excluded: Set<String> = emptySet()
    ): CatalogLookupResult {
        val probeBudget = ReleaseCatalog.ArtworkProbeBudget()
        for (query in CatalogSearchTerms.musicBrainzQueries(artist, title)) {
            val result = queryMusicBrainzOnce(query, artist, title, excluded, probeBudget)
            if (result !is CatalogLookupResult.NotFound || probeBudget.exhausted()) {
                return result
            }
        }
        return CatalogLookupResult.NotFound
    }

    private suspend fun queryMusicBrainzOnce(
        query: String,
        artist: String,
        title: String,
        excluded: Set<String>,
        probeBudget: ReleaseCatalog.ArtworkProbeBudget
    ): CatalogLookupResult {
        val url = "$MUSICBRAINZ?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=${ReleaseCatalog.MAX_RECORDINGS}"
        if (!MusicBrainzGate.beforeRequest()) {
            return CatalogLookupResult.TemporaryFailure(
                MusicBrainzGate.retryAfterSeconds()
            )
        }
        currentCoroutineContext().ensureActive()
        val body = try {
            net.mspanc.twinsenradio.net.SafeHttp.getText(
                url, 2 * 1024 * 1024, MUSICBRAINZ_AGENT
            )
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: net.mspanc.twinsenradio.net.SafeHttp.HttpStatusException) {
            MusicBrainzGate.failed(error.retryAfterSeconds)
            return CatalogLookupResult.TemporaryFailure(error.retryAfterSeconds)
        } catch (_: java.io.IOException) {
            MusicBrainzGate.failed()
            return CatalogLookupResult.TemporaryFailure()
        }
        MusicBrainzGate.succeeded()
        val selected = try {
            ReleaseCatalog.select(body, artist, title, excluded, probeBudget) { imageUrl ->
                net.mspanc.twinsenradio.net.SafeHttp.headExists(
                    imageUrl, MUSICBRAINZ_AGENT, 6_000
                )
            }
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: Exception) {
            MusicBrainzGate.failed(
                (error as? net.mspanc.twinsenradio.net.SafeHttp.HttpStatusException)
                    ?.retryAfterSeconds
            )
            return CatalogLookupResult.TemporaryFailure(
                (error as? net.mspanc.twinsenradio.net.SafeHttp.HttpStatusException)
                    ?.retryAfterSeconds
            )
        }
        currentCoroutineContext().ensureActive()
        return when (selected) {
            is ReleaseCatalog.Result.Found -> {
                val item = selected.candidate
                CatalogLookupResult.Found(TrackInfo(
                    artworkUrl = item.artworkUrl,
                    album = item.album,
                    year = item.year,
                    isSingle = false,
                    durationMs = 0,
                    trackName = item.trackName,
                    artistName = item.artistName
                ))
            }
            ReleaseCatalog.Result.NotFound -> CatalogLookupResult.NotFound
            is ReleaseCatalog.Result.TemporaryFailure -> {
                MusicBrainzGate.failed(selected.retryAfterSeconds)
                CatalogLookupResult.TemporaryFailure(selected.retryAfterSeconds)
            }
        }
    }

    private fun httpGet(url: String): String? {
        return net.mspanc.twinsenradio.net.SafeHttp.getText(url,2*1024*1024,MUSICBRAINZ_AGENT)
    }

    private fun headOk(url: String): Boolean = net.mspanc.twinsenradio.net.SafeHttp.headOk(url,MUSICBRAINZ_AGENT,6_000)

    /** Explicit user refresh: only accept another matching artist/title image. */
    suspend fun findAlternative(
        artist: String?, title: String?, excluded: Set<String>
    ): TrackInfo? = when (val result = findAlternativeDetailed(artist, title, excluded)) {
        is CatalogLookupResult.Found -> result.info
        CatalogLookupResult.NotFound, is CatalogLookupResult.TemporaryFailure -> null
    }

    suspend fun findAlternativeDetailed(
        artist: String?, title: String?, excluded: Set<String>
    ): CatalogLookupResult = withContext(ArtworkWorkScheduler.trackDispatcher) {
        lookupCalls.incrementAndGet()
        val a = artist?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        if (t.isBlank() || a.isBlank()) return@withContext CatalogLookupResult.NotFound
        if (net.mspanc.twinsenradio.BuildConfig.ENABLE_ITUNES_ARTWORK) {
            val apple = try {
                query("$a $t".trim(), a, t, excluded)
            } catch (error: java.util.concurrent.CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withContext CatalogLookupResult.TemporaryFailure()
            }
            if (apple != null) {
                return@withContext CatalogLookupResult.Found(apple)
            }
        }
        val result = queryMusicBrainz(a, t, excluded)
        currentCoroutineContext().ensureActive()
        result
    }

    /** A loose catalog search must still match the requested track on refresh. */
    internal fun matchingAlternative(
        artist: String?, title: String?, found: TrackInfo, excluded: Set<String>
    ): TrackInfo? = found.takeIf {
        !it.artworkUrl.isNullOrBlank() && it.artworkUrl !in excluded &&
            matchesCatalogTrack(artist, title, it.artistName, it.trackName)
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
