package net.mspanc.twinsenradio.data

import android.content.Context
import android.util.Log
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * Source of the station list: the built-in assets/stations.json plus any M3U
 * lists provided by the user in Options. Keeps the result in memory, because
 * Android Auto can query the browsing tree frequently.
 */
class StationRepository private constructor(private val appContext: Context) {

    private val prefs = Prefs(appContext)
    private val playlistPrefs = appContext.getSharedPreferences("playlist_library", Context.MODE_PRIVATE)
    private val sourceLock = Any()
    private val stationIds by lazy { loadIdMap() }
    private val assignedIds by lazy { stationIds.values.toMutableSet() }
    private val parsedCatalogs = mutableMapOf<String, List<Station>>()
    @Volatile private var activeCatalogs: Map<String, List<Station>> = emptyMap()
    private var snapshotRaw: String? = null
    private var snapshotCache: List<Station> = emptyList()

    /** Current sources, including legacy M3U URLs migrated on first access. */
    fun playlists(): List<PlaylistSource> = synchronized(sourceLock) {
        val saved = playlistPrefs.getString("sources", null)
        if (saved == null) {
            val migrated = prefs.userM3uUrls.mapIndexed { index, url ->
                PlaylistSource("pl_${M3uParser.stableKey(url).take(20)}", url,
                    defaultPlaylistName(url, index))
            }
            if (runCatching { CatalogLimits.sources(migrated) }.isFailure) return@synchronized emptyList()
            writeSources(migrated)
            migrated
        } else readSources(saved).map { source ->
            source.copy(stationCount = activeCatalogs[source.url]?.size ?: 0)
        }
    }

    fun sourceStations(id: String): List<Station> {
        val source = playlists().firstOrNull { it.id == id } ?: return emptyList()
        return StationVisibility.visible(rawSourceStations(source), stationExclusions(), all = false).map(::resolve)
    }

    private fun rawSourceStations(source: PlaylistSource): List<Station> =
        activeCatalogs[source.url].orEmpty().map(::stable)

    /** Local-only rules never modify cached or hosted M3U source contents. */
    fun stationExclusions(): List<StationExclusion> = synchronized(sourceLock) {
        StationVisibility.decode(playlistPrefs.getString("station_exclusions", "[]")!!)
    }

    private fun writeExclusions(rules: List<StationExclusion>) {
        playlistPrefs.edit().putString("station_exclusions", StationVisibility.encode(rules)).apply()
        revision.value += 1
    }

    private fun originalStation(station: Station): Station =
        (cache + prefs.discovered).map(::stable).firstOrNull { it.id == station.id } ?: station

    fun hideFromAll(station: Station) = synchronized(sourceLock) {
        writeExclusions(StationVisibility.exclude(stationExclusions(), originalStation(station), false))
    }

    fun removeFromApp(station: Station) = synchronized(sourceLock) {
        val original = originalStation(station)
        val rules = StationVisibility.exclude(stationExclusions(), original, true)
        prefs.favourites = StationVisibility.favoriteIds(prefs.favourites, rules)
        prefs.recent = prefs.recent.filterNot { it == original.id }
        writeExclusions(rules)
    }

    fun exclusionSourceNames(station: Station): List<String> = playlists()
        .filter { source -> rawSourceStations(source).any { it.stream == station.stream } }
        .map { it.name }

    fun canRestoreStation(station: Station): Boolean =
        (cache + prefs.discovered).any { it.stream == station.stream }

    /** A still-present source is required; restoring never creates a Favorite. */
    fun restoreStation(station: Station): Boolean = synchronized(sourceLock) {
        val sourceStreams = (cache + prefs.discovered).map { it.stream }.toSet()
        if (station.stream !in sourceStreams) return@synchronized false
        writeExclusions(StationVisibility.restore(stationExclusions(), station.stream, sourceStreams))
        prefs.unhide(station.id)
        true
    }

    fun restorableStations(): List<StationExclusion> =
        (stationExclusions() + allIncludingHidden().filter { it.id in prefs.hidden }
            .map { StationExclusion(originalStation(it), false) }).distinctBy { it.station.stream }

    fun sourceNames(stationId: String): List<String> {
        val bySource = playlists().associate { it.id to it.name }
        return PlaylistLibrary.aggregate(playlists().map { it to sourceStations(it.id) })
            .firstOrNull { stableId(it.station) == stationId }
            ?.memberships.orEmpty().mapNotNull(bySource::get)
    }

