package net.mspanc.twinsenradio.phone

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.media3.common.util.UnstableApi
import net.mspanc.twinsenradio.playback.PlaybackStatusBus

/** Owner-approved PH02-D; every phone view uses the same locked-A playback dock. */
@UnstableApi
class PhonePlayerActivity : PhonePlaybackActivity() {
    override val showMiniPlayer = false
    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var station: TextView
    private lateinit var collection: TextView

    override fun buildBody(): View = PhoneUi.column(this).apply {
        addView(buildPlayerHeader(expanded = true))
        val scroll = ScrollView(this@PhonePlayerActivity).apply { isFillViewport = true }
        val content = PhoneUi.column(this@PhonePlayerActivity).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) }
        art = SquareArtwork(this@PhonePlayerActivity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; background = PhoneUi.shape(context, PhoneUi.SURFACE, 16)
            clipToOutline = true; contentDescription = "Current track artwork or station image"
        }
        content.addView(art, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(8); marginEnd = dp(8) })
        title = PhoneUi.text(this@PhonePlayerActivity, size = 34f).apply {
            setPadding(0, dp(24), 0, 0); setTypeface(typeface, Typeface.BOLD)
        }
        artist = PhoneUi.text(this@PhonePlayerActivity, size = 22f)
        station = PhoneUi.text(this@PhonePlayerActivity, size = 18f, muted = true).apply { setPadding(0, dp(20), 0, 0) }
        collection = PhoneUi.text(this@PhonePlayerActivity, size = 16f, muted = true)
        listOf(title, artist, station, collection).forEach { content.addView(it) }
        scroll.addView(content); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    override fun renderContent() {
        if (!::art.isInitialized) return
        val shown = shownStation(); val text = trackText()
        title.text = text.title.ifBlank { shown?.name ?: "Add a playlist to start" }
        artist.text = text.artist; artist.visibility = if (text.artist.isBlank()) View.GONE else View.VISIBLE
        station.text = shown?.let { "Station: ${it.name}" }.orEmpty()
        collection.text = listOf(collectionLabel(), PlaybackStatusBus.quality.value?.chipLabel().orEmpty())
            .filter { it.isNotBlank() }.joinToString(" · ")
        art.visibility = if (artworkHidden) View.GONE else View.VISIBLE
        if (!artworkHidden) loadArt(art)
    }

    private class SquareArtwork(context: Context) : AppCompatImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, minOf(width, (360 * resources.displayMetrics.density).toInt()))
        }
    }
}
