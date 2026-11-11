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
        val scroll = object : ScrollView(this@PhonePlayerActivity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                if (::collection.isInitialized && View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
                    val textWidth = (View.MeasureSpec.getSize(widthMeasureSpec) - dp(24)).coerceAtLeast(0)
                    val details = listOf(title, artist, station, collection).filter { it.visibility != View.GONE }
                    details.forEach { it.measure(View.MeasureSpec.makeMeasureSpec(textWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)) }
                    val room = View.MeasureSpec.getSize(heightMeasureSpec) - dp(32) - details.sumOf { it.measuredHeight }
                    art.layoutParams.width = minOf((textWidth - dp(16)).coerceAtLeast(0), room.coerceAtLeast(0))
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { isFillViewport = true }
        val content = PhoneUi.column(this@PhonePlayerActivity).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) }
        art = SquareArtwork(this@PhonePlayerActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = PhoneUi.shape(context, PhoneUi.SURFACE, 16)
            clipToOutline = true; contentDescription = "Current track artwork or station image"
        }
        content.addView(art, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(8); marginEnd = dp(8); gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        title = PhoneUi.text(this@PhonePlayerActivity, size = 34f).apply {
            setPadding(0, dp(24), 0, 0); setTypeface(typeface, Typeface.BOLD)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        artist = PhoneUi.text(this@PhonePlayerActivity, size = 22f).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        station = PhoneUi.text(this@PhonePlayerActivity, size = 18f, muted = true).apply { setPadding(0, dp(20), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        collection = PhoneUi.text(this@PhonePlayerActivity, size = 16f, muted = true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        listOf(title, artist, station, collection).forEach { content.addView(it) }
        scroll.addView(content); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    override fun renderContent() {
        if (!::art.isInitialized) return
        val cast = PhoneCastRouting.get(applicationContext).displaySnapshot()
        val shown = if (cast != null) cast.stationId?.let(repo::byId) else shownStation()
        val text = trackText()
        val localMetadataMatchesCast = cast == null ||
            (cast.stationId != null && cast.stationId == PlaybackStatusBus.stationId.value)
        title.text = if (cast != null && !localMetadataMatchesCast) {
            cast.title ?: shown?.name ?: "Casting"
        } else text.title.ifBlank { shown?.name ?: cast?.title ?: "Add a playlist to start" }
        val artistText = text.artist.takeIf { localMetadataMatchesCast }.orEmpty()
        artist.text = artistText; artist.visibility = if (artistText.isBlank()) View.GONE else View.VISIBLE
        station.text = (shown?.name ?: cast?.title)?.let { "Station: $it" }.orEmpty()
        val quality = PlaybackStatusBus.quality.value?.technicalLabel()
            .takeIf { localMetadataMatchesCast }
        val output = cast?.let { "Casting to ${it.receiverName}" } ?: collectionLabel()
        collection.text = listOf(output, quality)
            .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        art.visibility = View.VISIBLE
        loadArt(art, shown, localMetadataMatchesCast, shown?.name ?: cast?.title)
    }

    private class SquareArtwork(context: Context) : AppCompatImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, width)
        }
    }
}
