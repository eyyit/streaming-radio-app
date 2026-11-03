package net.mspanc.twinsenradio.playback

/** Session-local notice timing; callers supply a monotonic clock in milliseconds. */
class MissingTrackInfoGrace {
    private var station: String? = null
    private var updatedAtMs = 0L
    private var elapsedMs = 0L
    private var counting = false
    private var graceMs = DEFAULT_GRACE_MS

    fun reset() {
        station = null
        updatedAtMs = 0L
        elapsedMs = 0L
        counting = false
        graceMs = DEFAULT_GRACE_MS
    }

    fun setIcyHeaders(metadataIntervalBytes: Long, bitrateBitsPerSecond: Long) {
        graceMs = graceMs(metadataIntervalBytes, bitrateBitsPerSecond)
    }

    fun update(stationId: String?, playing: Boolean, hasTrackTitle: Boolean,
               hasArtist: Boolean, nowMs: Long) {
        if (station != stationId) {
            reset()
            station = stationId
        } else if (counting) {
            elapsedMs = (elapsedMs + (nowMs - updatedAtMs).coerceAtLeast(0L))
                .coerceAtMost(MAX_GRACE_MS)
        }
        if (hasTrackTitle || hasArtist || stationId == null) elapsedMs = 0L
        counting = stationId != null && playing && !hasTrackTitle && !hasArtist
        updatedAtMs = nowMs
    }

    fun text(nowMs: Long): String? {
        val activeMs = elapsedMs + if (counting) {
            (nowMs - updatedAtMs).coerceAtLeast(0L).coerceAtMost(MAX_GRACE_MS)
        } else 0L
        return MissingTrackInfo.text(counting && activeMs >= graceMs, false, false)
    }

    companion object {
        const val DEFAULT_GRACE_MS = 30_000L
        const val MAX_GRACE_MS = 300_000L

        /**
         * A UI heuristic, not an ICY deadline: at least 30 s, or two advertised
         * audio-byte intervals at the advertised bits/s. Bound to five minutes.
         * Media3 IcyHeaders.bitrate is already bits/s, not the wire icy-br kbps.
         * Reject impossible/overflow-sized headers before multiplying.
         */
        fun graceMs(metadataIntervalBytes: Long, bitrateBitsPerSecond: Long): Long {
            if (metadataIntervalBytes !in 1L..Int.MAX_VALUE.toLong() ||
                bitrateBitsPerSecond !in 1L..Int.MAX_VALUE.toLong()) return DEFAULT_GRACE_MS
            val numerator = metadataIntervalBytes * 16_000L
            val twoIntervalsMs = (numerator + bitrateBitsPerSecond - 1L) / bitrateBitsPerSecond
            return twoIntervalsMs.coerceIn(DEFAULT_GRACE_MS, MAX_GRACE_MS)
        }
    }
}
