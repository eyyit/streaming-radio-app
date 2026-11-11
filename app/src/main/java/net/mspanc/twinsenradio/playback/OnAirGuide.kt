// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.StationRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.temporal.ChronoUnit

/** One entry of a station's programme guide. */
data class OnAirSlot(
    val start: LocalDateTime,
    val name: String,
    /** Who presents it, as the guide writes it; empty for a night of music. */
    val hosts: String,
    /** The full-size picture the guide shows next to it, if any. */
    val imageUrl: String?
)

/** What the dashboard is told about the programme on air right now. */
data class OnAir(
    val name: String,
    val hosts: String,
    /** The presenter's photo, once it is on the phone; null until then. */
    val photoUri: Uri?
)

/**
 * The programme on air - name, presenters, photo - for a station that
 * publishes a programme guide.
 *
 * Radio Nowy Swiat's own app shows this between tracks (30.10.2026, decompiled:
 * `AudioPlayerInfo.radio` falls back to the schedule item and its picture when
 * there is no song). Its source, `/api/Mobile/get_schedule_today`, wants a key
 * baked into that app; the same data is on the public `/ramowka` page, ten
 * days of it, so one fetch covers a long time.
 *
 * Nothing here blocks: [current] answers from what is already known and starts
 * whatever is missing - the guide, a photo - in the background, calling
 * [onChange] when it lands so the dashboard can be redrawn.
 */
class OnAirGuide(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onChange: () -> Unit
) {

    private class Source(val url: String, val base: String)

    private class Fetched(val slots: List<OnAirSlot>, val atMs: Long)

    private val fetched = HashMap<String, Fetched>()
    private var fetching: String? = null
    private var lastAttemptMs = 0L
    private val photosLoading = HashSet<String>()
    private var lastTraced: String? = null

    /**
     * What to show about the programme on [stationId] at [now]; null when the
     * station has no guide, the guide isn't in yet, or the news is on.
     *
     * @param trackPlaying a record is on; the news window only applies to a gap.
     * @param newsMinutes see [net.mspanc.twinsenradio.data.Prefs.newsMinutes].
     */
    fun current(stationId: String, now: LocalDateTime, trackPlaying: Boolean, newsMinutes: Int): OnAir? {
        val source = forStation(stationId) ?: return null
        val known = fetched[stationId]
        val stale = known == null || SystemClock.elapsedRealtime() - known.atMs > REFRESH_MS
        val slot = known?.let { ProgrammeGuidePage.at(it.slots, now) }
        if (slot == null || stale) fetch(stationId, source)
        if (slot == null) return null

        val news = !trackPlaying && ProgrammeGuidePage.isNewsTime(now.toLocalTime(), newsMinutes)
        trace(stationId, slot, news)
        if (news) return null
        return OnAir(slot.name, slot.hosts, slot.imageUrl?.let(::photoUri))
    }

    /** A new station - the next answer is traced even if it reads the same. */
    fun reset() {
        lastTraced = null
    }

    private fun fetch(stationId: String, source: Source) {
        if (fetching != null) return
        // A failed fetch is not retried on every minute tick.
        if (SystemClock.elapsedRealtime() - lastAttemptMs < RETRY_MS && fetched[stationId] == null) return
        fetching = stationId
        lastAttemptMs = SystemClock.elapsedRealtime()
        scope.launch {
            val html = withContext(Dispatchers.IO) { download(source.url)?.toString(Charsets.UTF_8) }
            val slots = html?.let { ProgrammeGuidePage.parse(it, source.base, LocalDate.now()) }.orEmpty()
            fetching = null
            Trace.write("ramowka") {
                put("st", stationId)
                put("pozycji", slots.size)
                put("od", slots.firstOrNull()?.start?.toString())
                put("do", slots.lastOrNull()?.start?.toString())
            }
            if (slots.isEmpty()) return@launch
            fetched[stationId] = Fetched(slots, SystemClock.elapsedRealtime())
            onChange()
        }
    }

    /**
     * The photo's local address, or null while it is still on its way.
     *
     * The guide's own pictures are 150 px thumbnails, soft on a head unit; the
     * originals are up to a couple of megabytes. So the original is fetched
     * once, scaled down to [SquareArt.SIZE] and kept - presenters come back every
     * week, and so does their photo.
     */
    private fun photoUri(url: String): Uri? {
        val file = photoFile(url)
        if (file.exists() && file.length() > 0) return LogoProvider.fileUri(context, "onair-${file.nameWithoutExtension}", file)
        if (photosLoading.add(url)) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { savePhoto(url, file) }
                photosLoading.remove(url)
                if (ok) onChange()
            }
        }
        return null
    }

    private fun photoFile(url: String): File {
        val name = url.substringAfterLast('/').substringBefore('?').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(context.filesDir, "onair").apply { mkdirs() }, name.substringBeforeLast('.') + ".jpg")
    }

    // The originals are wide banners with the face in the middle; the slot on
    // the head unit is square - see SquareArt.
    private fun savePhoto(url: String, file: File): Boolean = SquareArt.save(url, file)

    private fun download(url: String): ByteArray? = runCatching {
        net.mspanc.twinsenradio.net.SafeHttp.getBytes(url,2*1024*1024,StationRepository.USER_AGENT,TIMEOUT_MS)
    }.onFailure { Log.w(TAG, "Programme lookup failed") }.getOrNull()

    /** Every change of programme, and of news against no news, goes into the trace. */
    private fun trace(stationId: String, slot: OnAirSlot, news: Boolean) {
        val key = "$stationId|${slot.start}|$news"
        if (key == lastTraced) return
        lastTraced = key
        Trace.write("audycja") {
            put("st", stationId)
            put("nazwa", slot.name)
            put("prowadzacy", slot.hosts)
            put("od", slot.start.toString())
            put("serwis", news)
        }
    }

    private fun forStation(stationId: String): Source? = when (stationId) {
        "rns" -> Source("https://nowyswiat.online/ramowka", "https://nowyswiat.online/")
        else -> null
    }

    companion object {
        private const val TAG = "OnAirGuide"
        private const val TIMEOUT_MS = 10_000

        /** The guide covers ten days; twice a day is plenty to catch late changes. */
        private const val REFRESH_MS = 12 * 3600_000L

        /** How soon a fetch that failed may be tried again. */
        private const val RETRY_MS = 10 * 60_000L
    }
}