    fun isDetachedFavorite(stationId: String): Boolean {
        if (stationId !in prefs.favourites) return false
        return (cache + prefs.discovered).none { stableId(it) == stationId } && detachedStations().any { it.id == stationId }
    }

    /** Move an item in the independently persisted All or Favorites order. */
    fun moveStation(stationId: String, delta: Int, favorites: Boolean) {
        val key = if (favorites) "favorite_order" else "all_order"
        val order = readStringList(playlistPrefs.getString(key, ""))
        val moved = PlaylistLibrary.move(order, stationId, delta)
        if (moved != order) {
            playlistPrefs.edit().putString(key, moved.joinToString("\n")).apply()
            revision.value += 1
        }
    }

    suspend fun validatePlaylist(url: String): List<Station> =
        fetchValidatedPlaylist(url).second

    private suspend fun fetchValidatedPlaylist(url: String): Pair<String, List<Station>> = withContext(Dispatchers.IO) {
        val normalized = url.trim()
        CatalogLimits.field(normalized, CatalogLimits.MAX_URL, "Playlist URL")
        PlaylistLibrary.requireRemoteUrl(normalized)
        val body = fetchText(normalized)
        require(!body.contains("#EXT-X-", ignoreCase = true)) { "Expected a station catalog, received HLS" }
        val stations = M3uParser.parse(body, normalized)
        require(stations.isNotEmpty()) { "No playable station entries" }
        body to stations
    }

