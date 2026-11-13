package net.mspanc.twinsenradio.phone

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.text.TextUtils
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.AppearanceTheme
import net.mspanc.twinsenradio.playback.RadioService
import net.mspanc.twinsenradio.playback.PlaybackStatusBus

@UnstableApi
class PhoneLibraryActivity : PhonePlaybackActivity() {
    companion object {
        internal fun usesLandscapeSplit(config: Configuration): Boolean =
            config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 560
    }

    private val split get() = usesLandscapeSplit(resources.configuration)
    override val showMiniPlayer = false
    private var browse = "all"
    private var picking = false
    private var restoredListState: Parcelable? = null
    private lateinit var utilities: View
    private lateinit var art: PhoneCarouselArtwork
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var station: TextView
    private var audioFormat: TextView? = null
    private lateinit var playingFrom: TextView
    private lateinit var browseHeader: View
    private lateinit var tabs: LinearLayout
    private lateinit var chooser: LinearLayout
    private lateinit var main: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var stations: PhoneStationAdapter
    private val stationFollow = PhoneStationFollow()
    private var chooserSignature = ""
    private val positions = mutableMapOf<String, Parcelable?>()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val closeChooser = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { showChooser(false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        browse = savedInstanceState?.getString("browse")?.takeIf { it == "favorites" } ?: "all"
        picking = savedInstanceState?.getBoolean("picking") ?: false
        restoredListState = savedInstanceState?.getParcelable("list_position")
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, closeChooser)
        closeChooser.isEnabled = picking
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    override fun onStop() {
        stationFollow.reset()
        super.onStop()
    }

    override fun stationAreaReady() = !stationFollow.isCentering
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showChooser(false)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("browse", browse); outState.putBoolean("picking", picking)
        outState.putParcelable("list_position", list.layoutManager?.onSaveInstanceState())
        super.onSaveInstanceState(outState)
    }