/**
 * Reads RNS's `/ramowka` page. Pure, so it can be checked against the page as
 * it really is.
 *
 * The page carries a strip of days (`<span class="rns-week-switcher-date">
 * 30.10</span>`) and, in the same order, one block per day of entries: start
 * time, programme, presenters, picture. The presenters' block also carries
 * the day's guests and topics after a "|", which are not presenters.
 */
object ProgrammeGuidePage {

    fun parse(html: String, base: String, today: LocalDate): List<OnAirSlot> {
        val days = Regex("""rns-week-switcher-date">\s*(\d{1,2})\.(\d{1,2})\s*<""").findAll(html)
            .map { dateNear(MonthDay.of(it.groupValues[2].toInt(), it.groupValues[1].toInt()), today) }
            .toList()
        val blocks = html.split("rns-switcher-grid-element").drop(1)
        return days.zip(blocks).flatMap { (day, block) ->
            block.split("rns-switcher-single").drop(1).mapNotNull { entry(day, it, base) }
        }.sortedBy { it.start }
    }

    /** The entry on air at [time]: the last one that has started, if it started today or yesterday. */
    fun at(slots: List<OnAirSlot>, time: LocalDateTime): OnAirSlot? =
        slots.lastOrNull { !it.start.isAfter(time) }
            ?.takeIf { ChronoUnit.HOURS.between(it.start, time) < MAX_SLOT_HOURS }

    /** Whether [time] falls in the first [minutes] of an hour - see Prefs.newsMinutes. */
    fun isNewsTime(time: LocalTime, minutes: Int): Boolean = minutes > 0 && time.minute < minutes

    private fun entry(day: LocalDate, html: String, base: String): OnAirSlot? {
        val time = Regex("""rns-switcher-time">\s*(\d{1,2}):(\d{2})""").find(html) ?: return null
        val name = Regex("""rns-switcher-title">(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.let { text(it.groupValues[1]) }
            ?.takeIf { it.isNotEmpty() } ?: return null
        val hosts = Regex("""rns-switcher-names">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.let { text(it.groupValues[1]).substringBefore('|').trim().trimEnd(',') }
            .orEmpty()
        // A programme without a picture of its own gets the site's placeholder
        // (Koncertowe granie, 31.10.2026) - the station's logo says that better.
        val image = Regex("""<img[^>]*src="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.takeUnless { "placeholder" in it }
            ?.let { original(it, base) }
        val start = day.atTime(time.groupValues[1].toInt(), time.groupValues[2].toInt())
        return OnAirSlot(start, name, hosts, image)
    }

    /** "userfiles/team/x-light_thumb_150x150.jpg?ts=1" -> the full picture behind the thumbnail. */
    private fun original(src: String, base: String): String {
        val path = src.substringBefore('?').replace(Regex("""_thumb_\d+x\d+(?=\.\w+$)"""), "")
        return if (path.startsWith("http")) path else base + path.removePrefix("/")
    }

    /** "30.10" in the year that puts it nearest to [today] - the guide spans New Year. */
    private fun dateNear(md: MonthDay, today: LocalDate): LocalDate =
        (today.year - 1..today.year + 1)
            .map { md.atYear(it) }
            .minBy { Math.abs(ChronoUnit.DAYS.between(today, it)) }

    private fun text(html: String): String =
        unescape(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").replace(" ,", ",").trim()

    private fun unescape(s: String): String =
        s.replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    /** Longer than any programme RNS runs (Muzyka do rana is six hours). */
    private const val MAX_SLOT_HOURS = 8L
}
