package net.mspanc.twinsenradio.data

/** Useful identities even for URL-only playlists; never display query strings or credentials. */
object StationText {
    fun fallbackName(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return "Internet station"
        val host = uri.host?.removePrefix("www.") ?: return "Internet station"
        val segment = uri.path.orEmpty().trimEnd('/').substringAfterLast('/')
        val useful = segment.substringBeforeLast('.', segment)
            .takeUnless { it.lowercase() in setOf("", "stream", "live", "listen", "radio", "audio") }
        return if (useful != null) "$host / $useful" else host
    }

    fun subtitle(station: Station): String = listOfNotNull(
        station.genre.takeUnless { it.isBlank() || it == "Playlist" },
        runCatching { java.net.URI(station.stream).host?.removePrefix("www.") }.getOrNull()
    ).distinct().joinToString(" · ")
}
