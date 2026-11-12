// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.playback.LogoProvider
import net.mspanc.twinsenradio.BuildConfig
import net.mspanc.twinsenradio.net.SafeHttp
import net.mspanc.twinsenradio.playback.ArtworkAccessPolicy
import net.mspanc.twinsenradio.playback.ArtworkWorkScheduler
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import androidx.core.content.ContextCompat
import net.mspanc.twinsenradio.data.Prefs

/**
 * Loads cover art (or, failing that, a station logo) into an ImageView.
 * Deliberately without an external library - there's only one need: fetch a
 * single image over HTTP and show it, with a simple cache.
 */
object ArtworkLoader {

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    internal fun cachedImage(context: Context, uri: Uri?): Drawable? =
        uri?.let { cache.get(it.toString()) }?.let { BitmapDrawable(context.resources, it) }

    internal fun hasCachedImage(uri: Uri): Boolean = cache.get(uri.toString()) != null

    internal suspend fun loadStationPreview(context: Context, uri: Uri): Drawable? {
        cachedImage(context, uri)?.let { return it }
        val bitmap = withContext(ArtworkWorkScheduler.dispatcher) {
            load(context.applicationContext, uri)
        } ?: return null
        if (uri.scheme != "file") cache.put(uri.toString(), bitmap)
        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * @param coverUri track cover art from session metadata; null or non-http(s)
     *   means we fall through to [logoUri] instead.
     * @param logoUri the station's own logo - a custom one (file://) is decoded
     *   on the spot; a remote one (http(s)) is fetched the same way cover art
     *   is. Shown when there is no cover, or the cover can't be fetched. This is what's shown whenever there's
     *   no cover art - previously callers only passed a static resource here,
     *   so a station with a custom or remote logo but no cover art showed the
     *   bare placeholder instead of its own logo.
     * @param fallbackRes shown when there's neither cover art nor any logo.
     */
    /**
     * @param drawn a picture made on the spot - the clock face - which beats
     *   everything else and needs no loading at all.
     * @param muted the picture faded back, which means "not playing" - the
     *   logo before play, for a station with no picture of its own for that
     *   moment. Never while something plays.
     */
    fun into(
        scope: LifecycleCoroutineScope,
        coverUri: Uri?,
        logoUri: Uri?,
        fallbackRes: Int,
        target: ImageView,
        drawn: Bitmap? = null,
        muted: Boolean = false,
        blank: Boolean = false,
        animate: Boolean = false,
        slideDirection: Int = 0,
        fallbackDrawable: Drawable? = null,
        showFallbackWhileLoading: Boolean = false
    ) {
        fun show(key: String, drawable: Drawable, transition: Boolean = animate) {
            if (target.getTag(DISPLAY_KEY) == key && target.drawable != null) return
            target.setTag(DISPLAY_KEY, key)
            if (transition && slideDirection != 0 && target.width > 0) {
                val distance = target.width.toFloat() * slideDirection
                target.animate().cancel()
                target.animate().translationX(-distance).setDuration(120).withEndAction {
                    if (target.getTag(DISPLAY_KEY) == key) {
                        target.setImageDrawable(drawable)
                        target.translationX = distance
                        target.animate().translationX(0f).setDuration(180).start()
                    }
                }.start()
            } else if (transition) {
                val previous = target.drawable.let {
                    if (it is TransitionDrawable) it.getDrawable(it.numberOfLayers - 1) else it
                } ?: ColorDrawable(android.graphics.Color.TRANSPARENT)
                target.setImageDrawable(TransitionDrawable(arrayOf(previous, drawable)).apply {
                    isCrossFadeEnabled = !showFallbackWhileLoading
                    startTransition(220)
                })
            } else {
                target.animate().cancel()
                target.translationX = 0f
                target.setImageDrawable(drawable)
            }
        }
        target.alpha = if (muted) LogoProvider.MUTED_ALPHA else 1f
        if (blank) {
            // The picture isn't decided yet (see PlaybackStatusBus.join):
            // an empty slot now, the right picture once - not a logo first.
            invalidateRequest(target)
            target.setTag(DISPLAY_KEY, null)
            target.setImageDrawable(null)
            return
        }
        if (drawn != null) {
            // A cover still on its way must not land on top of the clock.
            invalidateRequest(target)
            target.setTag(DISPLAY_KEY, null)
            target.setImageBitmap(drawn)
            return
        }

        // Local files use the same bounded background decoder as remote art.
        val fallbackUri = logoUri?.takeIf { it.scheme in listOf("file", "content", "http", "https") }
            ?.takeUnless { it.scheme in listOf("http", "https") && !Prefs(target.context).onlineArtworkEnabled }
        var localLogo: Bitmap? = null

        // The logo, or the bare placeholder - for when there is nothing better,
        // and only then. It used to go up first as a "resting image" under every
        // download, so a cover arriving after play was preceded by the logo for
        // the half second the download took - exactly the flash the empty slot
        // after play exists to prevent (seen 30.10.2026 on the phone, frame by
        // frame: empty, logo, cover).
        fun resting(transition: Boolean = animate) {
            if (localLogo != null) show(logoUri.toString(), BitmapDrawable(target.resources, localLogo), transition)
            else if (fallbackDrawable != null) show("fallback:${fallbackDrawable}", fallbackDrawable, transition)
            else ContextCompat.getDrawable(target.context, fallbackRes)?.let { show("res:$fallbackRes", it, transition) }
        }

        // What has to be fetched: the presenter's photo (ours already, behind
        // LogoProvider - the same address the head unit gets), the cover, or a
        // remote station logo.
        val candidate = coverUri?.takeIf { it.scheme in listOf("content", "http", "https") }
            ?: logoUri?.takeIf { it.scheme in listOf("file", "content", "http", "https") }
        val key = candidate?.takeUnless {
            it.scheme in listOf("http", "https") && !Prefs(target.context).onlineArtworkEnabled
        } ?: fallbackUri
        if (key == null) {
            invalidateRequest(target)
            resting(transition = animate && !showFallbackWhileLoading)
            return
        }
        // A favorite/order update must not re-open the same provider image,
        // or enqueue another copy of a request that is already in flight.
        if (key.scheme != "file" && target.getTag(DISPLAY_KEY) == key.toString()) {
            invalidateRequest(target) // Invalidate any request for a different image.
            return
        }
        if (target.getTag(TAG_KEY) == key.toString()) return
        (if (key.scheme == "file") null else cache.get(key.toString()))?.let {
            invalidateRequest(target)
            show(key.toString(), BitmapDrawable(target.resources, it),
                transition = animate && (!showFallbackWhileLoading || target.drawable != null))
            return
        }

        if (showFallbackWhileLoading &&
            (target.drawable == null || target.getTag(DISPLAY_KEY) != fallbackUri?.toString())) {
            localLogo = fallbackUri?.takeUnless { it.scheme == "file" }?.let { cache.get(it.toString()) }
            resting(transition = false)
        }
        val requestToken = Any()
        target.setTag(TAG_KEY, key.toString())
        target.setTag(REQUEST_TOKEN_KEY, requestToken)
        val context = target.context.applicationContext
        scope.launch {
            val bitmap = withContext(ArtworkWorkScheduler.dispatcher) {
                load(context, key).also { bitmap ->
                    // AAOS station logos use content URIs, not just file URIs.
                    if (bitmap == null && fallbackUri != null && fallbackUri != key) {
                        localLogo = (if (fallbackUri.scheme == "file") null else cache.get(fallbackUri.toString()))
                            ?: load(context, fallbackUri)
                        localLogo?.let { if (fallbackUri.scheme != "file") cache.put(fallbackUri.toString(), it) }
                    }
                }
            }
            // the track (or station) may have changed in the meantime
            if (target.getTag(TAG_KEY) != key.toString() ||
                target.getTag(REQUEST_TOKEN_KEY) !== requestToken
            ) return@launch
            invalidateRequest(target)
            if (bitmap != null) {
                if (key.scheme != "file") cache.put(key.toString(), bitmap)
                // A custom logo can change while keeping its file URI.
                if (key.scheme == "file") target.setTag(DISPLAY_KEY, null)
                show(key.toString(), BitmapDrawable(target.resources, bitmap))
            } else {
                resting()
            }
        }
    }

    private fun load(context: Context, uri: Uri): Bitmap? = runCatching {
        when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)?.use { input ->
                ArtworkAccessPolicy.readBounded(input)?.let(::decodeBounded)
            }
            "file" -> java.io.File(uri.path ?: return@runCatching null).inputStream().use { input ->
                ArtworkAccessPolicy.readBounded(input)?.let(::decodeBounded)
            }
            "http", "https" -> fetch(uri.toString())
            else -> null
        }
    }.getOrNull()

    private fun fetch(url: String): Bitmap? = runCatching {
        decodeBounded(SafeHttp.getBytes(url, ArtworkAccessPolicy.MAX_BYTES,
            StationRepository.USER_AGENT, allowHttp = BuildConfig.DEBUG))
    }.getOrNull()

    /** Inspect dimensions before allocating pixels; every caller already bounds bytes. */
    internal fun decodeBounded(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || bytes.size > ArtworkAccessPolicy.MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = ArtworkAccessPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
            ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (bitmap.width > ArtworkAccessPolicy.MAX_DIMENSION ||
            bitmap.height > ArtworkAccessPolicy.MAX_DIMENSION) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private val TAG_KEY = "artwork_url".hashCode()
    private val DISPLAY_KEY = "artwork_displayed".hashCode()
    private val REQUEST_TOKEN_KEY = "artwork_request_token".hashCode()

    private fun invalidateRequest(target: ImageView) {
        target.setTag(TAG_KEY, null)
        target.setTag(REQUEST_TOKEN_KEY, null)
    }
}
