package net.mspanc.twinsenradio.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.M3uParser
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.StationRepository
import java.io.File

/** Bounded, decoded artwork cache. Hosts read local image bytes instead of fetching catalog URLs. */
object TrackArtworkStore {
    val downloadCalls = java.util.concurrent.atomic.AtomicInteger()
    // Only completed-file publication/eviction takes this lock. Never network or decoding.
    private val cacheLock = Any()
    private fun directory(context: Context) = File(context.cacheDir, "track-art").apply { mkdirs() }

    fun cachedFile(context: Context, name: String): File? {
        if (!name.matches(Regex("[a-f0-9]{64}\\.jpg")) || !Prefs(context).onlineArtworkEnabled) return null
        return File(directory(context), name).takeIf { it.isFile && it.length() > 0 }
    }

    suspend fun fetch(context: Context, url: String, currentTrack: Boolean = false,
        allowedHosts: Set<String>? = null): Uri? = withContext(
        if (currentTrack) ArtworkWorkScheduler.trackDispatcher else ArtworkWorkScheduler.dispatcher
    ) {
        val checkedUrl = try {
            net.mspanc.twinsenradio.net.SafeHttp.checkedUrl(
                url, allowHttp = net.mspanc.twinsenradio.BuildConfig.DEBUG,
                allowedHosts = allowedHosts)
        } catch (_: java.io.IOException) { return@withContext null }
        if (!Prefs(context).onlineArtworkEnabled || checkedUrl.protocol !in listOf("https", "http")) return@withContext null
        // A cache hit must carry the same redirect trust policy as its download.
        val cacheKey = if (allowedHosts == null) url else {
            val policy = allowedHosts.map { it.lowercase(java.util.Locale.ROOT) }
                .distinct().sorted().joinToString("") { "${it.length}:$it" }
            "restricted-artwork-v1\u0000$policy\u0000$url"
        }
        val name = M3uParser.stableKey(cacheKey) + ".jpg"
        val file = File(directory(context), name)
        if (!file.isFile || file.length() == 0L) {
            downloadCalls.incrementAndGet()
                val bytes = try { net.mspanc.twinsenradio.net.SafeHttp.getBytes(url,5*1024*1024,StationRepository.USER_AGENT,
                    allowHttp=net.mspanc.twinsenradio.BuildConfig.DEBUG, allowedHosts=allowedHosts) }
                    catch(_:java.io.IOException) { return@withContext null }
                currentCoroutineContext().ensureActive()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val options = BitmapFactory.Options().apply { inSampleSize = ArtworkAccessPolicy.sampleSize(bounds.outWidth,bounds.outHeight) ?: return@withContext null }
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withContext null
                if(image.width>1024 || image.height>1024) { image.recycle(); return@withContext null }
                val staged = File.createTempFile("pending-", ".tmp", directory(context))
                try {
                    currentCoroutineContext().ensureActive()
                    if (!Prefs(context).onlineArtworkEnabled) return@withContext null
                    staged.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                    currentCoroutineContext().ensureActive()
                    synchronized(cacheLock) {
                        // The two artwork lanes may request the same URL. Publish once,
                        // without making current-track work wait for another download.
                        if (!file.isFile || file.length() == 0L) {
                            val atomic = AtomicFile(file)
                            val output = atomic.startWrite()
                            try {
                                staged.inputStream().use { it.copyTo(output) }
                                atomic.finishWrite(output)
                            } catch (failure: Throwable) {
                                atomic.failWrite(output)
                                throw failure
                            }
                        }
                    }
                } finally { image.recycle(); staged.delete() }
        }
        currentCoroutineContext().ensureActive()
        if (!Prefs(context).onlineArtworkEnabled) return@withContext null
        synchronized(cacheLock) {
            file.setLastModified(System.currentTimeMillis())
            directory(context).listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }
                ?.drop(64)?.filter { it != file }?.forEach { it.delete() }
        }
        Uri.Builder().scheme("content").authority(context.packageName + ".logos").appendPath("track").appendPath(name).build()
    }
}
