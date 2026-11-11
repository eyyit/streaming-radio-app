// Modified for Streaming Radio by Impostor Labs.
package net.mspanc.twinsenradio.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.AtomicFile
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.net.SafeHttp
import java.io.File

/**
 * A wide picture from a station's website, cut to the square the head unit and
 * the phone draw it in, and kept on disk.
 *
 * Both of its users get banners: a presenter's photo from the programme guide
 * (Mikolaj Tyczynski's is 8:3) and RNS's picture for the screen before play
 * (16:9, 8000 px wide). The middle square is what the station's own thumbnails
 * cut out too, and fetching the original once beats a soft 150 px thumbnail
 * every time.
 */
object SquareArt {

    private const val TAG = "SquareArt"
    private const val TIMEOUT_MS = 10_000

    const val SIZE = 512

    /** Downloads [url], cuts the middle square, scales it to [SIZE], saves it as [file]. */
    fun save(url: String, file: File): Boolean = runCatching {
        val bytes = download(url) ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = ArtworkAccessPolicy.sampleSize(bounds.outWidth, bounds.outHeight) ?: return false
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        var square: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            if (decoded.width > ArtworkAccessPolicy.MAX_DIMENSION || decoded.height > ArtworkAccessPolicy.MAX_DIMENSION) return false
            val side = minOf(decoded.width, decoded.height)
            val crop = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
            square = crop
            val result = if (side > SIZE) Bitmap.createScaledBitmap(crop, SIZE, SIZE, true) else crop
            scaled = result
            val parent = file.parentFile ?: return false
            parent.mkdirs()
            if (file.canonicalFile.parentFile != parent.canonicalFile) return false
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                check(result.compress(Bitmap.CompressFormat.JPEG, 90, output))
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
            true
        } finally {
            if (scaled != null && scaled !== square && scaled !== decoded) scaled.recycle()
            if (square != null && square !== decoded) square.recycle()
            decoded.recycle()
        }
    }.onFailure { Log.w(TAG, "Artwork fetch/render failed: ${it.javaClass.simpleName}") }.getOrDefault(false)

    private fun download(url: String): ByteArray? = runCatching {
        SafeHttp.getBytes(url, ArtworkAccessPolicy.MAX_BYTES, StationRepository.USER_AGENT, TIMEOUT_MS)
    }.getOrNull()
}
