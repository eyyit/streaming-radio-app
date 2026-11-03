package net.mspanc.twinsenradio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * App settings. Deliberately backed by SharedPreferences - the playback
 * service reads from it too, and writes are rare.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("twinsen_radio", Context.MODE_PRIVATE)

    /** Disables catalog lookup and publishing/downloading remote artwork, not just its display. */
    var onlineArtworkEnabled: Boolean
        get() = sp.getBoolean("online_artwork_enabled", net.mspanc.twinsenradio.BuildConfig.DEBUG)
        set(value) = sp.edit { putBoolean("online_artwork_enabled", value) }

    var artworkTransitionsEnabled: Boolean
        get() = sp.getBoolean("artwork_transitions_enabled", true)
        set(value) = sp.edit { putBoolean("artwork_transitions_enabled", value) }

    var randomStationOrder: Boolean
        get() = sp.getBoolean("random_station_order", false)
        set(value) { sp.edit().putBoolean("random_station_order", value).commit() }

    /**
     * A mode where every metadata field gets its Polish field name shown
     * instead of its value.
     *
     * OFF by default. It was on until we knew which fields the head unit
     * actually renders - measured on 2026-08-11 (FINDINGS.md), so from now on
     * it's a tool for further experiments, not the default state.
     */
    var diagnosticMode: Boolean
        get() = sp.getBoolean(KEY_DIAG, false)
        set(v) = sp.edit { putBoolean(KEY_DIAG, v) }

    /**
     * Whether to strip ICY metadata while in diagnostic mode.
     *
     * OFF by default. Stripping gives fully deterministic labels, but takes
     * away the ability to see what and how often a station actually
     * broadcasts - which is its own interesting topic. ICY only overwrites
     * `title`, `station`, and `genre`; the fields Android Auto actually shows
     * (`displayTitle`, `subtitle`) stay ours.
     */
    var stripIcyInDiagnostic: Boolean
        get() = sp.getBoolean(KEY_STRIP_ICY, false)
        set(v) = sp.edit { putBoolean(KEY_STRIP_ICY, v) }

    /** Whether to append the API field name to the Polish label, e.g. "TYTUL<title>". */
    var diagnosticShowApiName: Boolean
        get() = sp.getBoolean(KEY_DIAG_API, false)
        set(v) = sp.edit { putBoolean(KEY_DIAG_API, v) }

    /**
     * Whether a drive is recorded to a file (see [net.mspanc.twinsenradio.playback.Trace]).
     * On by default: the screens worth studying are in the car, and a drive
     * that was not recorded cannot be gone back to.
     */
    var traceEnabled: Boolean
        get() = sp.getBoolean(KEY_TRACE, net.mspanc.twinsenradio.BuildConfig.DEBUG && !net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE)
        set(v) = sp.edit { putBoolean(KEY_TRACE, v) }

    /** Presentation scheme for folders in Android Auto (see [ContentStyle]). */
    var browsableStyle: Int
        get() = sp.getInt(KEY_STYLE_BROWSABLE, ContentStyle.CATEGORY_LIST)
        set(v) = sp.edit { putInt(KEY_STYLE_BROWSABLE, v) }

    /** Presentation scheme for stations in Android Auto. */
    var playableStyle: Int
        get() = sp.getInt(KEY_STYLE_PLAYABLE, ContentStyle.LIST)
        set(v) = sp.edit { putInt(KEY_STYLE_PLAYABLE, v) }

    /**
     * Content of the successive description lines. Numbered like on the AID,
     * top to bottom - see the comment in [Presentation], which explains which
     * line goes into which metadata field and why they can't be split between
     * the AID and the head unit screen.
     */
    var lineTop: Int
        get() = sp.getInt(KEY_LINE_TOP, Presentation.DEFAULT_PLAYING.top.primary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_TOP, v) }

    var lineMiddle: Int
        get() = sp.getInt(KEY_LINE_MIDDLE, Presentation.DEFAULT_PLAYING.middle.primary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_MIDDLE, v) }

    var lineBottom: Int
        get() = sp.getInt(KEY_LINE_BOTTOM, Presentation.DEFAULT_PLAYING.bottom.primary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_BOTTOM, v) }

    /**
     * The same three lines for the state where no track is playing.
     *
     * Separate keys rather than a migration of the old ones: the two states
     * want different things, and silently reinterpreting somebody's "Tytuł
     * utworu" as a between-tracks choice would be a guess about their taste.
     * An upgrade therefore keeps its playing layout and starts from
     * [Presentation.DEFAULT_IDLE] for the gaps.
     */
    var lineTopIdle: Int
        get() = sp.getInt(KEY_LINE_TOP_IDLE, Presentation.DEFAULT_IDLE.top.primary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_TOP_IDLE, v) }

    var lineMiddleIdle: Int
        get() = sp.getInt(KEY_LINE_MIDDLE_IDLE, Presentation.DEFAULT_IDLE.middle.primary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_MIDDLE_IDLE, v) }

    var lineBottomIdle: Int
        get() = sp.getInt(KEY_LINE_BOTTOM_IDLE, Presentation.DEFAULT_IDLE.bottom.primary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_BOTTOM_IDLE, v) }

    /**
     * The second half of each line - see [LineSpec].
     *
     * Absent means [LineContent.EMPTY] for every line that had no second half
     * before, which is all of them: an upgrade therefore changes nothing until
     * something is actually picked here.
     */
    var lineTop2: Int
        get() = sp.getInt(KEY_LINE_TOP_2, Presentation.DEFAULT_PLAYING.top.secondary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_TOP_2, v) }

    var lineMiddle2: Int
        get() = sp.getInt(KEY_LINE_MIDDLE_2, Presentation.DEFAULT_PLAYING.middle.secondary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_MIDDLE_2, v) }

    var lineBottom2: Int
        get() = sp.getInt(KEY_LINE_BOTTOM_2, Presentation.DEFAULT_PLAYING.bottom.secondary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_BOTTOM_2, v) }

    var lineTopIdle2: Int
        get() = sp.getInt(KEY_LINE_TOP_IDLE_2, Presentation.DEFAULT_IDLE.top.secondary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_TOP_IDLE_2, v) }

    var lineMiddleIdle2: Int
        get() = sp.getInt(KEY_LINE_MIDDLE_IDLE_2, Presentation.DEFAULT_IDLE.middle.secondary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_MIDDLE_IDLE_2, v) }

    var lineBottomIdle2: Int
        get() = sp.getInt(KEY_LINE_BOTTOM_IDLE_2, Presentation.DEFAULT_IDLE.bottom.secondary.ordinal)
        set(v) = sp.edit { putInt(KEY_LINE_BOTTOM_IDLE_2, v) }

    /**
     * What fills the picture slot, per state - see [ArtContent].
     *
     * The defaults translate the three settings this replaced, so an upgrade
     * keeps looking the way it looked. Read-only translation, never written
     * back: the old keys stay where they are until the first save, and a
     * half-migrated file is worse than none.
     */
    var artPlaying: Int
        get() = sp.getInt(KEY_ART_PLAYING, legacyArt(idle = false).ordinal)
        set(v) = sp.edit { putInt(KEY_ART_PLAYING, v) }

    var artIdle: Int
        get() = sp.getInt(KEY_ART_IDLE, legacyArt(idle = true).ordinal)
        set(v) = sp.edit { putInt(KEY_ART_IDLE, v) }

    /**
     * The old clock face plus the old "always" switch, expressed as the new
     * per-state choice.
     *
     * The switch meant "clock even when a cover was found". So with it on, both
     * states were a clock; with it off, only the state without a cover was -
     * which in practice is the one between tracks.
     */
    private fun legacyArt(idle: Boolean): ArtContent {
        val face = ClockFace.at(sp.getInt(KEY_CLOCK_FACE, ClockFace.NONE.ordinal))
        val clock = when (face) {
            ClockFace.DIGITAL -> ArtContent.CLOCK_DIGITAL
            ClockFace.ANALOG -> ArtContent.CLOCK_ANALOG
            ClockFace.NONE -> null
        } ?: return if (idle) ArtContent.LOGO else ArtContent.COVER
        val always = sp.getBoolean(KEY_CLOCK_ALWAYS, false)
        return if (idle || always) clock else ArtContent.COVER
    }

    /** One stored line, with a retired composite unfolded - see LineContent.RETIRED. */
    private fun spec(primary: Int, secondary: Int): LineSpec =
        LineContent.expandedBy(LineContent.at(primary), LineContent.at(secondary))

    /** The full set of description settings, assembled from the above. */
    val presentation: Presentation
        get() = Presentation(
            playing = LineSet(
                top = spec(lineTop, lineTop2),
                middle = spec(lineMiddle, lineMiddle2),
                bottom = spec(lineBottom, lineBottom2)
            ),
            idle = LineSet(
                top = spec(lineTopIdle, lineTopIdle2),
                middle = spec(lineMiddleIdle, lineMiddleIdle2),
                bottom = spec(lineBottomIdle, lineBottomIdle2)
            ),
            artPlaying = ArtContent.at(artPlaying),
            artIdle = ArtContent.at(artIdle)
        )

    /** Background color of the clock drawn instead of cover art. */
    var clockBackground: Int
        get() = sp.getInt(KEY_CLOCK_BG, 0)
        set(v) = sp.edit { putInt(KEY_CLOCK_BG, v) }

    /** Color of the clock digits and hands; index 0 means automatic selection. */
    var clockForeground: Int
        get() = sp.getInt(KEY_CLOCK_FG, 0)
        set(v) = sp.edit { putInt(KEY_CLOCK_FG, v) }

    /**
     * Whether to append the year to the album in the "artist/title · album"
     * lines, when the catalog knows it. The album itself always shows when
     * available - this only controls the year suffix.
     */
    var enrichWithYear: Boolean
        get() = sp.getBoolean(KEY_ENRICH_ALBUM, true)
        set(v) = sp.edit { putBoolean(KEY_ENRICH_ALBUM, v) }

    /** Buffer profile index, see [BufferProfile.ALL]. */
    var bufferProfile: Int
        get() = sp.getInt(KEY_BUFFER, 1)
        set(v) = sp.edit { putInt(KEY_BUFFER, v) }

    /**
     * How long a title change from a station's title API waits before it goes
     * on, in seconds - see [net.mspanc.twinsenradio.playback.StationTitleApi].
     *
     * A setting rather than a constant because there is nothing to measure it
     * against any more: RNS's stream stopped naming tracks, so the only witness
     * of when a song really starts is the driver's ear. The default 3 s is the
     * user's own pick (30.10.2026) - long enough that a song the presenter talks
     * over appears as the presenter steps away, not under the talking.
     */
    var apiHoldSeconds: Int
        get() = sp.getInt(KEY_API_HOLD, 3)
        set(v) = sp.edit { putInt(KEY_API_HOLD, v) }

    /**
     * For how many minutes after the full hour a gap between tracks counts as
     * the news, so the programme gives way to the station - see
     * [net.mspanc.twinsenradio.playback.OnAirGuide]. 0 turns it off.
     *
     * The programme guide does not list the news and the title API says the
     * same slogan for it as for any talk, so the hour is the only clue there
     * is. A guess to be checked against the trace, which is why it is a setting.
     */
    var newsMinutes: Int
        get() = sp.getInt(KEY_NEWS_MINUTES, 5)
        set(v) = sp.edit { putInt(KEY_NEWS_MINUTES, v) }

    /** How cover art is delivered to Android Auto, see [ArtworkMode]. */
    var artworkMode: Int
        get() = sp.getInt(KEY_ARTWORK, ArtworkMode.RESOURCE_URI)
        set(v) = sp.edit { putInt(KEY_ARTWORK, v) }

    var userM3uUrls: List<String>
        get() = sp.getString(KEY_M3U, if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE)
            net.mspanc.twinsenradio.BuildConfig.DEFAULT_PLAYLIST_URL else "").orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        set(v) = sp.edit { putString(KEY_M3U, v.joinToString("\n")) }

    init {
        // A single source of truth for all screens, seeded on first use
        synchronized(FAV_LOCK) {
            if (!favouritesSeeded) {
                _favourites.value = sp.getStringSet(KEY_FAV, emptySet()).orEmpty()
                favouritesSeeded = true
            }
        }
        synchronized(DISCOVERED_LOCK) {
            if (!discoveredSeeded) {
                _discovered.value = readDiscovered()
                discoveredSeeded = true
            }
        }
        synchronized(HIDDEN_LOCK) {
            if (!hiddenSeeded) {
                _hidden.value = sp.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
                hiddenSeeded = true
            }
        }
    }

    var favourites: Set<String>
        get() = _favourites.value
        set(v) {
            sp.edit { putStringSet(KEY_FAV, v) }
            _favourites.value = v
        }

    /** @return true if the station was added to favourites, false if it was removed. */
    fun toggleFavourite(stationId: String): Boolean {
        val cur = favourites.toMutableSet()
        val added = if (cur.contains(stationId)) {
            cur.remove(stationId)
            false
        } else {
            cur.add(stationId)
            true
        }
        favourites = cur
        if (added && stationId !in presetOrder) presetOrder = presetOrder + stationId
        return added
    }

    /** Ordered radio presets; membership continues to use the shared favorites set. */
    var presetOrder: List<String>
        get() = sp.getString("preset_order", "").orEmpty().lines().filter { it.isNotBlank() }
        set(value) = sp.edit { putString("preset_order", value.distinct().joinToString("\n")) }

    var activeBrowseParent: String
        get() = sp.getString("active_browse_parent", "/all") ?: "/all"
        set(value) = sp.edit { putString("active_browse_parent", value) }

    /**
     * Stations added manually from the online directory. We keep the full
     * data, not just the identifier: the directory can stop responding or
     * delete an entry, and a station once added should keep working in the
     * car even without access to the directory.
     */
    val discovered: List<Station> get() = _discovered.value

    fun addDiscovered(station: Station) {
        if (discovered.any { it.id == station.id }) return
        saveDiscovered(discovered + station)
    }

    fun removeDiscovered(stationId: String) {
        saveDiscovered(discovered.filterNot { it.id == stationId })
    }

    fun isDiscovered(stationId: String): Boolean = discovered.any { it.id == stationId }

    private fun saveDiscovered(list: List<Station>) {
        val arr = JSONArray()
        list.forEach { s ->
            val variants = JSONArray()
            s.streams.forEach { v ->
                variants.put(
                    JSONObject().put("url", v.url).put("label", v.label).put("kbps", v.kbps)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    // "name" always stays what the catalog said. Shortening
                    // happens on the way out, in readDiscovered, so a rule we
                    // improve later re-applies to stations added long ago -
                    // and a bad shortening can never eat the original.
                    .put("name", s.catalogName ?: s.name)
                    // Only a name the user typed goes in here. Absent means
                    // "whatever the rule makes of it".
                    .put("nazwaWlasna", if (s.renamed) s.name else "")
                    .put("genre", s.genre)
                    .put("stream", s.stream)
                    .put("streams", variants)
                    .put("logoUrl", s.logoUrl ?: "")
            )
        }
        sp.edit { putString(KEY_DISCOVERED, arr.toString()) }
        // Read back rather than publishing `list`: what everyone else sees must
        // be what a fresh start would give them, shortened names and all.
        // Publishing the raw list left a station called one thing until the
        // next launch and another thing afterwards.
        _discovered.value = readDiscovered()
    }

    /**
     * Gives a catalog station a name of the user's own, or - with a blank
     * [name] - hands it back to [StationNames]. The catalog's own wording is
     * never overwritten, so this is always reversible.
     */
    fun renameDiscovered(stationId: String, name: String) {
        val wanted = name.trim()
        saveDiscovered(
            discovered.map { s ->
                if (s.id != stationId) {
                    s
                } else {
                    val catalog = s.catalogName ?: s.name
                    if (wanted.isEmpty()) {
                        s.copy(name = catalog, catalogName = null, renamed = false)
                    } else {
                        s.copy(name = wanted, catalogName = catalog, renamed = true)
                    }
                }
            }
        )
    }

    private fun readDiscovered(): List<Station> = runCatching {
        val raw = sp.getString(KEY_DISCOVERED, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val variants = o.optJSONArray("streams")?.let { list ->
                (0 until list.length()).map { j ->
                    val v = list.getJSONObject(j)
                    StreamVariant(
                        url = v.getString("url"),
                        label = v.optString("label").ifBlank { Station.DEFAULT_LABEL },
                        kbps = v.optInt("kbps", 0)
                    )
                }
            }.orEmpty()
            val catalog = o.getString("name")
            val own = o.optString("nazwaWlasna").ifBlank { null }
            val display = own ?: StationNames.shorten(catalog)
            Station(
                id = o.getString("id"),
                name = display,
                genre = o.optString("genre", "Z sieci"),
                stream = o.getString("stream"),
                streams = variants,
                logoUrl = o.optString("logoUrl").ifBlank { null },
                source = Station.Source.DISCOVERED,
                catalogName = catalog.takeIf { it != display },
                renamed = own != null
            )
        }
    }.getOrElse { emptyList() }

    /**
     * Search history - separate for the local list and for the online
     * directory, because they're two different worlds: here you search for
     * "rmf", there for "jazz radio paris". Newest first, no duplicates.
     */
    var localSearchHistory: List<String>
        get() = readHistory(KEY_HISTORY_LOCAL)
        set(v) = writeHistory(KEY_HISTORY_LOCAL, v)

    var webSearchHistory: List<String>
        get() = readHistory(KEY_HISTORY_WEB)
        set(v) = writeHistory(KEY_HISTORY_WEB, v)

    fun pushLocalSearch(query: String) = pushHistory(KEY_HISTORY_LOCAL, query)

    fun pushWebSearch(query: String) = pushHistory(KEY_HISTORY_WEB, query)

    private fun pushHistory(key: String, query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val current = readHistory(key).filterNot { it.equals(q, ignoreCase = true) }
        writeHistory(key, listOf(q) + current)
    }

    private fun readHistory(key: String): List<String> =
        sp.getString(key, "").orEmpty().lines().filter { it.isNotBlank() }

    private fun writeHistory(key: String, values: List<String>) =
        sp.edit { putString(key, values.take(HISTORY_LIMIT).joinToString("\n")) }

    /**
     * Built-in stations that the user has removed from the list.
     *
     * Built-in stations can't actually be deleted - they live in assets - so
     * instead we keep a set of hidden ones and skip them when building the
     * list. That makes removal reversible, and the list file stays untouched.
     */
    val hidden: Set<String> get() = _hidden.value

    fun hide(stationId: String) {
        saveHidden(hidden + stationId)
    }

    fun unhide(stationId: String) {
        saveHidden(hidden - stationId)
    }

    fun restoreAllHidden() = saveHidden(emptySet())

    fun isHidden(stationId: String) = stationId in hidden

    private fun saveHidden(value: Set<String>) {
        sp.edit { putStringSet(KEY_HIDDEN, value) }
        _hidden.value = value
    }

    /**
     * The chosen stream variant for a station. No entry = we take the best one
     * the station offers. We store the address, not the position index - the
     * variant list can change when the app is updated.
     */
    fun selectedStream(stationId: String): String? =
        sp.getString(KEY_STREAM_PREFIX + stationId, null)

    fun setSelectedStream(stationId: String, url: String?) = sp.edit {
        if (url == null) remove(KEY_STREAM_PREFIX + stationId) else putString(KEY_STREAM_PREFIX + stationId, url)
    }

    /**
     * A manually entered address. Takes priority over everything - even for
     * built-in stations, whose list file can't be edited.
     */
    fun customStream(stationId: String): String? =
        sp.getString(KEY_CUSTOM_STREAM_PREFIX + stationId, null)?.takeIf { it.isNotBlank() }

    fun setCustomStream(stationId: String, url: String?) = sp.edit {
        if (url.isNullOrBlank()) {
            remove(KEY_CUSTOM_STREAM_PREFIX + stationId)
        } else {
            putString(KEY_CUSTOM_STREAM_PREFIX + stationId, url.trim())
        }
    }

    /** Custom logo uploaded by the user - a path to a file in the app's directory. */
    /**
     * The last slogan a station sent about itself ("Pion i poziom!"), for the
     * screen before play - like the station's own app, which shows it there.
     * Written only when it changes: it comes again with every block.
     */
    fun slogan(stationId: String): String? = sp.getString(KEY_SLOGAN_PREFIX + stationId, null)

    fun rememberSlogan(stationId: String, slogan: String) {
        if (slogan(stationId) != slogan) sp.edit { putString(KEY_SLOGAN_PREFIX + stationId, slogan) }
    }

    fun customLogo(stationId: String): String? =
        sp.getString(KEY_CUSTOM_LOGO_PREFIX + stationId, null)?.takeIf { it.isNotBlank() }

    /** Synchronous result for artwork transactions; caller runs on artwork dispatcher. */
    fun setCustomLogoCommitted(stationId: String, path: String?): Boolean {
        val editor = sp.edit()
        if (path.isNullOrBlank()) editor.remove(KEY_CUSTOM_LOGO_PREFIX + stationId)
        else editor.putString(KEY_CUSTOM_LOGO_PREFIX + stationId, path)
        return editor.commit()
    }
    fun setCustomLogo(stationId: String, path: String?) = sp.edit {
        if (path.isNullOrBlank()) {
            remove(KEY_CUSTOM_LOGO_PREFIX + stationId)
        } else {
            putString(KEY_CUSTOM_LOGO_PREFIX + stationId, path)
        }
    }

    /**
     * How many times the station has been turned on. Used to sort by "most
     * frequently listened to" - after a few weeks of driving this is the best
     * ordering we can offer.
     */
    fun playCount(stationId: String): Int = sp.getInt(KEY_PLAYS_PREFIX + stationId, 0)

    fun bumpPlayCount(stationId: String) =
        sp.edit { putInt(KEY_PLAYS_PREFIX + stationId, playCount(stationId) + 1) }

    /** Station list order, see [StationSort]. */
    var stationSort: StationSort
        get() = StationSort.at(sp.getInt(KEY_SORT_STATIONS, 0))
        set(v) = sp.edit { putInt(KEY_SORT_STATIONS, v.ordinal) }

    /** Search results order in the directory, see [DiscoverSort]. */
    var discoverSort: DiscoverSort
        get() = DiscoverSort.at(sp.getInt(KEY_SORT_DISCOVER, 0))
        set(v) = sp.edit { putInt(KEY_SORT_DISCOVER, v.ordinal) }

    /** Recently listened to, newest first. */
    var recent: List<String>
        get() = sp.getString(KEY_RECENT, "").orEmpty().lines().filter { it.isNotBlank() }
        set(v) = sp.edit { putString(KEY_RECENT, v.take(20).joinToString("\n")) }

    fun pushRecent(stationId: String) {
        recent = listOf(stationId) + recent.filter { it != stationId }
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        /**
         * Favourites as a stream, rather than polling preferences on demand.
         *
         * Previously each screen read them at its own moment - the list when
         * coming to the foreground, the playback screen when rendering,
         * Android Auto when building buttons - and the states would drift
         * apart from each other. Now there's a single source of truth that
         * announces changes, and everyone interested observes it.
         */
        private val _favourites = MutableStateFlow<Set<String>>(emptySet())
        val favouritesFlow: StateFlow<Set<String>> = _favourites

        private var favouritesSeeded = false
        private val FAV_LOCK = Any()

        /**
         * Stations pulled in from the directory - just like favourites, a
         * single source of truth with a change stream, so the list on the
         * phone and the tree in the car rebuild at the same moment.
         */
        private val _discovered = MutableStateFlow<List<Station>>(emptyList())
        val discoveredFlow: StateFlow<List<Station>> = _discovered

        private var discoveredSeeded = false
        private val DISCOVERED_LOCK = Any()

        /** Hidden built-in stations - observable just like favourites. */
        private val _hidden = MutableStateFlow<Set<String>>(emptySet())
        val hiddenFlow: StateFlow<Set<String>> = _hidden

        private var hiddenSeeded = false
        private val HIDDEN_LOCK = Any()

        const val KEY_DIAG = "diagnostic_mode"
        const val KEY_DIAG_API = "diagnostic_api_names"
        const val KEY_TRACE = "trace_enabled"
        const val KEY_STRIP_ICY = "strip_icy_in_diagnostic"
        const val KEY_LINE_TOP = "line_top"
        const val KEY_LINE_MIDDLE = "line_middle"
        const val KEY_LINE_BOTTOM = "line_bottom"
        const val KEY_LINE_TOP_IDLE = "line_top_idle"
        const val KEY_LINE_MIDDLE_IDLE = "line_middle_idle"
        const val KEY_LINE_BOTTOM_IDLE = "line_bottom_idle"
        const val KEY_LINE_TOP_2 = "line_top_2"
        const val KEY_LINE_MIDDLE_2 = "line_middle_2"
        const val KEY_LINE_BOTTOM_2 = "line_bottom_2"
        const val KEY_LINE_TOP_IDLE_2 = "line_top_idle_2"
        const val KEY_LINE_MIDDLE_IDLE_2 = "line_middle_idle_2"
        const val KEY_LINE_BOTTOM_IDLE_2 = "line_bottom_idle_2"
        const val KEY_CLOCK_FACE = "clock_face"
        const val KEY_CLOCK_ALWAYS = "clock_cover_always"
        const val KEY_ART_PLAYING = "art_playing"
        const val KEY_ART_IDLE = "art_idle"
        const val KEY_CLOCK_BG = "clock_background"
        const val KEY_CLOCK_FG = "clock_foreground"
        const val KEY_ENRICH_ALBUM = "enrich_with_album"
        const val KEY_SWAP = "swap_title_artist"
        const val KEY_STYLE_BROWSABLE = "aa_style_browsable"
        const val KEY_STYLE_PLAYABLE = "aa_style_playable"
        const val KEY_BUFFER = "buffer_profile"
        const val KEY_API_HOLD = "api_hold_seconds"
        const val KEY_NEWS_MINUTES = "news_minutes"

        /** What the settings offer for [apiHoldSeconds]. */
        val API_HOLD_CHOICES = listOf(0, 1, 2, 3, 4, 5, 8, 12)

        /** What the settings offer for [newsMinutes]. */
        val NEWS_MINUTES_CHOICES = listOf(0, 3, 5, 8, 10, 15)
        const val KEY_ARTWORK = "artwork_mode"
        const val KEY_M3U = "user_m3u"
        const val KEY_FAV = "favourites"
        const val KEY_RECENT = "recent"
        const val KEY_DISCOVERED = "discovered_stations"
        const val KEY_HIDDEN = "hidden_stations"
        const val KEY_SORT_STATIONS = "sort_stations"
        const val KEY_SORT_DISCOVER = "sort_discover"

        // Station-dependent keys - the full key is the prefix plus its identifier
        private const val KEY_STREAM_PREFIX = "stream_choice_"
        private const val KEY_CUSTOM_STREAM_PREFIX = "stream_custom_"
        private const val KEY_CUSTOM_LOGO_PREFIX = "logo_custom_"
        private const val KEY_SLOGAN_PREFIX = "slogan_"
        private const val KEY_PLAYS_PREFIX = "plays_"
        const val KEY_HISTORY_LOCAL = "search_history_local"
        const val KEY_HISTORY_WEB = "search_history_web"

        /** This many entries is enough - a longer list wouldn't fit on screen anyway. */
        private const val HISTORY_LIMIT = 10
    }
}

