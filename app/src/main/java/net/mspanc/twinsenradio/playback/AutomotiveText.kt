package net.mspanc.twinsenradio.playback

/** Shared text contract for native playback, queues and media-session/cluster consumers. */
object AutomotiveText {
    data class Lines(val title: String, val subtitle: String, val description: String)

    fun playback(station: String, title: String?, artist: String?, album: String?, quality: StreamQuality?): Lines {
        val track = title?.trim().orEmpty()
        val performer = artist?.trim().orEmpty()
        return Lines(
            title = track.ifBlank { station },
            subtitle = listOf(performer, track, quality?.compactLabel().orEmpty())
                .filter { it.isNotBlank() }.distinct().joinToString(" · "),
            description = listOf(station, album.orEmpty(), quality?.technicalLabel().orEmpty())
                .filter { it.isNotBlank() }.distinct().joinToString(" · ")
        )
    }
}
