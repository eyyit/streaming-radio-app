package net.mspanc.twinsenradio.playback

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.ui.ArtworkLoader
import java.io.File
import java.util.UUID

/** Real provider/decoder/UI path; UUID-private file and registry entry only. */
object ArtworkFallbackRegression {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val name = "fallback-test-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "track-art/$name.jpg")
        file.parentFile!!.mkdirs()
        val color = 0xffd12ba6.toInt()
        val source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(color)
            file.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { source.recycle() }
        val logo = LogoProvider.fileUri(context, name, file)
        val missing = Uri.parse("content://${LogoProvider.authority(context)}/track/${"0".repeat(64)}.jpg")
        var owner: Owner? = null
        lateinit var image: ImageView
        try {
            instrumentation.runOnMainSync {
                owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
                image = ImageView(context)
                ArtworkLoader.into(checkNotNull(owner).lifecycleScope, missing, logo, R.drawable.logo_placeholder, image)
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            var matched = false
            while (!matched && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync {
                    val bitmap = (image.drawable as? BitmapDrawable)?.bitmap
                    matched = bitmap != null && bitmap.getPixel(0, 0) == color
                }
                if (!matched) Thread.sleep(50)
            }
            check(matched) { "Missing track art must fall back to valid content station logo" }
        } finally {
            instrumentation.runOnMainSync {
                owner?.registry?.currentState = Lifecycle.State.DESTROYED
            }
            context.getSharedPreferences("artwork_provider_registry", 0).edit()
                .remove("custom/${logo.lastPathSegment}").commit()
            check(!file.exists() || file.delete())
        }
    }
}
