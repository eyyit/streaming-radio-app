// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.net.SafeHttp
import java.io.File

/**
 * Stable artwork addresses readable by Android Auto and native AAOS media hosts.
 * Only app-registered resources/files/sources are served; callers cannot supply
 * paths, resource IDs or remote URLs. Registrations survive process restart.
 */
class LogoProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = context?.let { context ->
        ArtworkAccessPolicy.route(uri.toString(), authority(context))?.let {
            if (it.first in setOf("track", "station") ||
                (it.first == "custom" && registration(context, "custom", it.second)
                    ?.substringAfterLast('.')?.lowercase() in setOf("jpg", "jpeg")))
                "image/jpeg" else "image/png"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw java.io.FileNotFoundException("Read-only artwork")
        val context = context ?: return null
        val file = renderToCache(context, uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val context = context ?: return null
        val file = renderToCache(context, uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column -> when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            } }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

    private fun renderToCache(context: Context, uri: Uri): File? = runCatching {
        val (kind, id) = ArtworkAccessPolicy.route(uri.toString(), authority(context)) ?: return null
        when (kind) {
            "station" -> safeImage(context, StationArtworkStore.file(context, id))
            "track" -> safeImage(context, TrackArtworkStore.cachedFile(context, id))
            "custom" -> registration(context, kind, id)?.let { safeImage(context, File(it)) }
            "logo", "icon" -> registration(context, kind, id)?.toIntOrNull()?.let { resource ->
                cachedRender(context, "$kind-$id") { resourceBitmap(context, resource) }
            }
            "muted" -> renderMuted(context, id)
            else -> null
        }
    }.getOrNull()

    private fun safeImage(context: Context, file: File?): File? {
        val safe = file?.let { ArtworkAccessPolicy.allowedFile(it, roots(context)) } ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(safe.absolutePath, options)
        return safe.takeIf { options.outWidth in 1..ArtworkAccessPolicy.MAX_DIMENSION &&
            options.outHeight in 1..ArtworkAccessPolicy.MAX_DIMENSION }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options()
        options.inSampleSize = ArtworkAccessPolicy.sampleSize(bounds.outWidth, bounds.outHeight) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (bitmap.width > ArtworkAccessPolicy.MAX_DIMENSION || bitmap.height > ArtworkAccessPolicy.MAX_DIMENSION) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun resourceBitmap(context: Context, resId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return null
        return Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, SIZE, SIZE)
            drawable.draw(Canvas(it))
        }
    }

    /** Serializes only bounded renders; no recursive ContentResolver calls. */
    private fun renderMuted(context: Context, id: String): File? {
        val src = registration(context, "muted", id) ?: return null
        if (ArtworkAccessPolicy.httpsSource(src) && !Prefs(context).onlineArtworkEnabled) return null
        return cachedRender(context, "muted-$id") {
            val source = Uri.parse(src)
            val own = when {
                source.scheme == "content" -> {
                    val route = ArtworkAccessPolicy.route(src, authority(context))
                    if (route == null || route.first == "muted") null
                    else renderToCache(context, source)?.inputStream()?.use { input ->
                        ArtworkAccessPolicy.readBounded(input)?.let(::decode)
                    }
                }
                source.scheme == "file" -> safeImage(context, source.path?.let(::File))?.inputStream()?.use {
                    ArtworkAccessPolicy.readBounded(it)?.let(::decode)
                }
                source.scheme == "res" -> source.schemeSpecificPart.toIntOrNull()?.let {
                    resourceBitmap(context, it)
                }
                ArtworkAccessPolicy.httpsSource(src) -> download(src)?.let(::decode)
                else -> null
            }
            val logo = own ?: resourceBitmap(context, R.drawable.logo_placeholder) ?: return@cachedRender null
            try {
                Bitmap.createBitmap(logo.width, logo.height, Bitmap.Config.ARGB_8888).also { faded ->
                    Canvas(faded).drawBitmap(logo, 0f, 0f, Paint().apply { alpha = (MUTED_ALPHA * 255).toInt() })
                }
            } finally { logo.recycle() }
        }
    }

    private fun cachedRender(context: Context, name: String, render: () -> Bitmap?): File? = synchronized(renderLock) {
        val dir = File(context.cacheDir, "logo").apply { mkdirs() }
        val file = File(dir, "$name.png")
        // Names are internal fixed prefixes plus validated SHA-256 tokens.
        // Reject an existing symlink as well before AtomicFile opens a writer.
        if (file.canonicalFile.parentFile != dir.canonicalFile) return@synchronized null
        safeImage(context, file)?.let { return@synchronized it }
        val bitmap = render() ?: return@synchronized null
        val atomic = AtomicFile(file)
        try {
            val output = atomic.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
        } finally { bitmap.recycle() }
        safeImage(context, file)
    }

    private fun download(url: String): ByteArray? = runCatching {
        SafeHttp.getBytes(url, ArtworkAccessPolicy.MAX_BYTES, StationRepository.USER_AGENT)
    }.getOrNull()

    companion object {
        private const val SIZE = 512
        private val renderLock = Any()
        const val MUTED_ALPHA = 0.3f
        fun authority(context: Context): String = "${context.packageName}.logos"
        private fun registry(context: Context) = context.getSharedPreferences("artwork_provider_registry", Context.MODE_PRIVATE)
        private fun registration(context: Context, kind: String, id: String): String? =
            registry(context).getString("$kind/$id", null)
        private fun roots(context: Context) = listOf(
            File(context.filesDir, "logo-custom"), File(context.filesDir, "standby"),
            File(context.filesDir, "onair"),
            File(context.filesDir, "station-art"), File(context.cacheDir, "track-art"),
            File(context.cacheDir, "logo")
        )
        private fun register(context: Context, kind: String, value: String, revision: String): Uri {
            val id = ArtworkAccessPolicy.key("$kind:$value:$revision")
            registry(context).edit().putString("$kind/$id", value).apply()
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath(kind).appendPath(id).build()
        }

        fun mutedUriFor(context: Context, station: Station, source: Uri): Uri {
            // Source comes from metadata construction, never from exported URI parameters.
            val src = when (source.scheme) {
                "content" -> source.takeIf {
                    ArtworkAccessPolicy.route(it.toString(), authority(context))?.first in setOf("station", "track", "custom", "logo", "icon")
                }
                "file" -> source.path?.let(::File)?.let { ArtworkAccessPolicy.allowedFile(it, roots(context)) }?.let(Uri::fromFile)
                "res" -> source.takeIf { it.schemeSpecificPart.toIntOrNull() != null }
                "https" -> source.takeIf { ArtworkAccessPolicy.httpsSource(it.toString()) }
                else -> null
            } ?: Uri.fromParts("res", R.drawable.logo_placeholder.toString(), null)
            return register(context, "muted", src.toString(), station.id)
        }

        fun uriFor(context: Context, station: Station, resId: Int): Uri =
            register(context, "logo", resId.toString(), station.id)
        fun iconUri(context: Context, name: String, resId: Int): Uri =
            register(context, "icon", resId.toString(), name)
        fun customUriFor(context: Context, station: Station, file: File): Uri = fileUri(context, station.id, file)
        fun fileUri(context: Context, name: String, file: File): Uri {
            val safe = ArtworkAccessPolicy.allowedFile(file, roots(context))
            return if (safe == null) register(context, "logo", R.drawable.logo_placeholder.toString(), name)
            else register(context, "custom", safe.absolutePath, "$name:${safe.lastModified()}")
        }
    }
}
