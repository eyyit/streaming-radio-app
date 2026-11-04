package net.mspanc.twinsenradio.playback

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A record of a real drive: what the station sent us, what we sent to the car,
 * and when.
 *
 * The reason it exists: the two screens that matter are in the car, and neither
 * can be read from a desk. logcat only helps while a cable is attached, and its
 * buffer is a few minutes deep - an hour of driving is gone by the time anyone
 * looks. So each event is appended to a file the phone keeps, in a form that can
 * be analysed later without guessing: one JSON object per line (JSONL), every
 * line carrying its own timestamps and the station it belongs to.
 *
 * Two clocks per line, because they answer different questions. `t` is the wall
 * clock, which is what a person remembers ("somewhere after the junction"), and
 * `up` is [SystemClock.elapsedRealtime], which is monotonic - the only one that
 * can be subtracted safely, since the phone corrects its wall clock over the
 * network while we drive.
 *
 * Pull it off the phone with:
 *
 *     adb pull /sdcard/Android/data/net.mspanc.twinsenradio/files/trace
 */
object Trace {

    private const val TAG = "Trace"
    private const val DIR = "trace"

    /**
     * One run stops growing here. A phone left playing for a week must not fill
     * itself; 8 MB is a few hundred thousand events, far more than any drive.
     */
    private const val FILE_LIMIT_BYTES = 8L * 1024 * 1024

    /** How much the whole directory may hold before the oldest runs are dropped. */
    private const val DIR_LIMIT_BYTES = 32L * 1024 * 1024

    // A single thread, so lines land in the order they happened and no caller
    // ever waits on a disk write - not the main thread, and not the ExoPlayer
    // callback that reports ICY.
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "trace").apply { isDaemon = true }
    }

    private val wallClock = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private val fileClock = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Volatile
    private var file: File? = null

    @Volatile
    private var written = 0L

    @Volatile
    private var capped = false

    /** Tagged onto every line, so the analysis doesn't have to track it itself. */
    @Volatile
    private var station: String? = null

    val isOpen: Boolean get() = file != null

    /** Where the files live. Safe to call whether or not the trace is running. */
    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR)

    /** Total size and file count, for the line under the switch in Options. */
    fun size(context: Context): Pair<Int, Long> {
        val files = dir(context).listFiles()?.filter { it.isFile }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    /**
     * Starts a new file for this run of the service. Called again while already
     * open, it does nothing - the run is the unit, not the call.
     */
    fun open(context: Context) {
        if (file != null) return
        runCatching {
            val dir = dir(context).apply { mkdirs() }
            prune(dir)
            val f = File(dir, "trace-${fileClock.format(Date())}.jsonl")
            file = f
            written = f.length()
            capped = false
            Log.i(TAG, "slad zapisuje sie do ${f.absolutePath}")
            write("run") {
                put("app", versionOf(context))
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android", Build.VERSION.RELEASE)
                put("file", f.name)
            }
        }.onFailure { Log.w(TAG, "nie udalo sie otworzyc sladu", it) }
    }

    fun close() {
        val f = file ?: return
        write("end") { put("bytes", written) }
        file = null
        Log.i(TAG, "slad zamkniety: ${f.name}, ${written / 1024} KB")
    }

    /** Deletes everything written so far. The switch in Options offers it. */
    fun clear(context: Context) {
        val open = file
        io.execute {
            runCatching {
                dir(context).listFiles()?.forEach { if (it != open) it.delete() }
            }.onFailure { Log.w(TAG, "nie udalo sie wyczyscic sladu", it) }
        }
    }

    /** The station every following line belongs to, until the next change. */
    fun station(id: String?) {
        station = id
    }

    /**
     * Appends one event.
     *
     * @param event short name of what happened - the analysis groups by it
     * @param fields whatever that kind of event carries; a null value is simply
     *   absent from the line rather than written as null
     */
    fun write(event: String, fields: JSONObject.() -> Unit = {}) {
        val f = file ?: return
        if (capped) return

        // Built here, on the caller's thread, because the values it reads are
        // the caller's and may have moved on by the time the writer runs.
        val line = runCatching {
            DiagnosticUrls.redact(JSONObject().apply {
                put("t", wallClock.format(Date()))
                put("up", SystemClock.elapsedRealtime())
                put("e", event)
                put("st", station)
                fields()
            }).toString()
        }.getOrElse { return }

        io.execute {
            runCatching {
                if (written > FILE_LIMIT_BYTES) {
                    capped = true
                    f.appendText("""{"e":"capped"}""" + "\n")
                    Log.w(TAG, "slad osiagnal limit ${FILE_LIMIT_BYTES / 1024 / 1024} MB - przestaje pisac")
                    return@execute
                }
                f.appendText(line + "\n")
                written += line.length + 1
            }.onFailure { Log.w(TAG, "nie udalo sie dopisac do sladu", it) }
        }
    }

    /**
     * Keeps the directory under [DIR_LIMIT_BYTES] by dropping the oldest runs.
     * A drive that finds no space left would be a drive with no record of it.
     */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DIR_LIMIT_BYTES) break
            total -= f.length()
            if (f.delete()) Log.i(TAG, "usuwam stary slad ${f.name}")
        }
    }

    private fun versionOf(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("?")
}