    override fun buildBody(): View = if (split) {
        PhoneUi.column(this).apply {
            utilities = buildPlayerHeader(split = true)
            addView(utilities)
            addView(PhoneUi.divider(this@PhoneLibraryActivity))
            val panes = PhoneUi.row(this@PhoneLibraryActivity).apply {
                gravity = Gravity.TOP
                setPadding(0, dp(8), 0, 0)
                addView(buildLibraryPane(), LinearLayout.LayoutParams(0, -1, 55f))
                addView(View(this@PhoneLibraryActivity).apply {
                    setBackgroundColor(PhoneUi.CONTROL)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(1), -1).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                })
                addView(buildLandscapePlayer(), LinearLayout.LayoutParams(0, -1, 45f))
            }
            addView(panes, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    } else buildLibraryPane()

    private fun buildLibraryPane(): View = LibraryBody(this).apply {
        if (!split) {
            utilities = buildPlayerHeader()
            headerContent.addView(utilities)
            headerContent.addView(buildPortraitSummary(), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            })
        }
        val selector = PhoneUi.row(this@PhoneLibraryActivity).apply {
            minimumHeight = dp(48); background = PhoneUi.shape(context, PhoneUi.CONTROL)
            setPadding(dp(12), 0, dp(8), 0); isClickable = true; isFocusable = true
            contentDescription = "Choose playback collection"; setOnClickListener { showChooser(!picking) }
        }
        playingFrom = PhoneUi.text(this@PhoneLibraryActivity)
        selector.addView(playingFrom, LinearLayout.LayoutParams(0, -2, 1f))
        selector.addView(PhoneUi.icon(this@PhoneLibraryActivity, R.drawable.ic_expand_more, "").apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false
            setOnClickListener { showChooser(!picking) }
        })
        val browseControls = PhoneUi.column(this@PhoneLibraryActivity)
        browseControls.addView(selector)
        if (!split) browseControls.addView(PhoneUi.gap(this@PhoneLibraryActivity, 12))
        browseHeader = PhoneUi.text(this@PhoneLibraryActivity, "Browse library", 14f, true)
        if (!split) browseControls.addView(browseHeader)
        tabs = PhoneUi.row(this@PhoneLibraryActivity)
        listOf("all" to "All stations", "favorites" to "Favorites").forEach { (id, caption) ->
            tabs.addView(PhoneUi.button(this@PhoneLibraryActivity, caption) { changeBrowse(id) }.apply {
                tag = id; textSize = 14f; setPadding(dp(4), 0, dp(4), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        browseControls.addView(tabs)
        headerContent.addView(browseControls)
        main = PhoneUi.column(this@PhoneLibraryActivity)
        empty = PhoneUi.text(this@PhoneLibraryActivity, size = 16f, muted = true)
        main.addView(empty)
        list = RecyclerView(this@PhoneLibraryActivity).apply { layoutManager = LinearLayoutManager(context) }
        stations = PhoneStationAdapter(this@PhoneLibraryActivity, prefs, repo, compact = split) { selected ->
            playStation(selected, if (browse == "favorites") RadioService.NODE_FAVOURITES else RadioService.NODE_ALL)
        }
        main.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!split) animateStationArea(main, browseControls)
        addView(main, LinearLayout.LayoutParams(-1, 0, 1f))
        chooser = PhoneUi.column(this@PhoneLibraryActivity)
        val scroller = ScrollView(this@PhoneLibraryActivity).apply { addView(chooser) }
        scroller.tag = "phone_collection_chooser"
        addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun buildPortraitSummary(): View {
        val summary = PhoneUi.row(this@PhoneLibraryActivity).apply {
            minimumHeight = dp(120)
            isFocusable = true
            setOnClickListener { openExpandedPlayer() }
        }
        art = PhoneCarouselArtwork(this@PhoneLibraryActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = PhoneUi.shape(context, PhoneUi.SURFACE, 12)
            clipToOutline = true; contentDescription = "Current track artwork or station image"
        }
        enableArtworkStationSwipe(art, expandOnTap = true)
        summary.addView(art, LinearLayout.LayoutParams(dp(118), dp(118)).apply {
            marginStart = dp(6); marginEnd = dp(22)
        })
        val text = PhoneUi.column(this@PhoneLibraryActivity)
        title = PhoneUi.text(this@PhoneLibraryActivity, size = 22f).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        artist = PhoneUi.text(this@PhoneLibraryActivity, size = 18f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        station = PhoneUi.text(this@PhoneLibraryActivity, size = 16f, muted = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        listOf(title, artist, station).forEach { text.addView(it) }
        art.attachDetails(title, artist, station)
        playerTransitionViews = listOf(art, title, artist, station)
        summary.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        return summary
    }

    private fun buildLandscapePlayer(): View = PhoneUi.column(this).apply {
        splitTransportHost = this
        val details = PhoneUi.column(this@PhoneLibraryActivity).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        art = PhoneCarouselArtwork(this@PhoneLibraryActivity, square = true).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = PhoneUi.shape(context, PhoneUi.SURFACE, 16)
            clipToOutline = true
            contentDescription = "Current track artwork or station image"
        }
        enableArtworkStationSwipe(art)
        details.addView(art, LinearLayout.LayoutParams(dp(160), -2))
        fun label(size: Float, muted: Boolean = false) =
            PhoneUi.text(this@PhoneLibraryActivity, size = size, muted = muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }
        title = label(24f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        }
        artist = label(16f)
        station = label(14f, muted = true)
        val format = label(12f, muted = true).also { audioFormat = it }
        val labels = listOf(title, artist, station, format)
        labels.forEach(details::addView)
        art.attachDetails(*labels.toTypedArray())
        val scroll = object : ScrollView(this@PhoneLibraryActivity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val textHeight = labels.sumOf {
                    val metrics = it.paint.fontMetricsInt
                    metrics.descent - metrics.ascent + it.paddingTop + it.paddingBottom
                }
                val availableHeight = MeasureSpec.getSize(heightMeasureSpec) - textHeight - dp(8)
                art.layoutParams.width = minOf(
                    MeasureSpec.getSize(widthMeasureSpec),
                    availableHeight.coerceIn(dp(56), dp(248))
                )
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            isFillViewport = true
            addView(details)
        }
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun changeBrowse(id: String) {
        if (browse == id) return
        positions[browse] = list.layoutManager?.onSaveInstanceState()
        browse = id
        restoredListState = positions[id]
        renderContent()
    }
    private fun showChooser(value: Boolean) {
        picking = value; closeChooser.isEnabled = value; renderContent()
    }

    override fun renderContent() {
        if (!::list.isInitialized) return
        val cast = PhoneCastRouting.get(applicationContext).displaySnapshot()
        val shown = if (cast != null) cast.stationId?.let(repo::byId) else shownStation()
        val text = trackText(); val theme = AppearanceTheme.current(this)
        val localMetadataMatchesCast = cast == null ||
            (cast.stationId != null && cast.stationId == PlaybackStatusBus.stationId.value)
        val artistText = text.artist.takeIf { localMetadataMatchesCast }.orEmpty()
        loadArt(art, shown, localMetadataMatchesCast, shown?.name ?: cast?.title)
        val titleText = if (cast != null && !localMetadataMatchesCast) {
            cast.title ?: shown?.name ?: "Casting"
        } else text.title.ifBlank { shown?.name ?: cast?.title ?: "Add a playlist to start" }
        val stationText = (shown?.name ?: cast?.title).orEmpty()
        art.displayDetails {
            title.text = titleText
            artist.text = artistText
            artist.visibility = if (artistText.isBlank()) View.GONE else View.VISIBLE
            station.text = if (split && titleText == stationText) "" else stationText
            if (split) station.visibility = if (station.text.isEmpty()) View.GONE else View.VISIBLE
            audioFormat?.apply {
                this.text = PlaybackStatusBus.quality.value?.technicalLabel()
                    .takeIf { localMetadataMatchesCast }.orEmpty()
                visibility = if (this.text.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        playingFrom.text = "Playing from: ${collectionLabel()}"
        val centerActive = intent.getBooleanExtra(PhonePlayerActivity.CENTER_ACTIVE_STATION, false)
        val activeStation = if (cast == null) PlaybackStatusBus.stationId.value else cast.stationId
        if (centerActive && activeStation != null && browse == "favorites" &&
            repo.favourites().none { it.id == activeStation }) {
            browse = "all"
        }
        utilities.visibility = if (picking && !split) View.GONE else View.VISIBLE
        main.visibility = if (picking) View.GONE else View.VISIBLE
        tabs.visibility = if (picking) View.GONE else View.VISIBLE
        browseHeader.visibility = if (picking) View.GONE else View.VISIBLE
        (chooser.parent as View).visibility = if (picking) View.VISIBLE else View.GONE
        for (i in 0 until tabs.childCount) (tabs.getChildAt(i) as Button).apply {
            val selected = tag == browse
            backgroundTintList = null
            val tabInset = dp(if (split) 4 else 8)
            background = android.graphics.drawable.InsetDrawable(
                PhoneUi.shape(this@PhoneLibraryActivity, if (selected) theme.accent else PhoneUi.CANVAS, 24),
                0, tabInset, 0, tabInset)
            setTextColor(PhoneUi.TEXT); isSelected = selected
        }
        if (list.adapter !== stations) list.adapter = stations
        val values = if (browse == "favorites") repo.favourites() else repo.all()
        stations.update(values)
        restoredListState?.let { state ->
            list.layoutManager?.onRestoreInstanceState(state)
            restoredListState = null
        }
        if (!picking) {
            if (centerActive) {
                intent.removeExtra(PhonePlayerActivity.CENTER_ACTIVE_STATION)
                stationFollow.centerBeforeDraw(list, stations, browse, activeStation)
            } else stationFollow.reveal(list, stations, browse, activeStation)
        }
        val isEmpty = values.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        empty.text = when (browse) {
            "favorites" -> "No favorites yet. Tap a station heart to add one."
            else -> "Your library is empty. Open Settings > Playlists to add a playlist."
        }
        val key = "${prefs.favourites}${prefs.activeBrowseParent}${repo.revisions.value}"
        if (key != chooserSignature) { chooserSignature = key; rebuildChooser() }
    }

    private fun rebuildChooser() {
        chooser.removeAllViews(); chooser.setPadding(dp(12), dp(12), dp(12), dp(12))
        chooser.background = PhoneUi.shape(this, PhoneUi.SURFACE, 18)
        val header = PhoneUi.row(this)
        header.addView(PhoneUi.heading(this, "Playback collection"), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(PhoneUi.button(this, "Cancel") { showChooser(false) })
        chooser.addView(header); chooser.addView(PhoneUi.text(this, "Choose what plays next", 14f, true))
        val entries = listOf(RadioService.NODE_ALL to "${repo.all().size} stations",
            RadioService.NODE_FAVOURITES to "${repo.favourites().size} stations")
        entries.forEach { (parent, detail) ->
            val selected = parent == prefs.activeBrowseParent || (parent == RadioService.NODE_ALL && prefs.activeBrowseParent.isBlank())
            val row = PhoneUi.row(this).apply {
                minimumHeight = dp(72); isClickable = true; isFocusable = true
                contentDescription = "${collectionLabel(parent)}, $detail${if (selected) ", selected" else ""}"
                setOnClickListener { if (chooseCollection(parent)) showChooser(false) }
            }
            val text = PhoneUi.column(this)
            text.addView(PhoneUi.text(this, collectionLabel(parent), 18f))
            text.addView(PhoneUi.text(this, detail, 14f, true))
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) row.addView(PhoneUi.icon(this, R.drawable.ic_check, "Selected").apply {
                imageTintList = android.content.res.ColorStateList.valueOf(AppearanceTheme.current(this@PhoneLibraryActivity).accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false
            })
            chooser.addView(row); chooser.addView(PhoneUi.divider(this))
        }
        chooser.addView(PhoneUi.text(this, "Your current station stays when it is in the collection.", 14f, true))
    }

    /** Preserve a usable list viewport when text or a short display enlarges the header. */
    private class LibraryBody(context: android.content.Context) : LinearLayout(context) {
        val headerContent = PhoneUi.column(context)
        private val header = ScrollView(context).apply { addView(headerContent) }
        init {
            orientation = VERTICAL
            addView(header, LayoutParams(-1, -2))
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            headerContent.measure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val available = View.MeasureSpec.getSize(heightMeasureSpec)
            val headerLimit = (available - PhoneUi.dp(context, 96)).coerceAtLeast(0)
            header.layoutParams.height = minOf(headerContent.measuredHeight, headerLimit)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
