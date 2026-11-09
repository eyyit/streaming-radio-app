package net.mspanc.twinsenradio.phone

import android.Manifest
import android.content.Intent
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
import net.mspanc.twinsenradio.data.PlaylistSource
import net.mspanc.twinsenradio.playback.RadioService

@UnstableApi
class PhoneLibraryActivity : PhonePlaybackActivity() {
    override val showMiniPlayer = false
    private var browse = "all"
    private var picking = false
    private var restoredListState: Parcelable? = null
    private lateinit var utilities: View
    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var station: TextView
    private lateinit var playingFrom: TextView
    private lateinit var browseHeader: View
    private lateinit var tabs: LinearLayout
    private lateinit var chooser: LinearLayout
    private lateinit var main: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var add: Button
    private lateinit var stations: PhoneStationAdapter
    private lateinit var sources: SourcesAdapter
    private var chooserSignature = ""
    private val positions = mutableMapOf<String, Parcelable?>()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val closeChooser = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { showChooser(false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        browse = savedInstanceState?.getString("browse") ?: "all"
        picking = savedInstanceState?.getBoolean("picking") ?: false
        restoredListState = savedInstanceState?.getParcelable("list_position")
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, closeChooser)
        closeChooser.isEnabled = picking
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("browse", browse); outState.putBoolean("picking", picking)
        outState.putParcelable("list_position", list.layoutManager?.onSaveInstanceState())
        super.onSaveInstanceState(outState)
    }

