package net.mspanc.twinsenradio.playback

/** Brief refills stay quiet; the service owns the prolonged-connection grace. */
object PlayerConnectionLabel {
    fun text(status: PlaybackStatusBus.Status, wantsToPlay: Boolean, playing: Boolean): String? {
        if (!wantsToPlay) return null
        if (playing) return null
        return when (status) {
            PlaybackStatusBus.Status.CONNECTING -> "Connecting…"
            PlaybackStatusBus.Status.RECONNECTING -> "Reconnecting…"
            PlaybackStatusBus.Status.WAITING_FOR_NETWORK -> "Waiting for network…"
            PlaybackStatusBus.Status.STATION_UNREACHABLE -> "Station unavailable"
            else -> null
        }
    }
}

