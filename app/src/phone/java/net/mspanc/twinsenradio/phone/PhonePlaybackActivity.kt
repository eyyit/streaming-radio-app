package net.mspanc.twinsenradio.phone

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.*
import net.mspanc.twinsenradio.playback.*
import net.mspanc.twinsenradio.ui.ArtworkLoader
import net.mspanc.twinsenradio.ui.SettingsActivity

/** One phone media-session connection and one shared dock for every phone surface. */
@UnstableApi
abstract class PhonePlaybackActivity : AppCompatActivity() {
    protected lateinit var prefs: Prefs
    protected lateinit var repo: StationRepository
    protected lateinit var metadata: MetadataFactory
    protected var controller: MediaController? = null
        private set
    protected open val showMiniPlayer = true
    protected abstract fun buildBody(): View
    protected open fun renderContent() {}
    private var connection: ListenableFuture<MediaController>? = null
    private var ready = false
    private var connectionFailed = false
    private lateinit var status: TextView
    private lateinit var previous: ImageButton
    private lateinit var play: ImageButton
    private lateinit var next: ImageButton
    private lateinit var favorite: ImageButton
    private lateinit var shuffle: ImageButton
    private var miniArt: ImageView? = null
    private var miniTitle: TextView? = null
    private var miniArtist: TextView? = null
    private var miniStation: TextView? = null
    private var mini: View? = null
    private var artVisibility: ImageButton? = null
    private var artRefresh: ImageButton? = null
    private var artLoading: ImageView? = null
    private val displayPreferences by lazy { getSharedPreferences("phone_player", MODE_PRIVATE) }
    protected val artworkHidden get() = displayPreferences.getBoolean("hide_art", false)
    private val settings by lazy { getSharedPreferences("twinsen_radio", MODE_PRIVATE) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refreshPlaybackUi()
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshPlaybackUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this); repo = StationRepository.get(this); metadata = MetadataFactory(this, prefs)
        val root = PhoneUi.column(this).apply {
            setBackgroundColor(PhoneUi.CANVAS)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(buildBody(), LinearLayout.LayoutParams(-1, 0, 1f))
        if (showMiniPlayer) root.addView(buildMiniPlayer())
        status = PhoneUi.text(this, size = 14f, muted = true).apply {
            minHeight = dp(24); visibility = View.GONE
        }
        root.addView(status)
        root.addView(buildDock())
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, dp(8) + maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        ready = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                listOf(PlaybackStatusBus.stationId, PlaybackStatusBus.nowPlaying,
                    PlaybackStatusBus.coverArtUrl, PlaybackStatusBus.trackInfo, PlaybackStatusBus.onAir,
                    PlaybackStatusBus.status, PlaybackStatusBus.wantsToPlay, PlaybackStatusBus.join,
                    PlaybackStatusBus.quality, PlaybackStatusBus.trackArtworkLoading,
                    Prefs.favouritesFlow, repo.revisions).forEach { flow ->
                    launch { flow.collect { refreshPlaybackUi() } }
                }
                // Only the small factual notice is time-dependent; no artwork or network polling.
                launch { while (isActive) { delay(1_000); renderStatus() } }
            }
        }
        refreshPlaybackUi()
    }

    override fun onStart() {
        super.onStart(); settings.registerOnSharedPreferenceChangeListener(preferenceListener)
        connectionFailed = false
        val pending = MediaController.Builder(this,
            SessionToken(this, ComponentName(this, RadioService::class.java))).buildAsync()
        connection = pending
        pending.addListener({
            if (connection !== pending) return@addListener
            try { controller = pending.get().also { it.addListener(playerListener) } }
            catch (_: Exception) { connectionFailed = true }
            refreshPlaybackUi()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        settings.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        controller?.removeListener(playerListener); controller = null
        (artLoading?.drawable as? Animatable)?.stop()
        val pending = connection; connection = null
        if (pending != null) MediaController.releaseFuture(pending)
        super.onStop()
    }

    protected fun dp(value: Int) = PhoneUi.dp(this, value)
    protected fun shownStation(): Station? = PlaybackStatusBus.stationId.value?.let(repo::byId) ?: repo.resumeCandidate()
    protected fun trackText(): PhoneText = MetadataFactory.forPhone(
        PlaybackStatusBus.nowPlaying.value.takeUnless { PlaybackStatusBus.joining() },
        PlaybackStatusBus.trackInfo.value, PlaybackStatusBus.onAir.value, prefs.presentation, prefs.enrichWithYear)
    protected fun collectionLabel(parent: String = prefs.activeBrowseParent): String = when (parent) {
        RadioService.NODE_FAVOURITES -> "Favorites"
        RadioService.NODE_ALL, "" -> "All stations"
        else -> repo.playlists().firstOrNull { "/playlist/${it.id}" == parent }?.name ?: "Collection unavailable"
    }
    protected fun collectionMembers(parent: String): List<Station> = when (parent) {
        RadioService.NODE_FAVOURITES -> repo.favourites()
        RadioService.NODE_ALL, "" -> repo.all()
        else -> if (parent.startsWith("/playlist/")) repo.sourceStations(parent.removePrefix("/playlist/")) else emptyList()
    }
    protected fun playStation(station: Station, browseParent: String) {
        val c = controller ?: return message("Playback is connecting. Try again.")
        c.setMediaItem(MediaItem.Builder().setMediaId(Station.contextualMediaId(station.mediaId, browseParent)).build())
        c.prepare(); c.play()
    }
    protected fun chooseCollection(parent: String): Boolean {
        if (collectionMembers(parent).isEmpty()) { message("${collectionLabel(parent)} has no stations. Current audio kept."); return false }
        val c = controller ?: run { message("Playback is connecting. Try again."); return false }
        val wasPlaying = c.playWhenReady
        c.setMediaItem(MediaItem.Builder().setMediaId("/collection$parent").build())
        c.prepare(); c.playWhenReady = wasPlaying
        return true
    }
    protected fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    protected fun buildPlayerHeader(expanded: Boolean): View {
        val row = PhoneUi.row(this)
        val restore = PhoneUi.row(this).apply {
            minimumHeight = dp(48); isClickable = true; isFocusable = true
            contentDescription = if (expanded) "Restore station library" else "Expand player"
            fun navigate() {
                if (!expanded) startActivity(Intent(this@PhonePlaybackActivity, PhonePlayerActivity::class.java))
                else {
                    if (isTaskRoot) startActivity(Intent(this@PhonePlaybackActivity, PhoneLibraryActivity::class.java))
                    finish()
                }
            }
            setOnClickListener { navigate() }
            addView(PhoneUi.icon(this@PhonePlaybackActivity,
                if (expanded) R.drawable.phone_ic_view_restore else R.drawable.phone_ic_view_expand, "").apply {
                setPadding(dp(10), dp(10), dp(10), dp(10))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false
                setOnClickListener { navigate() }
            })
            addView(PhoneUi.text(this@PhonePlaybackActivity, if (expanded) "Library" else "Player"))
        }
        row.addView(restore, LinearLayout.LayoutParams(0, dp(48), 1f))
        artVisibility = PhoneUi.icon(this, R.drawable.phone_ic_track_art_hide, "Hide artwork").apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                displayPreferences.edit().putBoolean("hide_art", !artworkHidden).apply(); refreshPlaybackUi()
            }
        }
        row.addView(artVisibility)
        val refreshBox = FrameLayout(this)
        artRefresh = PhoneUi.icon(this, R.drawable.phone_ic_artwork_refresh, "Refresh track artwork").apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { refreshArtwork() }
        }
        refreshBox.addView(artRefresh)
        artLoading = ImageView(this).apply {
            setImageResource(R.drawable.avd_artwork_loading); visibility = View.GONE
            contentDescription = "Loading artwork"
        }
        refreshBox.addView(artLoading, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        row.addView(refreshBox, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(PhoneUi.icon(this, R.drawable.phone_cast, "Cast unavailable").apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isEnabled = false; alpha = .35f; tooltipText = "Cast unavailable"
        })
        return row
    }

    private fun refreshArtwork() {
        val id = PlaybackStatusBus.stationId.value ?: return
        val c = controller ?: return
        if (!c.isSessionCommandAvailable(RadioService.CMD_REFRESH_ARTWORK)) return
        val future = c.sendCustomCommand(RadioService.CMD_REFRESH_ARTWORK, Bundle().apply { putString("stationId", id) })
        future.addListener({
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
            runCatching { future.get() }.onSuccess {
                if (it.resultCode != SessionResult.RESULT_SUCCESS) message("Artwork could not be refreshed.")
            }.onFailure { message("Artwork could not be refreshed.") }
        }, ContextCompat.getMainExecutor(this))
    }

    protected fun loadArt(target: ImageView) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val station = shownStation()
        val now = PlaybackStatusBus.nowPlaying.value.takeUnless { PlaybackStatusBus.joining() }
        ArtworkLoader.into(lifecycleScope,
            if (prefs.onlineArtworkEnabled) metadata.phoneArtwork(now?.isRealSong == true,
                PlaybackStatusBus.coverArtUrl.value, PlaybackStatusBus.onAir.value) else null,
            station?.let { metadata.logoDisplayUri(it) }?.takeIf { prefs.onlineArtworkEnabled || it.scheme in listOf("file", "content") },
            station?.let { metadata.logoResId(it) } ?: R.drawable.logo_placeholder,
            target, blank = PlaybackStatusBus.artPending() && PlaybackStatusBus.stationId.value != null,
            animate = prefs.artworkTransitionsEnabled)
    }

    private fun buildMiniPlayer(): View {
        val row = PhoneUi.row(this).apply {
            tag = "phone_mini_player"; minimumHeight = dp(88)
            setPadding(0, dp(8), 0, dp(8)); isClickable = true; isFocusable = true
            contentDescription = "Expand player"
            setOnClickListener { startActivity(Intent(this@PhonePlaybackActivity, PhonePlayerActivity::class.java)) }
        }
        miniArt = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        row.addView(miniArt, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) })
        val text = PhoneUi.column(this)
        miniTitle = PhoneUi.text(this, size = 18f)
        miniArtist = PhoneUi.text(this, size = 16f)
        miniStation = PhoneUi.text(this, size = 14f, muted = true)
        listOf(miniTitle, miniArtist, miniStation).forEach { text.addView(it) }
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f)); mini = row
        return row
    }

    private fun buildDock(): View {
        val dock = PhoneUi.column(this)
        dock.addView(PhoneUi.divider(this))
        val secondary = PhoneUi.row(this).apply { tag = "phone_secondary_actions" }
        fun action(drawable: Int, caption: String, click: () -> Unit): LinearLayout = PhoneUi.column(this).apply {
            gravity = Gravity.CENTER; minimumHeight = dp(64)
            addView(PhoneUi.icon(this@PhonePlaybackActivity, drawable, caption).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { click() }
            })
            addView(PhoneUi.text(this@PhonePlaybackActivity, caption, 12f).apply {
                gravity = Gravity.CENTER; maxLines = 2; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        secondary.addView(action(R.drawable.phone_ic_settings_gear, "Settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val shuffleAction = action(R.drawable.phone_shuffle_off, "Shuffle stations") { toggleShuffle() }
        shuffle = shuffleAction.getChildAt(0) as ImageButton
        shuffle.apply {
            tag = "phone_locked_a_shuffle"; imageTintList = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        secondary.addView(shuffleAction, LinearLayout.LayoutParams(0, -2, 1f))
        val favoriteAction = action(R.drawable.ic_star_outline, "Favorite station") {
            shownStation()?.let { prefs.toggleFavourite(it.id); refreshPlaybackUi() }
        }
        favorite = favoriteAction.getChildAt(0) as ImageButton
        secondary.addView(favoriteAction, LinearLayout.LayoutParams(0, -2, 1f)); dock.addView(secondary)
        val transport = PhoneUi.row(this).apply {
            tag = "phone_primary_transport"; setPadding(0, dp(12), 0, 0)
        }
        previous = PhoneUi.icon(this, R.drawable.ic_transport_previous, "Previous station", 64).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { controller?.seekToPreviousMediaItem() }
        }
        play = PhoneUi.icon(this, R.drawable.ic_transport_play, "Play", 88).apply {
            setPadding(dp(26), dp(26), dp(26), dp(26)); setOnClickListener { togglePlayback() }
        }
        next = PhoneUi.icon(this, R.drawable.ic_transport_next, "Next station", 64).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { controller?.seekToNextMediaItem() }
        }
        listOf(previous, play, next).forEach { button -> transport.addView(FrameLayout(this).apply {
            addView(button, FrameLayout.LayoutParams(button.layoutParams.width, button.layoutParams.height, Gravity.CENTER))
        }, LinearLayout.LayoutParams(0, dp(88), 1f)) }
        dock.addView(transport); return dock
    }

    private fun toggleShuffle() {
        val c = controller ?: return
        if (!c.isSessionCommandAvailable(SHUFFLE)) return
        val future = c.sendCustomCommand(SHUFFLE, Bundle.EMPTY)
        future.addListener({
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
            runCatching { future.get() }.onSuccess {
                if (it.resultCode != SessionResult.RESULT_SUCCESS) message("Shuffle could not be changed.")
                refreshPlaybackUi()
            }.onFailure { message("Shuffle could not be changed.") }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun togglePlayback() {
        val c = controller ?: return
        when {
            c.playWhenReady -> c.pause()
            c.currentMediaItem != null -> c.play()
            else -> shownStation()?.let { playStation(it, prefs.activeBrowseParent.ifBlank { RadioService.NODE_ALL }) }
        }
    }
    protected fun refreshPlaybackUi() {
        if (!ready) return
        val c = controller; val shown = shownStation(); val theme = AppearanceTheme.current(this)
        val selected = shown?.id in prefs.favourites
        favorite.setImageResource(if (selected) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        favorite.imageTintList = ColorStateList.valueOf(PhoneUi.TEXT)
        favorite.isSelected = selected; favorite.isEnabled = shown != null
        favorite.contentDescription = if (selected) "Remove station from Favorites" else "Add station to Favorites"
        shuffle.setImageResource(if (prefs.randomStationOrder) R.drawable.phone_shuffle_on else R.drawable.phone_shuffle_off)
        shuffle.isSelected = prefs.randomStationOrder
        shuffle.isEnabled = c?.isSessionCommandAvailable(SHUFFLE) == true
        shuffle.contentDescription = when {
            !shuffle.isEnabled -> "Shuffle stations unavailable"
            prefs.randomStationOrder -> "Disable shuffle stations"
            else -> "Enable shuffle stations"
        }
        play.background = PhoneUi.shape(this, theme.accent, 80)
        play.imageTintList = ColorStateList.valueOf(PhoneUi.TEXT)
        play.setImageResource(if (c?.playWhenReady == true) R.drawable.ic_transport_pause else R.drawable.ic_transport_play)
        play.contentDescription = if (c?.playWhenReady == true) "Pause" else "Play"
        play.isEnabled = c != null && (c.currentMediaItem != null || shown != null)
        previous.isEnabled = c?.currentMediaItem != null && c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        next.isEnabled = c?.currentMediaItem != null && c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        listOf(play, previous, next, shuffle, favorite).forEach { it.alpha = if (it.isEnabled) 1f else .35f }
        renderStatus()
        artVisibility?.apply {
            isSelected = artworkHidden; contentDescription = if (artworkHidden) "Show artwork" else "Hide artwork"
            alpha = if (artworkHidden) .55f else 1f
        }
        val busy = prefs.onlineArtworkEnabled && PlaybackStatusBus.trackArtworkLoading.value
        artLoading?.visibility = if (busy) View.VISIBLE else View.GONE
        (artLoading?.drawable as? Animatable)?.let { if (busy) it.start() else it.stop() }
        artRefresh?.apply {
            visibility = if (busy) View.INVISIBLE else View.VISIBLE
            val now = PlaybackStatusBus.nowPlaying.value
            isEnabled = c?.isSessionCommandAvailable(RadioService.CMD_REFRESH_ARTWORK) == true &&
                PlaybackStatusBus.stationId.value != null && prefs.onlineArtworkEnabled && !busy &&
                now?.isRealSong == true && !now.artist.isNullOrBlank() && !now.songTitle.isNullOrBlank()
            alpha = if (isEnabled) 1f else .35f
        }
        mini?.visibility = if (shown == null) View.GONE else View.VISIBLE
        val text = trackText()
        miniTitle?.text = text.title.ifBlank { shown?.name.orEmpty() }
        miniArtist?.text = text.artist
        miniArtist?.visibility = if (text.artist.isBlank()) View.GONE else View.VISIBLE
        miniStation?.text = shown?.name.orEmpty()
        miniArt?.let(::loadArt)
        renderContent()
    }
    private fun renderStatus() {
        val notice = statusText()
        status.text = notice
        status.visibility = if (notice.isBlank()) View.GONE else View.VISIBLE
    }
    private fun statusText(): String = if (connectionFailed) "Playback connection unavailable" else when (PlaybackStatusBus.status.value) {
        PlaybackStatusBus.Status.CONNECTING -> "Connecting…"
        PlaybackStatusBus.Status.BUFFERING -> "Buffering…"
        PlaybackStatusBus.Status.RECONNECTING -> "Reconnecting…"
        PlaybackStatusBus.Status.WAITING_FOR_NETWORK -> "Waiting for network"
        PlaybackStatusBus.Status.STATION_UNREACHABLE -> "Station unavailable"
        else -> PlaybackStatusBus.missingTrackInfoText(SystemClock.elapsedRealtime()).orEmpty()
    }
    companion object {
        val SHUFFLE = SessionCommand("net.mspanc.twinsenradio.TOGGLE_SHUFFLE", Bundle.EMPTY)
    }
}
