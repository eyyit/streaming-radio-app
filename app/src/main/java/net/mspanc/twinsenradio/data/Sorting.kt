package net.mspanc.twinsenradio.data

/**
 * Ordering of the station list.
 *
 * [DEFAULT] is the order from the file - arranged thematically, so similar
 * stations sit next to each other. [MOST_PLAYED] counts actual plays and,
 * after a few weeks of driving, is usually the most convenient: what you
 * listen to naturally ends up at the top.
 */
enum class StationSort(val label: String) {
    DEFAULT("Kolejność wbudowana"),
    MOST_PLAYED("Najczęściej słuchane"),
    NAME("Alfabetycznie"),
    GENRE("Według gatunku");

    companion object {
        val LABELS get() = entries.map { it.label }
        fun at(index: Int) = entries.getOrElse(index) { DEFAULT }
    }
}

/**
 * Ordering of results from the catalog.
 *
 * [POPULARITY] is the order in which the catalog returns them - by number of
 * votes, which does a decent job of filtering out dead and random entries.
 */
enum class DiscoverSort(val label: String) {
    POPULARITY("Popularność"),
    NAME("Alfabetycznie"),
    BITRATE("Jakość strumienia"),
    COUNTRY("Kraj");

    companion object {
        val LABELS get() = entries.map { it.label }
        fun at(index: Int) = entries.getOrElse(index) { POPULARITY }
    }
}

/**
 * Tracks active playback duration per station on a monotonic clock and
 * flushes elapsed milliseconds when playback pauses, stops, switches
 * stations, or checkpoints.
 */
class ListeningTimeTracker(
    private val onAccumulated: (stationId: String, deltaMs: Long) -> Unit,
    private val nowMs: () -> Long,
    private val minRecordMs: Long = 1_000L
) {
    private var activeStationId: String? = null
    private var startedAtMs: Long = 0L

    @Synchronized
    fun update(stationId: String?): Boolean {
        val normalized = stationId?.takeIf { it.isNotBlank() }
        if (normalized == activeStationId) return false
        val now = nowMs()
        val recorded = flushLocked(now)
        activeStationId = normalized
        startedAtMs = if (normalized != null) now else 0L
        return recorded
    }

    @Synchronized
    fun checkpoint(): Boolean {
        val stationId = activeStationId ?: return false
        val now = nowMs()
        val elapsed = (now - startedAtMs).coerceAtLeast(0L)
        if (elapsed < minRecordMs) return false
        startedAtMs = now
        onAccumulated(stationId, elapsed)
        return true
    }

    @Synchronized
    fun stop(): Boolean = update(null)

    private fun flushLocked(now: Long): Boolean {
        val stationId = activeStationId ?: return false
        val elapsed = (now - startedAtMs).coerceAtLeast(0L)
        if (elapsed < minRecordMs) return false
        onAccumulated(stationId, elapsed)
        return true
    }
}
