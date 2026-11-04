package net.mspanc.twinsenradio.playback

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.mspanc.twinsenradio.data.*
import java.io.File
import java.io.ByteArrayOutputStream

/** Local station cards immediately; bounded, conservative directory-logo enrichment in background. */
object StationArtworkStore {
    internal const val MAX_AUTOMATIC_CANDIDATES = 5
    /** Small preview bytes; the full saved logo remains in the bounded disk cache. */
    data class ImageCandidate(val name: String, val url: String, val cacheName: String, val bytes: ByteArray)
    sealed class CandidateSearch {
        data class Found(val images: List<ImageCandidate>) : CandidateSearch()
        object NoResults : CandidateSearch()
        object DirectoryFailed : CandidateSearch()
        object DownloadFailed : CandidateSearch()
        object LowQuality : CandidateSearch()
        object Disabled : CandidateSearch()
    }
    private val _loading = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val loading: kotlinx.coroutines.flow.StateFlow<Set<String>> = _loading
    private val loadingCounts = mutableMapOf<String, Int>()
    private suspend fun <T> whileLoading(ids: Set<String>, block: suspend () -> T): T {
        synchronized(loadingCounts) {
            ids.forEach { loadingCounts[it] = (loadingCounts[it] ?: 0) + 1 }
            _loading.value = loadingCounts.keys.toSet()
        }
        try { return block() } finally {
            synchronized(loadingCounts) {
                ids.forEach { id ->
                    val count = (loadingCounts[id] ?: 1) - 1
                    if (count <= 0) loadingCounts.remove(id) else loadingCounts[id] = count
                }
                _loading.value = loadingCounts.keys.toSet()
            }
        }
    }
    fun hasDownloaded(context: Context, station: Station): Boolean =
        Prefs(context).onlineArtworkEnabled && validDownloaded(context, station)

    fun isGeneric(context: Context, station: Station): Boolean = genericKey(context, key(station))
    private fun genericKey(context: Context, id: String): Boolean =
        context.getSharedPreferences("station_artwork_checks", 0).getBoolean("generic-$id", false)

    /** Explicit local fallback; retain downloaded bytes for later choices. */
    suspend fun useGeneric(context: Context, station: Station): Boolean = withContext(ArtworkWorkScheduler.dispatcher) {
        currentCoroutineContext().ensureActive()
        synchronized(fileLock) {
            val saved = context.getSharedPreferences("station_artwork_checks", 0).edit()
                .putBoolean("generic-${key(station)}", true).putBoolean("manual-${key(station)}", true).commit()
            if (saved) Prefs(context).setCustomLogo(station.id, null)
            saved
        }
    }