    /** Save only a validated playlist. Existing source cache remains until validation succeeds. */
    suspend fun savePlaylist(name: String, url: String, id: String? = null): PlaylistSource {
        require(playlists().none { it.id == id && it.isLocal }) {
            "Saved stations can only be renamed or removed"
        }
        val normalized = url.trim()
        CatalogLimits.field(name.trim(), CatalogLimits.MAX_NAME, "Playlist name")
        // Reject source-count/field violations before doing a network request.
        val proposal = PlaylistSource(id ?: "new", normalized, name.trim())
        CatalogLimits.sources(playlists().filterNot { it.id == id || it.url == normalized } + proposal)
        val validated = fetchValidatedPlaylist(normalized)
        val validation = validated.second
        val currentSources = playlists()
        val existingIndex = id?.let { key -> currentSources.indexOfFirst { it.id == key } } ?: -1
        val existing = currentSources.getOrNull(existingIndex)
        val source = PlaylistSource(existing?.id ?: "pl_${java.util.UUID.randomUUID()}",
            normalized, name.trim().ifBlank { defaultPlaylistName(normalized, 0) },
            validation.size)
        refreshLock.withLock { withContext(Dispatchers.IO) {
        val file = playlistFile(normalized)
        synchronized(sourceLock) {
            val previous = playlists()
            val sources = previous.filterNot { it.id == source.id || it.url == source.url }.toMutableList()
            val insertAt = existingIndex.takeIf { it >= 0 }?.coerceAtMost(sources.size) ?: sources.size
            sources.add(insertAt, source)
            CatalogLimits.sources(sources)
            val candidate = sources.associate { it.url to
                (if (it.url == normalized) validation else activeCatalogs[it.url].orEmpty()) }
            CatalogLimits.catalog(candidate.values)
            snapshotFavorites(allIncludingHidden())
            val output = file.startWrite()
            try { output.write(validated.first.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
            catch (e: Exception) { file.failWrite(output); throw e }
            parsedCatalogs[normalized] = validation
            writeSources(sources)
            prefs.userM3uUrls = sources.map { it.url }
            reloadPlaylistCache(sources)
        }
        } }
        return source
    }

    /** The internal saved catalog has no editable URL and never does network I/O. */
    fun renameLocalPlaylist(name: String): Boolean = synchronized(sourceLock) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Enter a playlist name" }
        CatalogLimits.field(normalized, CatalogLimits.MAX_NAME, "Playlist name")
        val current = playlists()
        if (current.none { it.isLocal }) return@synchronized false
        writeSources(current.map { if (it.isLocal) it.copy(name = normalized) else it })
        revision.value += 1
        true
    }

    fun removePlaylist(id: String): Boolean = synchronized(sourceLock) {
        val current = playlists()
        if (current.none { it.id == id }) return@synchronized false
        val sources = current.filterNot { it.id == id }
        snapshotFavorites(allIncludingHidden())
        writeSources(sources)
        prefs.userM3uUrls = sources.map { it.url }
        reloadPlaylistCache(sources)
        true
    }

    suspend fun refreshPlaylists(force: Boolean = false) = refreshUserLists(force)

    /** All playlist stations, deduplicated by exact stream URL in source order. */
    fun playlistStations(): List<PlaylistStation> = PlaylistLibrary.aggregate(
        playlists().map { it to sourceStations(it.id) }
    )

    private fun readSources(raw: String): List<PlaylistSource> = runCatching {
        val array = JSONArray(raw)
        require(array.length() <= CatalogLimits.MAX_SOURCES) { "Too many saved playlists" }
        (0 until array.length()).map { i -> array.getJSONObject(i).let {
            PlaylistSource(it.getString("id"), it.getString("url"), it.getString("name"), it.optInt("count"))
        } }.also(CatalogLimits::sources)
    }.getOrDefault(emptyList())

    private fun writeSources(sources: List<PlaylistSource>) {
        CatalogLimits.sources(sources)
        val array = JSONArray()
        sources.forEach { array.put(JSONObject().put("id", it.id).put("url", it.url)
            .put("name", it.name).put("count", it.stationCount)) }
        playlistPrefs.edit().putString("sources", array.toString()).apply()
    }

    private fun reloadPlaylistCache(sources: List<PlaylistSource>) {
        snapshotFavorites(allIncludingHidden())
        val catalogs = loadCatalogs(sources)
        val updated = builtIn + catalogs.values.flatten()
        parsedCatalogs.keys.retainAll(sources.map { it.url }.toSet())
        activeCatalogs = catalogs
        retiredStations=(cache.map(::stable)+retiredStations).distinctBy { it.id }.take(512)
        cache = updated
        userLoadedFor = sources.map { it.url }
        rememberIds(updated + prefs.discovered)
        revision.value += 1
    }

    private fun readStringList(raw: String?): List<String> = raw.orEmpty().lines().filter(String::isNotBlank)
    private fun loadIdMap(): MutableMap<String, String> = runCatching {
        JSONObject(playlistPrefs.getString("stream_ids", "{}")!!).let { json ->
            buildMap { json.keys().forEach { key -> put(key, json.getString(key)) } }.toMutableMap()
        }
    }.getOrDefault(mutableMapOf())

    @Synchronized private fun rememberIds(stations: List<Station>) {
        val ids = stationIds
        var changed=false
        stations.forEach { station -> if (station.stream !in ids) {
            val assigned = PlaylistLibrary.stationId(station.stream, station.id, ids, assignedIds)
            ids[station.stream] = assigned
            assignedIds += assigned
            changed=true
        } }
        if(!changed) return
        val json = JSONObject()
        ids.forEach { (stream, id) -> json.put(stream, id) }
        playlistPrefs.edit().putString("stream_ids", json.toString()).apply()
    }

    @Synchronized private fun stableId(station: Station): String {
        val ids = stationIds
        val id = ids[station.stream]
        if (id != null) return id
        val assigned = PlaylistLibrary.stationId(station.stream, station.id, ids, assignedIds)
        ids[station.stream] = assigned
        assignedIds += assigned
        val json = JSONObject()
        ids.forEach { (stream, value) -> json.put(stream, value) }
        playlistPrefs.edit().putString("stream_ids", json.toString()).apply()
        return assigned
    }

    private fun stable(station: Station): Station = station.copy(id = stableId(station))

    @Synchronized private fun snapshotFavorites(stations: List<Station>) {
        val favorites = prefs.favourites
        if (favorites.isEmpty()) return
        val previous=detachedStations()
        val saved = previous.associateBy { it.id }.toMutableMap()
        stations.filter { it.id in favorites }.forEach { saved[it.id] = it }
        if(saved.values.toList()==previous) return
        val array = JSONArray()
        saved.values.forEach { station ->
            array.put(JSONObject().put("id", station.id).put("name", station.name)
                .put("genre", station.genre).put("stream", station.stream)
                .put("logoUrl", station.logoUrl ?: "").put("source", station.source.name))
        }
        snapshotRaw=array.toString()
        snapshotCache=saved.values.toList()
        playlistPrefs.edit().putString("detached_favorites", snapshotRaw).apply()
    }

    @Synchronized private fun detachedStations(): List<Station> {
      val raw=playlistPrefs.getString("detached_favorites", "[]") ?: "[]"
      if(raw==snapshotRaw) return snapshotCache.filter { it.id in prefs.favourites }
      snapshotCache=runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { i -> array.getJSONObject(i).let { o ->
            Station(o.getString("id"), o.getString("name"), o.getString("genre"),
                o.getString("stream"), logoUrl = o.optString("logoUrl").ifBlank { null },
                source = runCatching { Station.Source.valueOf(o.getString("source")) }
                    .getOrDefault(Station.Source.USER_M3U))
        } }
      }.getOrDefault(emptyList())
      snapshotRaw=raw
      return snapshotCache.filter { it.id in prefs.favourites }
    }

