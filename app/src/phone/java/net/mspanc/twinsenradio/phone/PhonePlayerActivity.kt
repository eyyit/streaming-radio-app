package net.mspanc.twinsenradio.phone

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.util.UnstableApi
import net.mspanc.twinsenradio.playback.PlaybackStatusBus

/** Owner-approved PH02-D; every phone view uses the same locked-A playback dock. */
@UnstableApi
class PhonePlayerActivity : PhonePlaybackActivity() {
    companion object {
        const val CENTER_ACTIVE_STATION = "center_active_station_on_entry"
    }

    override val showMiniPlayer = false
    private lateinit var art: PhoneCarouselArtwork
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var station: TextView
    private lateinit var collection: TextView
    private lateinit var wheelPanel: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { revealStationList() }
        })
    }

    override fun onStationAreaMotion(moving: Boolean) {
        if (::art.isInitialized) art.setPanelMoving(moving)
    }

    private fun revealStationList(enteringFromTop: Boolean = false, centerActive: Boolean = false) {
        val library = PhoneLibraryActivity::class.java
        PhoneWheelTransition.prepare(wheelPanel, library.name, enteringFromTop, fadeOutgoing = true,
            sharedViews = playerTransitionViews)
        startActivity(Intent(this, library).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (centerActive) putExtra(CENTER_ACTIVE_STATION, true)
        })
        finish()
        overridePendingTransition(0, 0)
    }

    override fun buildBody(): View = PhoneUi.column(this).apply {
        addView(buildPlayerHeader { revealStationList() })
        val scroll = object : ScrollView(this@PhonePlayerActivity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                if (::collection.isInitialized && View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
                    val textWidth = (View.MeasureSpec.getSize(widthMeasureSpec) - dp(24)).coerceAtLeast(0)
                    // Reserve the same line budget while labels are hidden, empty, or changing.
                    val detailsHeight = listOf(title, artist, station, collection).sumOf { detail ->
                        val metrics = detail.paint.fontMetricsInt
                        val firstLine = if (detail.includeFontPadding) metrics.bottom - metrics.top
                            else metrics.descent - metrics.ascent
                        firstLine + (detail.maxLines - 1) * detail.lineHeight +
                            detail.compoundPaddingTop + detail.compoundPaddingBottom
                    }
                    val room = View.MeasureSpec.getSize(heightMeasureSpec) - dp(32) -
                        detailsHeight - playbackStatusHeight()
                    art.layoutParams.width = minOf(
                        (textWidth - dp(16)).coerceAtLeast(0),
                        room.coerceAtLeast(dp(72))
                    )
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { isFillViewport = true }
        wheelPanel = scroll
        animateStationArea(scroll)
        val content = PhoneUi.column(this@PhonePlayerActivity).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) }
        art = PhoneCarouselArtwork(this@PhonePlayerActivity, square = true).apply {
            useExpandedViewport(scroll)
            scaleType = ImageView.ScaleType.CENTER_CROP; background = PhoneUi.shape(context, PhoneUi.SURFACE, 16)
            clipToOutline = true; contentDescription = "Current track artwork or station image"
        }
        enableArtworkStationSwipe(art, onVerticalSwipe = { revealStationList(enteringFromTop = it > 0f) })
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
        station = PhoneUi.text(this@PhonePlayerActivity, size = 18f, muted = true).apply {
            setPadding(0, dp(20), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        collection = PhoneUi.text(this@PhonePlayerActivity, size = 16f, muted = true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        listOf(title, artist, station, collection).forEach { detail ->
            detail.setOnClickListener { revealStationList(centerActive = true) }
            content.addView(detail)
        }
        art.attachDetails(title, artist, station, collection)
        playerTransitionViews = listOf(art, title, artist, station)
        scroll.addView(content); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    override fun renderContent() {
        if (!::art.isInitialized) return
        val cast = PhoneCastRouting.get(applicationContext).displaySnapshot()
        val shown = if (cast != null) cast.stationId?.let(repo::byId) else shownStation()
        val text = trackText()
        val localMetadataMatchesCast = cast == null ||
            (cast.stationId != null && cast.stationId == PlaybackStatusBus.stationId.value)
        val artistText = text.artist.takeIf { localMetadataMatchesCast }.orEmpty()
        val quality = PlaybackStatusBus.quality.value?.technicalLabel()
            .takeIf { localMetadataMatchesCast }
        val output = cast?.let { "Casting to ${it.receiverName}" } ?: collectionLabel()
        loadArt(art, shown, localMetadataMatchesCast, shown?.name ?: cast?.title)
        val titleText = if (cast != null && !localMetadataMatchesCast) {
            cast.title ?: shown?.name ?: "Casting"
        } else text.title.ifBlank { shown?.name ?: cast?.title ?: "Add a playlist to start" }
        val stationText = (shown?.name ?: cast?.title)?.let { "Station: $it" }.orEmpty()
        val collectionText = listOf(output, quality).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        art.displayDetails(listOf(titleText, artistText, stationText, collectionText)) {
            title.text = titleText
            artist.text = artistText
            artist.visibility = if (artistText.isBlank()) View.GONE else View.VISIBLE
            station.text = stationText
            collection.text = collectionText
            val hasStation = shown != null || cast?.title != null
            listOf(title, artist, station, collection).forEach {
                it.isClickable = hasStation
                it.isFocusable = hasStation
            }
        }
    }

}
