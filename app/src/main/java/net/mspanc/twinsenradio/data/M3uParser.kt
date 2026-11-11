// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.data

/**
 * A minimal parser for extended M3U (#EXTM3U / #EXTINF). Extracts the name, URL,
 * and the tvg-logo and group-title attributes, which in practice carry the logo and genre.
 */
object M3uParser {

    /** IDs depend on source and station identity, never on playlist position. */
    fun stableKey(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val result = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val number = byte.toInt() and 255
            result[index * 2] = hex[number ushr 4]
            result[index * 2 + 1] = hex[number and 15]
        }
        return String(result)
    }

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(body: String, sourceTag: String): List<Station> {
        CatalogLimits.body(body)
        CatalogLimits.field(sourceTag, CatalogLimits.MAX_URL, "Playlist URL")
        val out = mutableListOf<Station>()
        var entryCount = 0
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingId: String? = null
        var section: String? = null

        body.lineSequence().forEach { rawLine ->
            CatalogLimits.field(rawLine, CatalogLimits.MAX_LINE, "Playlist line")
            val line = rawLine.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit

                line.startsWith("# SECTION:", ignoreCase = true) -> {
                    section = line.substringAfter(':').trim().ifBlank { null }
                    CatalogLimits.field(section, CatalogLimits.MAX_GROUP, "Station group")
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    var quoted = false
                    val comma = line.indexOfFirst { c ->
                        if (c == '"') quoted = !quoted
                        c == ',' && !quoted
                    }
                    val afterComma = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    pendingName = afterComma.ifBlank { null }
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    if (pendingName == null) pendingName = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                    pendingLogo = attrs["tvg-logo"]?.ifBlank { null }
                    pendingGroup = attrs["group-title"]?.ifBlank { null }
                    pendingId = attrs["tvg-id"]?.ifBlank { null }
                    CatalogLimits.field(pendingName, CatalogLimits.MAX_NAME, "Station name")
                    CatalogLimits.field(attrs["tvg-name"], CatalogLimits.MAX_NAME, "Station name")
                    CatalogLimits.field(pendingLogo, CatalogLimits.MAX_URL, "Artwork URL")
                    CatalogLimits.field(pendingGroup, CatalogLimits.MAX_GROUP, "Station group")
                    CatalogLimits.field(pendingId, CatalogLimits.MAX_ID, "Station ID")
                }

                line.startsWith("#") -> Unit

                else -> {
                    CatalogLimits.field(line, CatalogLimits.MAX_URL, "Station URL")
                    val uri = runCatching { java.net.URI(line) }.getOrNull()
                    if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
                        pendingName = null
                        pendingLogo = null
                        pendingGroup = null
                        pendingId = null
                        return@forEach
                    }
                    val name = pendingName ?: StationText.fallbackName(line)
                    CatalogLimits.field(name, CatalogLimits.MAX_NAME, "Station name")
                    require(++entryCount <= CatalogLimits.MAX_STATIONS_PER_SOURCE) {
                        "Playlist exceeds ${CatalogLimits.MAX_STATIONS_PER_SOURCE} station entries; keeping saved playlist"
                    }
                    out += Station(
                        id = "u_${stableKey(sourceTag + "\n" + (pendingId ?: line))}",
                        name = name,
                        genre = pendingGroup ?: section ?: "Playlist",
                        stream = line,
                        logoUrl = pendingLogo,
                        source = Station.Source.USER_M3U
                    )
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                    pendingId = null
                }
            }
        }
        return out.distinctBy { it.id }
    }
}