    private fun ordered(stations: List<Station>, key: String): List<Station> {
        val byId = stations.associateBy { it.id }
        val saved = readStringList(playlistPrefs.getString(key, ""))
        val order = PlaylistLibrary.reconcileOrder(saved, stations.map { it.id }, stations.map { it.id })
        if (order != saved) playlistPrefs.edit().putString(key, order.joinToString("\n")).apply()
        return order.mapNotNull(byId::get)
    }
    private fun defaultPlaylistName(url: String, index: Int): String =
        runCatching { URL(url).host }.getOrDefault("").ifBlank { "Playlist ${index + 1}" }

    @Volatile
    private var cache: List<Station> = emptyList()
    // Resolve the current session after source removal without adding it to All.
    @Volatile private var retiredStations: List<Station> = emptyList()

    @Volatile
    private var userLoadedFor: List<String> = emptyList()
    @Volatile private var failedSources: Set<String> = emptySet()
    val lastRefreshFailureCount: Int get() = failedSources.size
    private val refreshLock = Mutex()
    private val revision = MutableStateFlow(0L)
    val revisions = revision.asStateFlow()
    private val cacheDirectory = java.io.File(appContext.filesDir, "playlists").apply { mkdirs() }
    private fun playlistFile(url: String) = AtomicFile(java.io.File(cacheDirectory, M3uParser.stableKey(url) + ".m3u"))
    private fun cachedStations(url: String): List<Station> = synchronized(sourceLock) {
        parsedCatalogs.getOrPut(url) {
            runCatching {
                val body = playlistFile(url).openRead().use(CatalogLimits::readBody)
                PlaylistLibrary.parseCachedPlaylist(body, url)
            }.getOrDefault(emptyList())
        }
    }

    private fun loadCatalogs(sources: List<PlaylistSource>): Map<String, List<Station>> = runCatching {
        CatalogLimits.sources(sources)
        sources.associate { it.url to cachedStations(it.url) }.also { CatalogLimits.catalog(it.values) }
    }.getOrDefault(emptyMap())

    /** Built-in list - always available, doesn't require network access. */
    val builtIn: List<Station> by lazy {
        if (net.mspanc.twinsenradio.BuildConfig.IS_AUTOMOTIVE) emptyList() else readBuiltIn()
    }

    init {
        activeCatalogs = loadCatalogs(playlists())
        cache = builtIn + activeCatalogs.values.flatten()
        rememberIds(cache + prefs.discovered)
    }

    /**
     * Full list: built-in + from M3U lists + stations added from the online catalog.
     *
     * We read the fetched ones on every call, not from `cache`: they live in a
     * stream in [Prefs] and can change at any moment, including from another
     * screen. There are at most a dozen or so of them, so there's nothing to optimize.
     */
    fun all(): List<Station> {
        if (cache.isEmpty()) cache = builtIn
        val extra = prefs.discovered
        val full = (cache + extra).map(::stable).distinctBy { it.stream }.map(::resolve)
        // We filter out hidden ones here, in one place - this way they disappear
        // everywhere at once: in the phone list, in search, and in the car's browsing tree.
        val hidden = prefs.hidden
        val result = sorted(full)
        if (readStringList(playlistPrefs.getString("all_order", "")).isEmpty()) {
            playlistPrefs.edit().putString("all_order", result.map { it.id }.joinToString("\n")).apply()
        }
        return StationVisibility.visible(ordered(result, "all_order"), stationExclusions(), all = true)
            .filterNot { it.id in hidden }
    }