/**
 * The four presentation schemes that Android Auto actually exposes to media
 * apps (the CONTENT_STYLE_* keys in the MediaBrowser extensions).
 */
object ContentStyle {
    const val LIST = 1
    const val GRID = 2
    const val CATEGORY_LIST = 3
    const val CATEGORY_GRID = 4

    const val EXTRA_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    const val EXTRA_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val EXTRA_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

    val LABELS = listOf(
        "Lista (LIST)",
        "Siatka (GRID)",
        "Lista kategorii (CATEGORY_LIST)",
        "Siatka kategorii (CATEGORY_GRID)"
    )

    fun indexToValue(i: Int) = i + 1
    fun valueToIndex(v: Int) = (v - 1).coerceIn(0, 3)
}

object ArtworkMode {
    /** android.resource:// - Android Auto fetches the logo directly from APK resources. */
    const val RESOURCE_URI = 0
    /** PNG bytes in the artworkData field - works even when the HU can't handle URIs. */
    const val EMBEDDED_BYTES = 1
    /** No cover art - for checking what the AID shows without graphics. */
    const val NONE = 2

    val LABELS = listOf(
        "URI zasobu (android.resource://)",
        "Bajty w metadanych (artworkData)",
        "Bez okładki"
    )
}

/** Buffering profiles. Values in ms. */
data class BufferProfile(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val forPlaybackMs: Int,
    val afterRebufferMs: Int
) {
    companion object {
        val ALL = listOf(
            BufferProfile("Mały — start ~1 s, zapas 20 s", 20_000, 30_000, 1_000, 3_000),
            BufferProfile("Średni — start ~2 s, zapas 45 s (domyślny)", 45_000, 75_000, 2_000, 6_000),
            BufferProfile("Duży — start ~4 s, zapas 120 s", 120_000, 180_000, 4_000, 12_000)
        )

        fun at(index: Int) = ALL.getOrElse(index) { ALL[1] }
    }
}
