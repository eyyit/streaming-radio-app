package net.mspanc.twinsenradio.playback

/** Entry-only debounce: audible playback and Pause always clear the footer immediately. */
class AudioWaitLabel {
    private var station: String? = null
    private var hasPlayed = false
    private var waitingSince: Long? = null

    fun text(
        stationId: String?, wantsToPlay: Boolean, playing: Boolean,
        suppressed: Boolean, status: PlaybackStatusBus.Status, nowMs: Long,
    ): String? {
        if (station != stationId) {
            station = stationId
            hasPlayed = false
            waitingSince = null
        }
        if (stationId == null || !wantsToPlay) {
            hasPlayed = false
            waitingSince = null
            return null
        }
        if (playing) {
            hasPlayed = true
            waitingSince = null
            return null
        }
        // Audio focus/route suppression is not evidence of stream buffering.
        if (suppressed) {
            waitingSince = null
            return null
        }
        if (status == PlaybackStatusBus.Status.STATION_UNREACHABLE) return "Station unavailable"
        val since = waitingSince ?: nowMs.also { waitingSince = it }
        // UX heuristics, not stream-protocol deadlines; never delay clearing.
        if (nowMs - since < if (hasPlayed) 700L else 400L) return null
        return when (status) {
            PlaybackStatusBus.Status.WAITING_FOR_NETWORK -> "Waiting for network…"
            PlaybackStatusBus.Status.RECONNECTING -> "Reconnecting…"
            else -> if (hasPlayed) "Buffering audio…" else "Starting audio…"
        }
    }
}