    /**
     * Ordering of the list. By default, the order from the file - arranged
     * thematically and thought out - or, on request, alphabetically or by
     * what the user actually listens to.
     */
    private fun sorted(stations: List<Station>): List<Station> = when (prefs.stationSort) {
        StationSort.MOST_PLAYED ->
            stations.sortedWith(
                compareByDescending<Station> { prefs.playCount(it.id) }.thenBy { fold(it.name) }
            )
        StationSort.NAME -> stations.sortedBy { fold(it.name) }
        StationSort.GENRE -> stations.sortedWith(compareBy({ fold(it.genre) }, { fold(it.name) }))
        StationSort.DEFAULT -> stations
    }

    /** Full list including hidden stations - needed only for restoring them. */
    fun allIncludingHidden(): List<Station> {
        if (cache.isEmpty()) cache = builtIn
        return (cache + prefs.discovered).map(::stable).distinctBy { it.stream }.map(::resolve)
    }

    fun byId(id: String): Station? =
        allIncludingHidden().firstOrNull { it.id == id }
            ?: detachedStations().firstOrNull { it.id == id }
            ?: retiredStations.firstOrNull { it.id == id }

    fun byMediaId(mediaId: String): Station? =
        Station.idFromMediaId(mediaId)?.let { byId(it) }

    /** Resolve a controller's queue with one catalog pass, rather than one per item. */
    fun byMediaIds(mediaIds: List<String>): List<Station?> {
        val byId = (allIncludingHidden() + detachedStations() + retiredStations)
            .distinctBy { it.id }.associateBy { it.id }
        return mediaIds.map { Station.idFromMediaId(it)?.let(byId::get) }
    }

    fun favourites(): List<Station> {
        val fav = prefs.favourites
        val stations = StationVisibility.visible(allIncludingHidden(), stationExclusions(), all = false)
            .filter { it.id in fav }
        snapshotFavorites(stations)
        val result = PlaylistLibrary.favorites(stations,
            StationVisibility.visible(detachedStations(), stationExclusions(), all = false), fav)
        if (readStringList(playlistPrefs.getString("favorite_order", "")).isEmpty()) {
            val legacy = (prefs.presetOrder + result.map { it.id }).distinct()
            playlistPrefs.edit().putString("favorite_order", legacy.joinToString("\n")).apply()
        }
        return ordered(result, "favorite_order")
    }

    fun recent(): List<Station> {
        val order = prefs.recent
        val removed = stationExclusions().filter { it.removed }.map { it.station.id }.toSet()
        return order.filterNot { it in removed }.mapNotNull { byId(it) }
    }

    /**
     * The station play starts with when nothing is loaded: the most recently
     * listened one, then the first favourite, then the first on the list. One
     * answer for the service resuming in the car and for the phone's screen
     * before play, so the screen shows the station that will actually start.
     */
    fun resumeCandidate(): Station? =
        recent().firstOrNull() ?: favourites().firstOrNull() ?: all().firstOrNull()

    fun genres(): List<String> = all().map { it.genre }.distinct().sorted()

    fun byGenre(genre: String): List<Station> = all().filter { it.genre == genre }

    /**
     * Search that's resistant to Polish characters and case - in the car, the
     * query comes from speech recognition and rarely hits diacritics correctly.
     */
    fun search(query: String): List<Station> {
        val q = fold(query)
        if (q.isBlank()) return all()
        return all().filter { fold(it.name).contains(q) || fold(it.genre).contains(q) }
    }