    override fun buildBody(): View = LibraryBody(this).apply {
        utilities = buildPlayerHeader(expanded = false); headerContent.addView(utilities)
        val summary = PhoneUi.row(this@PhoneLibraryActivity).apply { minimumHeight = dp(120) }
        art = ImageView(this@PhoneLibraryActivity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; background = PhoneUi.shape(context, PhoneUi.SURFACE, 12)
            clipToOutline = true; contentDescription = "Current track artwork or station image"
        }
        summary.addView(art, LinearLayout.LayoutParams(dp(118), dp(118)).apply {
            marginStart = dp(6); marginEnd = dp(22)
        })
        val text = PhoneUi.column(this@PhoneLibraryActivity)
        title = PhoneUi.text(this@PhoneLibraryActivity, size = 22f).apply { setTypeface(typeface, Typeface.BOLD) }
        artist = PhoneUi.text(this@PhoneLibraryActivity, size = 18f)
        station = PhoneUi.text(this@PhoneLibraryActivity, size = 16f, muted = true)
        listOf(title, artist, station).forEach { text.addView(it) }
        summary.addView(text, LinearLayout.LayoutParams(0, -2, 1f)); headerContent.addView(summary)
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
        headerContent.addView(selector); headerContent.addView(PhoneUi.gap(this@PhoneLibraryActivity, 12))
        browseHeader = PhoneUi.text(this@PhoneLibraryActivity, "Browse library", 14f, true)
        headerContent.addView(browseHeader)
        tabs = PhoneUi.row(this@PhoneLibraryActivity)
        listOf("all" to "All stations", "favorites" to "Favorites", "playlists" to "Playlists").forEach { (id, caption) ->
            tabs.addView(PhoneUi.button(this@PhoneLibraryActivity, caption) { changeBrowse(id) }.apply {
                tag = id; textSize = 14f; setPadding(dp(4), 0, dp(4), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        headerContent.addView(tabs)
        main = PhoneUi.column(this@PhoneLibraryActivity)
        add = PhoneUi.button(this@PhoneLibraryActivity, "+ Add playlist", true) { openImport() }
        main.addView(add)
        empty = PhoneUi.text(this@PhoneLibraryActivity, size = 16f, muted = true)
        main.addView(empty)
        list = RecyclerView(this@PhoneLibraryActivity).apply { layoutManager = LinearLayoutManager(context) }
        stations = PhoneStationAdapter(this@PhoneLibraryActivity, prefs, repo) { selected ->
            playStation(selected, if (browse == "favorites") RadioService.NODE_FAVOURITES else RadioService.NODE_ALL)
        }
        sources = SourcesAdapter()
        main.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(main, LinearLayout.LayoutParams(-1, 0, 1f))
        chooser = PhoneUi.column(this@PhoneLibraryActivity)
        val scroller = ScrollView(this@PhoneLibraryActivity).apply { addView(chooser) }
        scroller.tag = "phone_collection_chooser"
        addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun changeBrowse(id: String) {
        positions[browse] = list.layoutManager?.onSaveInstanceState()
        browse = id; renderContent()
        positions[id]?.let { state -> list.post { list.layoutManager?.onRestoreInstanceState(state) } }
    }
    private fun showChooser(value: Boolean) {
        picking = value; closeChooser.isEnabled = value; renderContent()
    }
    private fun openImport() { startActivity(Intent(this, PhoneImportActivity::class.java)) }

    override fun renderContent() {
        if (!::list.isInitialized) return
        val shown = shownStation(); val text = trackText(); val theme = AppearanceTheme.current(this)
        title.text = text.title.ifBlank { shown?.name ?: "Add a playlist to start" }
        artist.text = text.artist; artist.visibility = if (text.artist.isBlank()) View.GONE else View.VISIBLE
        station.text = shown?.name.orEmpty()
        art.visibility = if (artworkHidden) View.GONE else View.VISIBLE
        if (!artworkHidden) loadArt(art)
        playingFrom.text = "Playing from: ${collectionLabel()}"
        utilities.visibility = if (picking) View.GONE else View.VISIBLE
        main.visibility = if (picking) View.GONE else View.VISIBLE
        tabs.visibility = if (picking) View.GONE else View.VISIBLE
        browseHeader.visibility = if (picking) View.GONE else View.VISIBLE
        (chooser.parent as View).visibility = if (picking) View.VISIBLE else View.GONE
        for (i in 0 until tabs.childCount) (tabs.getChildAt(i) as Button).apply {
            val selected = tag == browse
            backgroundTintList = null
            background = android.graphics.drawable.InsetDrawable(
                PhoneUi.shape(this@PhoneLibraryActivity, if (selected) theme.accent else PhoneUi.CANVAS, 24),
                0, dp(8), 0, dp(8))
            setTextColor(PhoneUi.TEXT); isSelected = selected
        }
        val playlistMode = browse == "playlists"
        if (list.adapter !== if (playlistMode) sources else stations) list.adapter = if (playlistMode) sources else stations
        val values = if (browse == "favorites") repo.favourites() else repo.all()
        if (playlistMode) sources.update(repo.playlists()) else stations.update(values)
        val isEmpty = if (playlistMode) repo.playlists().isEmpty() else values.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        empty.text = when (browse) {
            "favorites" -> "No favorites yet. Tap a station heart to add one."
            "playlists" -> "No playlists yet. Add a hosted URL or import an M3U file."
            else -> "Your library is empty. Add a playlist to start."
        }
        add.visibility = if (playlistMode || repo.all().isEmpty()) View.VISIBLE else View.GONE
        restoredListState?.let { state -> list.post { list.layoutManager?.onRestoreInstanceState(state) }; restoredListState = null }
        val key = repo.playlists().toString() + prefs.favourites + prefs.activeBrowseParent + repo.revisions.value
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
            RadioService.NODE_FAVOURITES to "${repo.favourites().size} stations") + repo.playlists().map {
            "/playlist/${it.id}" to "${repo.sourceStations(it.id).size} stations · ${if (it.isLocal) "Local import" else "Hosted playlist"}"
        }
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
        chooser.addView(PhoneUi.button(this, "+ Add playlist") { openImport() })
        chooser.addView(PhoneUi.text(this, "Your current station stays when it is in the collection.", 14f, true))
    }

    private inner class SourcesAdapter : RecyclerView.Adapter<SourceHolder>() {
        private var values = emptyList<PlaylistSource>()
        fun update(next: List<PlaylistSource>) { if (next != values) { values = next; notifyDataSetChanged() } }
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): SourceHolder {
            val row = PhoneUi.column(this@PhoneLibraryActivity).apply {
                minimumHeight = dp(72); setPadding(dp(8), dp(8), dp(8), dp(8)); isClickable = true; isFocusable = true
            }
            val title = PhoneUi.text(this@PhoneLibraryActivity, size = 18f)
            val detail = PhoneUi.text(this@PhoneLibraryActivity, size = 14f, muted = true)
            row.addView(title); row.addView(detail); row.addView(PhoneUi.divider(this@PhoneLibraryActivity))
            return SourceHolder(row, title, detail)
        }
        override fun onBindViewHolder(holder: SourceHolder, position: Int) {
            val source = values[position]
            holder.title.text = source.name
            holder.detail.text = "${repo.sourceStations(source.id).size} stations · ${if (source.isLocal) "Local import" else "Hosted playlist"}"
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@PhoneLibraryActivity, PhonePlaylistActivity::class.java).putExtra("source_id", source.id))
            }
        }
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
    private class SourceHolder(view: View, val title: TextView, val detail: TextView) : RecyclerView.ViewHolder(view)
}
