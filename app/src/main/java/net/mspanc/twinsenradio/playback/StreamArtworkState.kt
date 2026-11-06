package net.mspanc.twinsenradio.playback

/** Associates optional ICY artwork with its title, never with a lagging API track. */
internal class StreamArtworkState {
    private var title: String? = null
    private var url: String? = null
    private var artworkSeen = false

    fun update(update: IcyArtworkParser.Update): Boolean {
        val previousTitle = title
        val previousUrl = url
        val previousArtworkSeen = artworkSeen
        if (update.hasTitle && !same(title, update.title)) {
            title = update.title
            url = null
            artworkSeen = false
        }
        if (update.hasArtwork) {
            url = update.artworkUrl?.takeIf { it.isNotBlank() }
            artworkSeen = true
        }
        return previousTitle != title || previousUrl != url || previousArtworkSeen != artworkSeen
    }

    fun matches(raw: String?): Boolean = !title.isNullOrBlank() && same(title, raw)
    fun urlFor(raw: String?): String? = if (matches(raw)) url else null
    fun clear() { title = null; url = null; artworkSeen = false }

    private fun same(first: String?, second: String?): Boolean =
        first?.trim()?.equals(second?.trim(), ignoreCase = true) == true
}