    private val mutex = Mutex()
    private val fileLock = Any()
    private val sampleLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val automaticAttempts = AutomaticArtworkAttempts()
    fun beginServiceLaunch() = automaticAttempts.reset()
    private fun dir(context: Context) = File(context.filesDir, "station-art").apply { mkdirs() }
    private fun key(station: Station) = M3uParser.stableKey(station.id)
    private fun validDownloaded(context: Context, station: Station): Boolean {
        if (isGeneric(context, station)) return false
        val file = File(dir(context), key(station) + ".jpg")
        if (!file.isFile) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return StationLogoQuality.accepts(options.outWidth, options.outHeight)
    }
    fun uri(context: Context, station: Station): Uri {
        val image = File(dir(context), key(station) + ".jpg")
        val version = if (isGeneric(context, station)) 1L else if (Prefs(context).onlineArtworkEnabled) image.lastModified().coerceAtLeast(2L) else 0L
        return Uri.Builder().scheme("content").authority(context.packageName + ".logos")
            .appendPath("station").appendPath(key(station)).appendQueryParameter("v", version.toString()).build()
    }
    fun file(context: Context, id: String): File? {
        if (!id.matches(Regex("[a-f0-9]{64}"))) return null
        val imageDir = dir(context)
        val downloaded = File(imageDir, "$id.jpg")
        synchronized(fileLock) {
            if (validDownloadedFile(context, downloaded)) return downloaded
        }

        // Repository traversal stays outside the shared provider lock.
        val repo = StationRepository.get(context)
        val station = (repo.all() + repo.favourites()).firstOrNull { key(it) == id } ?: return null
        val perKeyLock = sampleLocks.computeIfAbsent(id) { Any() }
        synchronized(perKeyLock) {
            synchronized(fileLock) {
                if (validDownloadedFile(context, downloaded)) return downloaded
            }
            val sample = File(imageDir, "$id-sample.jpg")
            if (sample.isFile && sample.length() > 0L) return sample
            val colors = intArrayOf(0xff24576b.toInt(), 0xff5e3d75.toInt(), 0xff744536.toInt(), 0xff285e51.toInt(), 0xff3c4a7a.toInt())
            val color = colors[(id.take(6).toInt(16) % colors.size)]
            val bitmap = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; alpha = 22 }
            canvas.drawCircle(330f, 48f, 200f, paint)
            paint.alpha = 255; paint.textSize = 132f; paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            val initials = station.name.split(Regex("\\s+")).mapNotNull { word -> word.firstOrNull { it.isLetter() } }
                .take(2).joinToString("").uppercase().ifBlank { "FM" }
            canvas.drawText(initials, 192f, 230f, paint)
            val atomic = AtomicFile(sample); val output = atomic.startWrite()
            try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
            finally { bitmap.recycle() }
            return sample
        }
    }

    private fun validDownloadedFile(context: Context, file: File): Boolean {
        if (!Prefs(context).onlineArtworkEnabled || genericKey(context, file.nameWithoutExtension) || !file.isFile) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return StationLogoQuality.accepts(options.outWidth, options.outHeight)
    }
    fun pickLogo(station: Station, found: List<RadioBrowser.Found>): String? {
        val exactStream = found.firstOrNull { result -> station.variants().any { it.url == result.stream } && !result.faviconUrl.isNullOrBlank() }
        if (exactStream != null) return exactStream.faviconUrl
        fun normalized(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
        return found.filter { normalized(it.name) == normalized(station.name) }
            .sortedByDescending { it.votes }.mapNotNull { it.faviconUrl?.takeIf(String::isNotBlank) }.firstOrNull()
    }

    /** Search the existing RadioBrowser catalog and cache up to 20 usable logos. */
    fun candidateUrls(station: Station, logos: List<RadioBrowser.ArtworkLogo>): List<String> {
        val exactStream = logos.firstOrNull { logo -> station.variants().any { it.url == logo.stream } }?.url
        fun normalized(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
        val exactName = logos.filter { normalized(it.name) == normalized(station.name) }
            .sortedByDescending { it.votes }.firstOrNull()?.url
        return (listOfNotNull(exactStream, exactName) + logos.sortedByDescending { it.votes }.map { it.url })
            .filter { it.startsWith("https://") || it.startsWith("http://") }.distinct().take(20)
    }

    suspend fun searchCandidates(context: Context, station: Station, query: String, onDownloading: (() -> Unit)? = null): CandidateSearch = withContext(ArtworkWorkScheduler.dispatcher) {
        if (!Prefs(context).onlineArtworkEnabled) return@withContext CandidateSearch.Disabled
        val term = query.trim().take(120)
        if (term.length < 2) return@withContext CandidateSearch.NoResults
        val search = RadioBrowser.searchArtwork(term, 80, 8_000)
        if (search is RadioBrowser.ArtworkSearchResult.Failure) return@withContext CandidateSearch.DirectoryFailed
        val logos = (search as RadioBrowser.ArtworkSearchResult.Success).logos
        val urls = candidateUrls(station, logos)
        if (urls.isEmpty()) return@withContext CandidateSearch.NoResults
        onDownloading?.invoke()
        val result = mutableListOf<ImageCandidate>()
        var failures = 0
        var lowQuality = 0
        for ((index, url) in urls.withIndex()) {
            currentCoroutineContext().ensureActive()
            // Cooperative breathing room between bounded requests, also for preview jobs.
            if (index > 0) delay(75)
            if (!Prefs(context).onlineArtworkEnabled) return@withContext CandidateSearch.Disabled
            val cached = try { TrackArtworkStore.fetch(context, url) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Throwable) { null }
            if (cached == null) { failures++; continue }
            val fileName = cached.lastPathSegment ?: continue
            val file = TrackArtworkStore.cachedFile(context, fileName) ?: continue
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { failures++; continue }
            if (!StationLogoQuality.accepts(bounds.outWidth, bounds.outHeight)) { lowQuality++; continue }
            val bytes = runCatching { previewBytes(file) }.getOrNull() ?: continue
            val stationName = logos.firstOrNull { it.url == url }?.name ?: station.name
            result += ImageCandidate(stationName, url, fileName, bytes)
        }
        currentCoroutineContext().ensureActive()
        if (result.isNotEmpty()) CandidateSearch.Found(result)
        else if (lowQuality > 0) CandidateSearch.LowQuality
        else if (failures > 0) CandidateSearch.DownloadFailed
        else CandidateSearch.NoResults
    }

    /** <=384px and <=96KiB each: twenty results never retain twenty full logos. */
    private fun previewBytes(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (!StationLogoQuality.accepts(bounds.outWidth, bounds.outHeight)) return null
        val sample = ArtworkAccessPolicy.sampleSize(bounds.outWidth, bounds.outHeight, 384) ?: return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output) || output.size() > 96 * 1024) null
            else output.toByteArray()
        } finally { bitmap.recycle() }
    }

    /** Commit one cached preview as the station logo. Search alone never calls this. */
    suspend fun useCandidate(context: Context, station: Station, candidate: ImageCandidate): Boolean = withContext(ArtworkWorkScheduler.dispatcher) {
        if (!Prefs(context).onlineArtworkEnabled || !candidate.url.startsWith("https://") && !candidate.url.startsWith("http://")) return@withContext false
        if (candidate.cacheName != M3uParser.stableKey(candidate.url) + ".jpg") return@withContext false
        val source = TrackArtworkStore.cachedFile(context, candidate.cacheName)
            ?.takeIf { it.length() in 1..ArtworkAccessPolicy.MAX_BYTES.toLong() } ?: return@withContext false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, options)
        if (!StationLogoQuality.accepts(options.outWidth, options.outHeight)) return@withContext false
        persistSelection(context, station, source, candidate.url, manual = true)
    }

    /** Keep a disk rollback copy until both preference records and cancellation checks pass. */
    private suspend fun persistSelection(
        context: Context, station: Station, source: File, remote: String,
        manual: Boolean = false, automatic: Boolean = false,
    ): Boolean {
        val caller = currentCoroutineContext()
        return withContext(NonCancellable) {
            synchronized(fileLock) {
                caller.ensureActive()
                val prefs = Prefs(context)
                if (!prefs.onlineArtworkEnabled || automatic && isGeneric(context, station)) return@synchronized false
                val state = context.getSharedPreferences("station_artwork_checks", 0)
                val id = key(station)
                if (automatic && state.getBoolean("manual-$id", false) && validDownloaded(context, station)) return@synchronized false
                val target = File(dir(context), "$id.jpg")
                val backup = if (target.isFile) File.createTempFile("$id.", ".rollback", dir(context)) else null
                var retainBackup = false
                try {
                    if (backup != null) target.copyTo(backup, overwrite = true)
                    retainBackup = backup != null
                    val preferenceKeys = setOf("chosen-$id", "generic-$id", "manual-$id")
                    val previous = state.all.filterKeys { it in preferenceKeys }
                    val previousCustom = prefs.customLogo(station.id)
                    val atomic = AtomicFile(target)
                    fun copyImage(file: File, checkCancellation: Boolean) {
                        val output = atomic.startWrite()
                        try {
                            file.inputStream().use { it.copyTo(output) }
                            if (checkCancellation) caller.ensureActive()
                            atomic.finishWrite(output)
                        } catch (failure: Throwable) {
                            atomic.failWrite(output)
                            throw failure
                        }
                    }
                    val saved = ArtworkSelectionTransaction.run(
                        checkActive = { caller.ensureActive() },
                        publishImage = { copyImage(source, true) },
                        commitPreferences = {
                            val edit = state.edit().putString("chosen-$id", remote).putBoolean("generic-$id", false)
                            if (manual) edit.putBoolean("manual-$id", true)
                            val persisted = edit.commit()
                            persisted && (!manual || prefs.setCustomLogoCommitted(station.id, null))
                        },
                        restoreImage = {
                            if (backup != null) copyImage(backup, false) else atomic.delete()
                            retainBackup = false
                        },
                        restorePreferences = {
                            val edit = state.edit()
                            preferenceKeys.forEach { edit.remove(it) }
                            previous.forEach { (name, value) ->
                                when (value) {
                                    is String -> edit.putString(name, value)
                                    is Boolean -> edit.putBoolean(name, value)
                                }
                            }
                            val restored = edit.commit()
                            val customRestored = !manual || prefs.setCustomLogoCommitted(station.id, previousCustom)
                            check(restored && customRestored)
                        },
                    )
                    if (saved) retainBackup = false
                    saved
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                finally { if (!retainBackup) backup?.delete() }
            }
        }
    }
    private suspend fun candidates(station: Station): List<String> {
        for (query in candidateQueries(station.name)) {
            currentCoroutineContext().ensureActive()
            val result = RadioBrowser.searchArtwork(query,40,3000)
            val urls = if(result is RadioBrowser.ArtworkSearchResult.Success) candidateUrls(station,result.logos) else emptyList()
            if (urls.isNotEmpty()) return listOfNotNull(station.logoUrl).plus(urls).distinct()
        }
        return listOfNotNull(station.logoUrl)
    }
    internal fun candidateQueries(stationName: String): List<String> {
        val short = stationName.substringBefore(" - ").trim()
        return listOf(stationName, short, short.removePrefix("Radio "))
            .distinct()
            .filter { it.length >= 2 }
    }
    private suspend fun save(context: Context, station: Station, remote: String, automatic: Boolean = false): Boolean {
        currentCoroutineContext().ensureActive()
        if (!Prefs(context).onlineArtworkEnabled || automatic && isGeneric(context, station)) return false
        val cached = TrackArtworkStore.fetch(context,remote) ?: return false
        val source = TrackArtworkStore.cachedFile(context,cached.lastPathSegment.orEmpty()) ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (!StationLogoQuality.accepts(bounds.outWidth, bounds.outHeight)) return false
        currentCoroutineContext().ensureActive()
        if (!Prefs(context).onlineArtworkEnabled) return false
        return persistSelection(context, station, source, remote, automatic = automatic)
    }

    /** Retain refresh order, but never re-save the URL already selected as a new result. */
    internal fun alternateCandidateIndices(urls: List<String>, chosen: String?, start: Int): List<Int> {
        if (urls.isEmpty()) return emptyList()
        return (0 until urls.size).map { (start.mod(urls.size) + it).mod(urls.size) }
            .filter { urls[it] != chosen }.take(5)
    }

    suspend fun tryNext(context: Context, station: Station): String = withContext(ArtworkWorkScheduler.dispatcher) {
        whileLoading(setOf(station.id)) { mutex.withLock {
            if (!Prefs(context).onlineArtworkEnabled) return@withLock "Online artwork is disabled."
            val urls = candidates(station)
            if (urls.isEmpty()) return@withLock "No station images were found. Your current image is unchanged."
            val state = context.getSharedPreferences("station_artwork_checks",0)
            val chosen = state.getString("chosen-${key(station)}",null)
            val start = state.getInt("next-${key(station)}", (urls.indexOf(chosen)+1).mod(urls.size)).mod(urls.size)
            val alternatives = alternateCandidateIndices(urls, chosen, start)
            if (alternatives.isEmpty()) return@withLock "No different station image is available in the directory. Your current image is unchanged."
            for (index in alternatives) {
                val saved = try { save(context,station,urls[index]) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { false }
                state.edit().putInt("next-${key(station)}", (index+1).mod(urls.size)).commit()
                currentCoroutineContext().ensureActive()
                if (saved) {
                    Prefs(context).setCustomLogo(station.id,null)
                    return@withLock "Saved station image ${index+1} of ${urls.size}. Try again to use another result."
                }
            }
            "The image sources could not be downloaded. Your current image is unchanged."
        } }
    }
    suspend fun preload(context: Context, stations: List<Station>, changed: suspend (Station) -> Unit) = withContext(ArtworkWorkScheduler.dispatcher) {
        val prefs = Prefs(context)
        val checked = context.getSharedPreferences("station_artwork_checks",Context.MODE_PRIVATE)
        if (checked.getInt("search-version",0) != 3) {
            val editor=checked.edit().putInt("search-version",3)
            stations.forEach { editor.putLong(key(it),0) }
            editor.commit()
        }
        for (station in stations) {
            currentCoroutineContext().ensureActive()
            if (!prefs.onlineArtworkEnabled) break
            val id=key(station)
            if (isGeneric(context, station) || validDownloaded(context, station)) continue
            if (!automaticAttempts.begin(id)) continue
            var finished = false
            try {
                delay(75)
                val saved=whileLoading(setOf(station.id)) { mutex.withLock {
                    if (validDownloaded(context,station) || !prefs.onlineArtworkEnabled) false
                    else {
                        var result=false
                        for (remote in automaticCandidateUrls(candidates(station))) {
                            result=runCatching { save(context,station,remote,automatic=true) }.getOrDefault(false)
                            currentCoroutineContext().ensureActive()
                            if (result || !prefs.onlineArtworkEnabled) break
                        }
                        checked.edit().putLong(id,System.currentTimeMillis()).apply()
                        result
                    }
                } }
                if (saved) withContext(Dispatchers.Main) { changed(station) }
                finished = true
            } finally {
                if (finished) automaticAttempts.complete(id)
                else automaticAttempts.release(id)
            }
        }
        val keep=stations.map { key(it) }.toSet()
        dir(context).listFiles()?.filter { it.name.substringBefore('.').removeSuffix("-sample") !in keep }?.forEach { it.delete() }
    }

    internal fun automaticCandidateUrls(urls: List<String>): List<String> =
        urls.distinct().take(MAX_AUTOMATIC_CANDIDATES)
}

internal class AutomaticArtworkAttempts {
    private val attempted = mutableSetOf<String>()
    private val inFlight = mutableSetOf<String>()

    @Synchronized fun begin(id: String): Boolean =
        id !in attempted && inFlight.add(id)

    @Synchronized fun complete(id: String) {
        inFlight.remove(id)
        attempted.add(id)
    }

    @Synchronized fun release(id: String) {
        inFlight.remove(id)
    }

    @Synchronized fun reset() {
        attempted.clear()
        inFlight.clear()
    }
}

