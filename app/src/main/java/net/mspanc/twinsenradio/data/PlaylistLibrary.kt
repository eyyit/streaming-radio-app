package net.mspanc.twinsenradio.data

/** A user-managed M3U source. [id] is stable across URL edits. */
data class PlaylistSource(
    val id: String,
    val url: String,
    val name: String,
    val stationCount: Int = 0
) {
    val isLocal: Boolean get() = PlaylistLibrary.isLocalSnapshot(url)
}

/** A station in a source or aggregate view, keyed by its exact stream URL. */
data class PlaylistStation(
    val station: Station,
    val memberships: List<String>,
    val detached: Boolean = false
)

/** A local visibility rule keyed by the exact catalog stream URL. */
data class StationExclusion(val station: Station, val removed: Boolean)

/** Shared visibility rules used by persisted repository operations. */
object StationVisibility {
    fun encode(rules: List<StationExclusion>): String {
        val array = org.json.JSONArray()
        rules.forEach { rule -> val s = rule.station
            array.put(org.json.JSONObject().put("id", s.id).put("name", s.name)
                .put("genre", s.genre).put("stream", s.stream)
                .put("logoUrl", s.logoUrl ?: "").put("removed", rule.removed))
        }
        return array.toString()
    }

    fun decode(raw: String): List<StationExclusion> = runCatching {
        val array = org.json.JSONArray(raw)
        (0 until array.length()).map { index -> array.getJSONObject(index).let { o ->
            StationExclusion(Station(o.getString("id"), o.getString("name"),
                o.optString("genre"), o.getString("stream"),
                logoUrl = o.optString("logoUrl").ifBlank { null },
                source = Station.Source.USER_M3U), o.getBoolean("removed"))
        } }
    }.getOrDefault(emptyList())

    fun exclude(rules: List<StationExclusion>, station: Station, removed: Boolean) =
        rules.filterNot { it.station.stream == station.stream } + StationExclusion(station, removed)

    fun restore(rules: List<StationExclusion>, stream: String, sourceStreams: Set<String>) =
        if (stream in sourceStreams) rules.filterNot { it.station.stream == stream } else rules

    fun visible(stations: List<Station>, rules: List<StationExclusion>, all: Boolean): List<Station> {
        val excluded = rules.filter { all || it.removed }.map { it.station.stream }.toSet()
        // Repository IDs are stable for the exact catalog URL even when a
        // station's effective stream resolves to a selected bitrate variant.
        val ids = rules.filter { all || it.removed }.map { it.station.id }.toSet()
        return stations.filterNot { it.stream in excluded || it.id in ids }
    }

    fun favoriteIds(ids: Set<String>, rules: List<StationExclusion>): Set<String> =
        ids - rules.filter { it.removed }.map { it.station.id }.toSet()
}

/** Pure reconciliation and ordering rules shared by the repository and tests. */
object PlaylistLibrary {
    /** Internal cached source only; never accept arbitrary local paths or URIs. */
    const val LOCAL_SNAPSHOT_URL = "local:saved-stations"

    fun isLocalSnapshot(url: String): Boolean = url == LOCAL_SNAPSHOT_URL

    fun remoteSources(sources: List<PlaylistSource>): List<PlaylistSource> =
        sources.filterNot { it.isLocal }

    /** URL-only editor: exact survivors keep identity; local snapshots are untouched. */
    fun reconcileRemoteUrls(current: List<PlaylistSource>, input: List<String>): List<PlaylistSource> {
        val urls = input.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
        urls.forEach {
            CatalogLimits.field(it, CatalogLimits.MAX_URL, "Playlist URL")
            requireRemoteUrl(it)
        }
        require(urls.size + current.count { it.isLocal } <= CatalogLimits.MAX_SOURCES) {
            "At most ${CatalogLimits.MAX_SOURCES} playlists are supported"
        }
        val existing = remoteSources(current).associateBy { it.url }
        val remote = urls.mapIndexed { index, url ->
            existing[url] ?: PlaylistSource("pl_${java.util.UUID.randomUUID()}", url,
                PlaylistNames.fromUrl(url, "Playlist ${index + 1}"))
        }.iterator()
        val result = buildList {
            current.forEach { source ->
                if (source.isLocal) add(source) else if (remote.hasNext()) add(remote.next())
            }
            while (remote.hasNext()) add(remote.next())
        }
        CatalogLimits.sources(result)
        return result
    }

    fun parseCachedPlaylist(body: String, source: String): List<Station> {
        CatalogLimits.sources(listOf(PlaylistSource("cached", source, "Cached")))
        require(!body.contains("#EXT-X-", ignoreCase = true)) { "Saved playlist is HLS" }
        return M3uParser.parse(body, source).also {
            require(it.isNotEmpty()) { "No playable station entries" }
        }
    }

    /** User-entered URLs stay HTTPS-only, including in local debug builds. */
    fun requireRemoteUrl(url: String) {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        require(uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank() && uri?.userInfo == null) {
            "Enter a valid HTTPS playlist URL, then try again."
        }
    }

    /** A tvg-id can be reused after a stream URL changes. Do not merge those streams. */
    fun stationId(stream: String, preferred: String, known: Map<String, String>,
                  usedIds: Set<String> = known.values.toSet()): String =
        known[stream] ?: preferred.takeIf { it.isNotBlank() && it !in usedIds }
            ?: "stream_${M3uParser.stableKey(stream)}"

    fun favorites(active: List<Station>, saved: List<Station>, ids: Set<String>): List<Station> =
        (active + saved).filter { it.id in ids }.distinctBy { it.id }

    fun aggregate(sources: List<Pair<PlaylistSource, List<Station>>>): List<PlaylistStation> {
        val entries = linkedMapOf<String, Pair<Station, MutableList<String>>>()
        sources.forEach { (source, stations) ->
            stations.forEach { station ->
                val entry = entries.getOrPut(station.stream) { station to mutableListOf() }
                if (source.id !in entry.second) entry.second += source.id
            }
        }
        return entries.values.map { (station, owners) -> PlaylistStation(station, owners) }
    }

    fun reconcileOrder(saved: List<String>, current: List<String>, discovered: List<String>): List<String> {
        val available = current.toSet()
        return (saved.filter { it in available } + discovered.filter { it in available })
            .distinct()
    }

    fun move(order: List<String>, id: String, delta: Int): List<String> {
        require(delta == -1 || delta == 1) { "delta must be -1 or 1" }
        val from = order.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in order.indices) return order
        return order.toMutableList().also {
            val item = it.removeAt(from)
            it.add(to, item)
        }
    }
}
