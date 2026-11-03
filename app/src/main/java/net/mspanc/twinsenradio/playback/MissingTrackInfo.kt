package net.mspanc.twinsenradio.playback

object MissingTrackInfo {
    fun text(playing: Boolean, hasTrackTitle: Boolean, hasArtist: Boolean): String? =
        if (playing && !hasTrackTitle && !hasArtist) "No track info received" else null
}
