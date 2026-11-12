package net.mspanc.twinsenradio.phone

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.*
import net.mspanc.twinsenradio.playback.*
import net.mspanc.twinsenradio.ui.ArtworkLoader
import kotlin.math.abs

internal fun phonePlaybackStatusLabel(
    status: PlaybackStatusBus.Status,
    hasCurrentStreamFormat: Boolean
): String? = when (status) {
    PlaybackStatusBus.Status.BUFFERING ->
        if (hasCurrentStreamFormat) "Buffering…" else "Connecting…"
    PlaybackStatusBus.Status.CONNECTING -> "Connecting…"
    PlaybackStatusBus.Status.RECONNECTING -> "Reconnecting…"
    PlaybackStatusBus.Status.WAITING_FOR_NETWORK -> "Waiting for network"
    PlaybackStatusBus.Status.STATION_UNREACHABLE -> "Station unavailable"
    else -> null
}

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
    private lateinit var castController: PhoneCastController
    private lateinit var status: TextView
    private lateinit var previous: ImageButton
    private lateinit var play: ImageButton
    private lateinit var next: ImageButton
    private lateinit var favorite: ImageButton
    private lateinit var shuffle: ImageButton
    private val artworkCarousels = mutableListOf<PhoneCarouselArtwork>()
    private val recentCovers = LruCache<String, Uri>(6)
    private var previewGestureDirection = 0
    private var previewImageDirection = 0
    private var previewStationId: String? = null
    private var previewLoad: Job? = null
    private var miniArt: PhoneCarouselArtwork? = null
    private var miniTitle: TextView? = null
    private var miniArtist: TextView? = null
    private var miniStation: TextView? = null
    private var mini: View? = null
    private var artVisibility: ImageButton? = null
    private var artRefresh: ImageButton? = null
    private var artLoading: PhoneGradientLoadingView? = null
    private var stationArea: View? = null
    private var browseControls: View? = null
    protected var playerTransitionViews: List<View> = emptyList()
    private val displayPreferences by lazy { getSharedPreferences("phone_player", MODE_PRIVATE) }
    protected val artworkHidden get() = displayPreferences.getBoolean("hide_art", false)
    private val settings by lazy { getSharedPreferences("twinsen_radio", MODE_PRIVATE) }
    private var refreshPosted = false
    private val refreshOnFrame = Runnable {
        refreshPosted = false
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refreshPlaybackUi()
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refreshPlaybackUi()
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            castController.onLocalPlaybackChanged()
            if (previewGestureDirection != 0 && events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
                )) updateCarouselPreview(previewGestureDirection)
            schedulePlaybackRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this); repo = StationRepository.get(this); metadata = MetadataFactory(this, prefs)
        castController = PhoneCastRouting.get(applicationContext).controller
        val root = PhoneUi.column(this).apply {
            setBackgroundColor(PhoneUi.CANVAS)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(buildBody(), LinearLayout.LayoutParams(-1, 0, 1f))
        if (showMiniPlayer) root.addView(buildMiniPlayer())
        if (this !is PhonePlayerActivity) root.addView(PhoneCastUi.dockRow(this))
        val dock = buildDock()
        root.addView(dock)
        status = PhoneUi.text(this, size = 14f, muted = true).apply {
            tag = "phone_playback_status"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = PhoneUi.shape(context, PhoneUi.SURFACE, 12)
            visibility = View.INVISIBLE
        }
        val screen = object : FrameLayout(this) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                val statusBottom = root.top + dock.top - dp(4)
                status.offsetTopAndBottom(statusBottom - status.bottom)
            }
        }
        screen.addView(root, FrameLayout.LayoutParams(-1, -1))
        screen.addView(status, FrameLayout.LayoutParams(-2, playbackStatusHeight(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { marginStart = dp(16); marginEnd = dp(16) })
        setContentView(screen)
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
                    Prefs.favouritesFlow, repo.revisions, StationArtworkStore.loading).forEach { flow ->
                    launch {
                        flow.collect {
                            if (flow === PlaybackStatusBus.stationId || flow === PlaybackStatusBus.quality) {
                                castController.onLocalPlaybackChanged()
                            }
                            schedulePlaybackRefresh()
                        }
                    }
                }
                // Only the small factual notice is time-dependent; no artwork or network polling.
                launch { while (isActive) { delay(1_000); renderStatus() } }
            }
        }
        refreshPlaybackUi()
    }

    override fun onStart() {
        super.onStart(); artLoading?.resumeForActivity(); settings.registerOnSharedPreferenceChangeListener(preferenceListener)
        PhoneCastRouting.get(applicationContext).listeners.add(::refreshPlaybackUi)
        connectionFailed = false
        val pending = MediaController.Builder(this,
            SessionToken(this, ComponentName(this, RadioService::class.java))).buildAsync()
        connection = pending
        pending.addListener({
            if (connection !== pending) return@addListener
            try { controller = pending.get().also { it.addListener(playerListener) }; castController.onLocalPlaybackChanged() }
            catch (_: Exception) { connectionFailed = true }
            refreshPlaybackUi()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        window.decorView.removeCallbacks(refreshOnFrame)
        refreshPosted = false
        finishArtworkPreview()
        PhoneCastRouting.get(applicationContext).listeners.remove(::refreshPlaybackUi)
        settings.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        controller?.removeListener(playerListener); controller = null
        artLoading?.stopForActivity()
        val pending = connection; connection = null
        if (pending != null) MediaController.releaseFuture(pending)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        renderContent()
        stationArea?.let {
            PhoneWheelTransition.playIncoming(this, it, playerTransitionViews, browseControls,
                ::onStationAreaMotion, ::stationAreaReady)
        }
    }

    protected open fun onStationAreaMotion(moving: Boolean) {
        mini?.let { row ->
            row.animate().cancel()
            if (moving) row.alpha = 0f
            else row.animate().alpha(1f).setDuration(100).start()
        }
    }
    protected open fun stationAreaReady(): Boolean = true

    private fun schedulePlaybackRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        window.decorView.postOnAnimation(refreshOnFrame)
    }

    protected fun dp(value: Int) = PhoneUi.dp(this, value)
    protected fun playbackStatusHeight(): Int {
        if (!::status.isInitialized) return dp(24)
        val metrics = status.paint.fontMetricsInt
        return maxOf(dp(24), metrics.bottom - metrics.top)
    }
    protected fun shownStation(): Station? = PlaybackStatusBus.stationId.value?.let(repo::byId) ?: repo.resumeCandidate()
    private fun activePlaybackStation(): Station? = controller?.currentMediaItem?.mediaId?.let(repo::byMediaId)
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
    protected fun playStation(
        station: Station,
        browseParent: String,
        explicitCollectionSelection: Boolean = false
    ) {
        val c = controller ?: return message("Playback is connecting. Try again.")
        castController.onExplicitStationSelection(station.id)
        val route = if (explicitCollectionSelection) {
            RadioService.PHONE_SELECTION_PREFIX + browseParent
        } else browseParent
        c.setMediaItem(MediaItem.Builder().setMediaId(Station.contextualMediaId(station.mediaId, route)).build())
        c.prepare()
        if (!castController.isCasting()) c.play()
    }
    protected fun chooseCollection(parent: String): Boolean {
        if (collectionMembers(parent).isEmpty()) { message("${collectionLabel(parent)} has no stations. Current audio kept."); return false }
        val c = controller ?: run { message("Playback is connecting. Try again."); return false }
        castController.onExplicitQueueNavigation()
        val wasPlaying = c.playWhenReady
        c.setMediaItem(MediaItem.Builder().setMediaId("/collection$parent").build())
        c.prepare(); c.playWhenReady = wasPlaying
        return true
    }
    protected fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    protected fun animateStationArea(view: View, controls: View? = null) {
        stationArea = view
        browseControls = controls
    }

    protected fun openExpandedPlayer() {
        PhoneWheelTransition.prepare(
            stationArea, PhonePlayerActivity::class.java.name, enteringFromTop = true,
            sharedViews = playerTransitionViews,
            browseControls = browseControls,
        )
        startActivity(Intent(this, PhonePlayerActivity::class.java))
        overridePendingTransition(0, 0)
    }

    protected fun enableArtworkStationSwipe(
        view: View,
        expandOnTap: Boolean = false,
        onVerticalSwipe: ((Float) -> Unit)? = null,
    ) {
        val carousel = (view as? PhoneCarouselArtwork)?.also(artworkCarousels::add)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val swipeDistance = maxOf(dp(40), touchSlop * 2)
        var downX = 0f
        var downY = 0f
        var horizontal = false
        var vertical = false
        var cancelled = false
        if (expandOnTap) view.setOnClickListener { openExpandedPlayer() }
        view.setOnTouchListener { target, event ->
            val dx = event.x - downX
            val dy = event.y - downY
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    horizontal = false; vertical = false; cancelled = false
                    finishArtworkPreview()
                    if (onVerticalSwipe != null) target.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    cancelled = true
                    finishArtworkPreview()
                    carousel?.settle()
                }
                MotionEvent.ACTION_MOVE -> if (!cancelled) {
                    if (!horizontal && !vertical) {
                        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            horizontal = true
                            target.parent.requestDisallowInterceptTouchEvent(true)
                        } else if (onVerticalSwipe != null && abs(dy) > touchSlop &&
                            abs(dy) > abs(dx)
                        ) {
                            vertical = true
                            target.parent.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    if (horizontal && (if (dx < 0) next else previous).isEnabled) {
                        val step = if (dx < 0) -1 else 1
                        if (previewGestureDirection != step) {
                            previewGestureDirection = step
                            if (prefs.artworkTransitionsEnabled) updateCarouselPreview(step)
                        }
                        carousel?.drag(dx, prefs.artworkTransitionsEnabled)
                    } else carousel?.settle()
                }
                MotionEvent.ACTION_UP -> {
                    val button = if (dx < 0) next else previous
                    if (!cancelled && horizontal && abs(dx) >= swipeDistance &&
                        abs(dx) > abs(dy) && button.isEnabled) {
                        val step = if (dx < 0) -1 else 1
                        if (prefs.artworkTransitionsEnabled) updateCarouselPreview(step)
                        finishArtworkPreview()
                        button.performClick()
                    } else if (!cancelled && vertical && abs(dy) >= swipeDistance &&
                        abs(dy) > abs(dx)
                    ) {
                        finishArtworkPreview()
                        carousel?.settle()
                        onVerticalSwipe?.invoke(dy)
                    } else {
                        finishArtworkPreview()
                        carousel?.settle()
                        if (!cancelled && expandOnTap && !horizontal &&
                            abs(dx) <= touchSlop && abs(dy) <= touchSlop) target.performClick()
                    }
                }
            }
            true
        }
    }

    private fun adjacentStation(step: Int): Station? {
        val candidate = PlaybackStatusBus.phoneStationNeighbor?.invoke(-step) ?: return null
        return repo.byId(candidate)?.takeUnless { it.id == PlaybackStatusBus.stationId.value }
    }

    private fun nextArtworkStep(): Int {
        if (!prefs.randomStationOrder) return -1
        val currentId = PlaybackStatusBus.stationId.value ?: return -1
        val targetId = PlaybackStatusBus.phoneStationNeighbor?.invoke(1) ?: return -1
        val members = collectionMembers(prefs.activeBrowseParent)
        val currentIndex = members.indexOfFirst { it.id == currentId }
        val targetIndex = members.indexOfFirst { it.id == targetId }
        return if (currentIndex >= 0 && targetIndex >= 0 && targetIndex < currentIndex) 1 else -1
    }

    private fun stationPreviewDrawable(station: Station): Drawable? {
        val fallback = metadata.logoResId(station)
        if (fallback != R.drawable.logo_placeholder) {
            return ContextCompat.getDrawable(this, fallback)?.mutate()
        }
        return PhoneStationArtwork(station.name)
    }

    private fun updateCarouselPreview(step: Int) {
        val station = adjacentStation(step)
        if (station?.id == previewStationId && previewImageDirection == step && previewLoad != null) return
        previewLoad?.cancel()
        previewStationId = station?.id
        previewImageDirection = step
        val uri = station?.let(metadata::logoDisplayUri)?.takeIf {
            prefs.onlineArtworkEnabled || it.scheme == "file" || it.scheme == "content"
        }
        val trackUri = station?.id?.let(::cachedCoverUri)
        val cached = ArtworkLoader.cachedImage(this, trackUri ?: uri)
        showAdjacentCover(step, station, cached ?: station?.let(::stationPreviewDrawable))
        previewLoad = if (cached == null && uri != null && previewGestureDirection == step) {
            lifecycleScope.launch {
                val image = ArtworkLoader.loadStationPreview(this@PhonePlaybackActivity, uri)
                if (image != null && previewGestureDirection == step && previewStationId == station?.id) {
                    showAdjacentCover(step, station, image)
                }
            }
        } else null
    }

    private fun cachedCoverUri(stationId: String): Uri? =
        recentCovers.get(stationId)?.takeIf {
            prefs.onlineArtworkEnabled && !artworkHidden && ArtworkLoader.hasCachedImage(it)
        }

    private fun showAdjacentCover(step: Int, station: Station?, image: Drawable?) {
        artworkCarousels.forEach { carousel ->
            carousel.setAdjacentPreview(step, station?.id, image, station?.name)
        }
    }

    private fun finishArtworkPreview() {
        previewGestureDirection = 0
        previewImageDirection = 0
        previewStationId = null
        previewLoad?.cancel()
        previewLoad = null
    }

    protected fun buildPlayerHeader(restoreLibrary: (() -> Unit)? = null): View {
        val expanded = restoreLibrary != null
        val row = PhoneUi.row(this)
        val restore = PhoneUi.row(this).apply {
            minimumHeight = dp(48); isClickable = true; isFocusable = true
            contentDescription = if (expanded) "Restore station library" else "Expand player"
            fun navigate() {
                if (restoreLibrary != null) restoreLibrary() else openExpandedPlayer()
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
        artLoading = PhoneGradientLoadingView(this).apply {
            artRefresh?.let { bindIdleIndicator(it) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setLoading(false)
        }
        refreshBox.addView(artLoading, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        row.addView(refreshBox, LinearLayout.LayoutParams(dp(48), dp(48)))
        PhoneCastUi.introduce(this, castController.createButton(row))
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

    protected fun loadArt(
        target: ImageView,
        station: Station? = shownStation(),
        useTrackArtwork: Boolean = true,
        fallbackName: String? = station?.name
    ) {
        val logo = station?.let(metadata::logoResId) ?: R.drawable.logo_placeholder
        val fallback = fallbackName?.takeIf { logo == R.drawable.logo_placeholder }?.let(::PhoneStationArtwork)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (target.drawable == null) {
                if (fallback != null) target.setImageDrawable(fallback) else target.setImageResource(logo)
            }
            return
        }
        (target as? PhoneCarouselArtwork)?.stationChanged(station?.id, fallbackName,
            prefs.artworkTransitionsEnabled && hasWindowFocus())
        val now = PlaybackStatusBus.nowPlaying.value.takeUnless { PlaybackStatusBus.joining() }
        val carousel = target as? PhoneCarouselArtwork
        val showCover = useTrackArtwork && prefs.onlineArtworkEnabled && !artworkHidden
        val cover = if (!showCover) null else if (PlaybackStatusBus.artPending()) {
            station?.id?.let(::cachedCoverUri)
        } else {
            metadata.phoneArtwork(now?.isRealSong == true,
                PlaybackStatusBus.coverArtUrl.value, PlaybackStatusBus.onAir.value)
                ?.also { uri -> if (now?.isRealSong == true && station != null) recentCovers.put(station.id, uri) }
        }
        ArtworkLoader.into(lifecycleScope,
            cover,
            station?.let { metadata.logoDisplayUri(it) }?.takeIf { prefs.onlineArtworkEnabled || it.scheme in listOf("file", "content") },
            logo,
            target,
            animate = prefs.artworkTransitionsEnabled && carousel == null,
            fallbackDrawable = fallback,
            showFallbackWhileLoading = true)
    }

    private fun buildMiniPlayer(): View {
        val row = PhoneUi.row(this).apply {
            tag = "phone_mini_player"; minimumHeight = dp(88)
            setPadding(0, dp(8), 0, dp(8)); isClickable = true; isFocusable = true
            contentDescription = "Expand player"
            setOnClickListener { openExpandedPlayer() }
        }
        miniArt = PhoneCarouselArtwork(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        miniArt?.let { enableArtworkStationSwipe(it, expandOnTap = true) }
        row.addView(miniArt, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) })
        val text = PhoneUi.column(this)
        miniTitle = PhoneUi.text(this, size = 18f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        miniArtist = PhoneUi.text(this, size = 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        miniStation = PhoneUi.text(this, size = 14f, muted = true)
        listOf(miniTitle, miniArtist, miniStation).forEach { text.addView(it) }
        miniArt?.attachDetails(*listOfNotNull(miniTitle, miniArtist, miniStation).toTypedArray())
        playerTransitionViews = listOfNotNull(miniArt, miniTitle, miniArtist, miniStation)
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f)); mini = row
        return row
    }

    private fun buildDock(): View {
        val controls = PhoneUi.column(this)
        controls.addView(PhoneUi.divider(this))
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
            startActivity(Intent(this, PhoneSettingsActivity::class.java))
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
        secondary.addView(favoriteAction, LinearLayout.LayoutParams(0, -2, 1f)); controls.addView(secondary)
        val transport = PhoneUi.row(this).apply {
            tag = "phone_primary_transport"; setPadding(0, dp(12), 0, 0)
        }
        previous = PhoneUi.icon(this, R.drawable.ic_transport_previous, "Previous station", 64).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                artworkCarousels.forEach { it.expectStation(1, prefs.artworkTransitionsEnabled) }
                castController.onExplicitQueueNavigation()
                controller?.seekToPreviousMediaItem()
            }
        }
        play = PhoneUi.icon(this, R.drawable.ic_transport_play, "Play", 88).apply {
            setPadding(dp(26), dp(26), dp(26), dp(26)); setOnClickListener { togglePlayback() }
        }
        next = PhoneUi.icon(this, R.drawable.ic_transport_next, "Next station", 64).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                artworkCarousels.forEach { it.expectStation(nextArtworkStep(), prefs.artworkTransitionsEnabled) }
                castController.onExplicitQueueNavigation()
                controller?.seekToNextMediaItem()
            }
        }
        listOf(previous, play, next).forEach { button -> transport.addView(FrameLayout(this).apply {
            addView(button, FrameLayout.LayoutParams(button.layoutParams.width, button.layoutParams.height, Gravity.CENTER))
        }, LinearLayout.LayoutParams(0, dp(88), 1f)) }
        controls.addView(transport); return controls
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
        if (castController.isCasting()) {
            castController.toggleRemotePlayback()
            return
        }
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
        val isPlaying = if (castController.isCasting()) castController.isRemotePlaying() else c?.playWhenReady == true
        play.setImageResource(if (isPlaying) R.drawable.ic_transport_pause else R.drawable.ic_transport_play)
        play.contentDescription = if (isPlaying) "Pause" else "Play"
        play.isEnabled = castController.isCasting() ||
            (c != null && (c.currentMediaItem != null || shown != null))
        previous.isEnabled = c?.currentMediaItem != null && c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        next.isEnabled = c?.currentMediaItem != null && c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        listOf(play, previous, next, shuffle, favorite).forEach { it.alpha = if (it.isEnabled) 1f else .35f }
        renderStatus()
        artVisibility?.apply {
            isSelected = artworkHidden; contentDescription = if (artworkHidden) "Show artwork" else "Hide artwork"
            alpha = if (artworkHidden) .55f else 1f
        }
        val busy = prefs.onlineArtworkEnabled && PlaybackStatusBus.trackArtworkLoading.value
        artRefresh?.contentDescription = if (busy) "Loading artwork" else "Refresh track artwork"
        artRefresh?.apply {
            visibility = View.VISIBLE
            val now = PlaybackStatusBus.nowPlaying.value
            isEnabled = c?.isSessionCommandAvailable(RadioService.CMD_REFRESH_ARTWORK) == true &&
                PlaybackStatusBus.stationId.value != null && prefs.onlineArtworkEnabled && !busy &&
                now?.isRealSong == true && !now.artist.isNullOrBlank() && !now.songTitle.isNullOrBlank()
        }
        artLoading?.setLoading(busy, if (artRefresh?.isEnabled == true) 1f else .35f)
        mini?.visibility = if (shown == null || castController.isCasting()) View.GONE else View.VISIBLE
        val text = trackText()
        miniArt?.let { cover ->
            loadArt(cover)
            val title = text.title.ifBlank { shown?.name.orEmpty() }
            val station = shown?.name.orEmpty()
            cover.displayDetails(listOf(title, text.artist, station)) {
                miniTitle?.text = title
                miniArtist?.text = text.artist
                miniArtist?.visibility = if (text.artist.isBlank()) View.GONE else View.VISIBLE
                miniStation?.text = station
            }
        }
        renderContent()
    }
    private fun renderStatus() {
        val notice = statusText()
        if (status.text.toString() != notice) status.text = notice
        status.visibility = if (notice.isBlank()) View.INVISIBLE else View.VISIBLE
    }
    private fun statusText(): String = if (connectionFailed) "Playback connection unavailable"
    else phonePlaybackStatusLabel(
        PlaybackStatusBus.status.value,
        hasCurrentStreamFormat = PlaybackStatusBus.quality.value != null
    )
        ?: PlaybackStatusBus.missingTrackInfoText(SystemClock.elapsedRealtime()).orEmpty()
    companion object {
        val SHUFFLE = SessionCommand("net.mspanc.twinsenradio.TOGGLE_SHUFFLE", Bundle.EMPTY)
    }
}
