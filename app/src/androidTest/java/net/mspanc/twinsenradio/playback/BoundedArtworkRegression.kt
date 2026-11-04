package net.mspanc.twinsenradio.playback

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import net.mspanc.twinsenradio.ui.ArtworkLoader

/** Exercises the production decoder with real Android image bytes. */
object BoundedArtworkRegression {
    fun run() {
        val source = Bitmap.createBitmap(4096, 1024, Bitmap.Config.ARGB_8888)
        val bytes = try {
            ByteArrayOutputStream().use { output ->
                check(source.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { source.recycle() }
        val decoded = checkNotNull(ArtworkLoader.decodeBounded(bytes))
        try {
            check(decoded.width == 1024 && decoded.height == 256) {
                "Large artwork must be sampled before pixel allocation"
            }
        } finally { decoded.recycle() }
        check(ArtworkLoader.decodeBounded(ByteArray(ArtworkAccessPolicy.MAX_BYTES + 1)) == null)
        check(ArtworkLoader.decodeBounded(byteArrayOf(1, 2, 3)) == null)
    }
}