    /** Fetches the user's M3U lists. Safe to call multiple times. */
    suspend fun refreshUserLists(force: Boolean = false) = withContext(Dispatchers.IO) {
      refreshLock.withLock {
        val sources = playlists()
        val urls = sources.map { it.url }
        var accepted = activeCatalogs.filterKeys { it in urls }
        val failures = mutableSetOf<String>()
        PlaylistLibrary.remoteSources(sources).forEach { source ->
            currentCoroutineContext().ensureActive()
            val url = source.url
            // A private migration snapshot is ordinary bounded cached M3U data.
            // Force refresh must never turn it into a network request.
            val file = playlistFile(url)
            val previous = accepted[url].orEmpty()
            val age = System.currentTimeMillis() - file.baseFile.lastModified()
            if (!force && url !in failedSources && age in 0 until REFRESH_MS && previous.isNotEmpty()) {
                // Already represented in the accepted snapshot.
            } else {
                runCatching {
                    val body = fetchText(url)
                    currentCoroutineContext().ensureActive()
                    require(!body.contains("#EXT-X-", ignoreCase = true)) { "Expected a station catalog, received HLS" }
                    val stations = M3uParser.parse(body, url)
                    require(stations.isNotEmpty()) { "No valid stations; keeping saved playlist" }
                    // Reserve all other last-good sources before writing this replacement.
                    val next = CatalogLimits.commitReplacement(accepted, url, stations) {
                    val output = file.startWrite()
                    try {
                        output.write(body.toByteArray(Charsets.UTF_8))
                        file.finishWrite(output)
                    } catch (e: Exception) {
                        file.failWrite(output)
                        throw e
                    }
                    }
                    synchronized(sourceLock) { parsedCatalogs[url] = stations }
                    next
                }.onSuccess { accepted = it }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        failures += url
                        Log.w(TAG, "Playlist refresh failed (${it.javaClass.simpleName}); retaining saved stations")
                    }
            }
        }
        synchronized(sourceLock) {
            snapshotFavorites(allIncludingHidden())
            val currentSources = playlists()
            val currentUrls = currentSources.map { it.url }
            val catalogs = if (currentUrls == urls) accepted else loadCatalogs(currentSources)
            val updated = builtIn + catalogs.values.flatten()
            parsedCatalogs.keys.retainAll(currentUrls.toSet())
            activeCatalogs = catalogs
            if (cache != updated || currentUrls != userLoadedFor) {
                retiredStations=(cache.map(::stable)+retiredStations).distinctBy { it.id }.take(512)
                cache = updated
                rememberIds(updated + prefs.discovered)
                revision.value += 1
            }
            userLoadedFor = currentUrls
            failedSources = failures.intersect(currentUrls.toSet())
        }
      }
    }

    private fun readBuiltIn(): List<Station> = runCatching {
        val json = appContext.assets.open("stations.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            // A station provides either a single "stream" or a list of "streams".
            // Both forms are valid - most stations have just one address.
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

            Station(
                id = o.getString("id"),
                name = o.getString("name"),
                genre = o.optString("genre", "Inne"),
                stream = variants.firstOrNull()?.url ?: o.getString("stream"),
                streams = variants,
                // "logo" is a built-in resource (for logo_placeholder etc.), "logoUrl"
                // is the station's remote artwork - we don't distribute stations'
                // own logos in the repo, we just link to their servers.
                logo = o.optString("logo").ifBlank { null },
                logoUrl = o.optString("logoUrl").ifBlank { null },
                standbyArtUrl = o.optString("standbyArt").ifBlank { null }
            )
        }
    }.getOrElse {
        Log.e(TAG, "Nie udalo sie wczytac stations.json", it)
        emptyList()
    }

    /**
     * Replaces the address with the one the user actually wants to listen to.
     *
     * Order: a custom address entered manually, then the selected variant, and
     * if there was no selection - the variant with the highest bitrate. This
     * way the rest of the app (the player, Android Auto) still just sees
     * `station.stream` and doesn't need to know anything about variants.
     */
    private fun resolve(source: Station): Station {
        val station = OfficialStationNames.correct(source)
        prefs.customStream(station.id)?.let { return station.copy(stream = it) }
        val variants = station.variants()
        val chosen = prefs.selectedStream(station.id)
        val variant = variants.firstOrNull { it.url == chosen }
            ?: variants.maxByOrNull { it.kbps }
            ?: return station
        return station.copy(stream = variant.url)
    }

    private fun fetchText(url: String): String {
        return net.mspanc.twinsenradio.net.SafeHttp.getText(url,CatalogLimits.MAX_BYTES,USER_AGENT,15_000,
            allowHttp=net.mspanc.twinsenradio.BuildConfig.DEBUG)
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("ł", "l")

    companion object {
        private const val TAG = "StationRepository"
        private const val REFRESH_MS = 15 * 60 * 1_000L
        const val USER_AGENT = "StreamingRadio/0.3 (Android; Impostor Labs)"

        @Volatile
        private var instance: StationRepository? = null

        fun get(context: Context): StationRepository =
            instance ?: synchronized(this) {
                instance ?: StationRepository(context.applicationContext).also { instance = it }
            }
    }
}

