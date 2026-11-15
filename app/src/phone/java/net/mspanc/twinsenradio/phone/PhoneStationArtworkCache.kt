package net.mspanc.twinsenradio.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.LifecycleCoroutineScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.playback.ArtworkAccessPolicy
import net.mspanc.twinsenradio.playback.ArtworkWorkScheduler
import net.mspanc.twinsenradio.ui.ArtworkLoader

/** Warm station logos ahead of RecyclerView entry, with a small restart-persistent cache. */
internal class PhoneStationArtworkCache(
    context: Context,
    private val scope: LifecycleCoroutineScope
) {
    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val directory = File(appContext.cacheDir, "phone-station-artwork").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val inFlight = mutableMapOf<String, Uri>()
    private var requested = emptyList<Uri>()

    /** Disk reads happen in [restore], away from binding and the UI thread. */
    fun drawable(uri: Uri?, name: String): Drawable {
        val bitmap = uri?.let { memory.get(it.toString()) }
        return bitmap?.let { BitmapDrawable(appContext.resources, it) }
            ?: PhoneStationArtwork(name)
    }

    /** Restore a cached thumbnail before the first frame that displays its row. */
    suspend fun restore(uri: Uri?) {
        if (uri == null || !allowed(uri)) return
        val key = uri.toString()
        if (memory.get(key) != null) return
        val bitmap = withContext(ArtworkWorkScheduler.dispatcher) {
            readDisk(key) ?: when (uri.scheme) {
                "file", "content" -> loadAndCache(uri, key)
                "http", "https" -> loadExistingMemory(uri, key)
                else -> null
            }
        } ?: return
        memory.put(key, bitmap)
    }

    /** Replace stale prefetch requests with the current viewport's nearest rows. */
    fun prefetch(window: List<Uri>) {
        requested = window.distinct().filter(::allowed)
        launchPending()
    }

    suspend fun waitForInitialRemoteArt(window: List<Uri>) {
        val remote = window.filter { isRemote(it) && memory.get(it.toString()) == null }.take(10)
        prefetch(remote)
        withTimeoutOrNull(800) {
            while (remote.any { uri ->
                val key = uri.toString()
                memory.get(key) == null && (uri in requested || key in inFlight)
            }) delay(16)
        }
    }

    private fun launchPending() {
        while (inFlight.size < MAX_ACTIVE) {
            val remoteActive = inFlight.values.any(::isRemote)
            val uri = requested.firstOrNull {
                val key = it.toString()
                key !in inFlight && memory.get(key) == null && (!isRemote(it) || !remoteActive)
            } ?: return
            val key = uri.toString()
            requested = requested.filterNot { it == uri }
            inFlight[key] = uri
            scope.launch {
                try {
                    val bitmap = withContext(ArtworkWorkScheduler.dispatcher) {
                        readDisk(key) ?: loadAndCache(uri, key)
                    }
                    if (bitmap != null) memory.put(key, bitmap)
                } finally {
                    inFlight.remove(key)
                    if (isActive) {
                        launchPending()
                    }
                }
            }
        }
    }

    private fun isRemote(uri: Uri): Boolean = uri.scheme == "http" || uri.scheme == "https"


    private suspend fun loadAndCache(uri: Uri, key: String): Bitmap? {
        val loaded = ArtworkLoader.loadStationPreview(appContext, uri) as? BitmapDrawable
            ?: return null
        val source = loaded.bitmap
        val thumb = thumbnail(source)
        if (uri.scheme != "file") persist(key, thumb)
        return thumb
    }

    private fun loadExistingMemory(uri: Uri, key: String): Bitmap? {
        val cached = ArtworkLoader.cachedImage(appContext, uri) as? BitmapDrawable ?: return null
        val thumb = thumbnail(cached.bitmap)
        persist(key, thumb)
        return thumb
    }

    private fun allowed(uri: Uri): Boolean = uri.scheme in setOf("file", "content") ||
        (uri.scheme in setOf("http", "https") && prefs.onlineArtworkEnabled)

    private fun file(key: String) = File(directory, digest(key) + ".img")

    private fun readDisk(key: String): Bitmap? {
        val cached = file(key)
        if (!cached.isFile || cached.length() !in 1..ArtworkAccessPolicy.MAX_BYTES.toLong()) return null
        val bytes = runCatching { cached.readBytes() }.getOrNull() ?: return null
        val bitmap = ArtworkLoader.decodeBounded(bytes) ?: return null
        return thumbnail(bitmap)
    }

    private fun thumbnail(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= MAX_THUMBNAIL_DIMENSION) return source
        val scale = MAX_THUMBNAIL_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun persist(key: String, bitmap: Bitmap) {
        val bytes = ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return
            output.toByteArray()
        }
        if (bytes.size > MAX_CACHE_ENTRY_BYTES) return
        runCatching {
            val target = file(key)
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) temporary.delete()
            trimDisk()
        }
    }

    private fun trimDisk() {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "img" }
            ?.sortedByDescending(File::lastModified) ?: return
        var total = 0L
        files.forEach { cached ->
            total += cached.length()
            if (total > MAX_DISK_BYTES) cached.delete()
        }
    }

    internal fun cacheFileForTest(uri: Uri) = file(uri.toString())

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_THUMBNAIL_DIMENSION = 160
        const val MAX_CACHE_ENTRY_BYTES = 512 * 1024
        const val MAX_DISK_BYTES = 16L * 1024 * 1024
        const val MAX_ACTIVE = 3
    }
}
