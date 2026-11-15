package net.mspanc.twinsenradio.phone

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.core.view.doOnLayout
import android.view.ViewTreeObserver
import androidx.core.animation.doOnEnd
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.playback.ArtworkWorkScheduler
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.data.AppearanceTheme
import net.mspanc.twinsenradio.playback.MetadataFactory
import net.mspanc.twinsenradio.playback.PlaybackStatusBus

private object StationStatePayload
private object StationArtworkPayload

@UnstableApi
internal class PhoneStationAdapter(
    private val owner: LifecycleOwner,
    private val prefs: Prefs,
    private val repo: StationRepository,
    private val compact: Boolean = false,
    private val artworkCache: PhoneStationArtworkCache = PhoneStationArtworkCache(
        owner as android.content.Context,
        owner.lifecycleScope
    ),
    private val play: (Station) -> Unit
) : RecyclerView.Adapter<PhoneStationAdapter.Holder>() {
    private var stations = emptyList<Station>()
    private var artworkWindow = emptyList<Station>()
    private var artworkResolution: Job? = null
    private val metadata = MetadataFactory(owner as android.content.Context, prefs)
    private var lastFavourites = prefs.favourites
    private var lastStationId = PlaybackStatusBus.stationId.value
    private var lastNowPlaying = PlaybackStatusBus.nowPlaying.value
    private var lastArtworkLoading = net.mspanc.twinsenradio.playback.StationArtworkStore.loading.value
    private var lastOnlineArtworkEnabled = prefs.onlineArtworkEnabled
    private var lastRepositoryRevision = repo.revisions.value
    private var lastAccent = AppearanceTheme.current(owner as android.content.Context).accent
    fun update(values: List<Station>) {
        val oldStations = stations
        val favourites = prefs.favourites
        val stationId = PlaybackStatusBus.stationId.value
        val nowPlaying = PlaybackStatusBus.nowPlaying.value
        val artworkLoading = net.mspanc.twinsenradio.playback.StationArtworkStore.loading.value
        val onlineArtworkEnabled = prefs.onlineArtworkEnabled

        if (oldStations != values) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldStations.size
                override fun getNewListSize() = values.size
                override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                    oldStations[oldPosition].id == values[newPosition].id
                override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                    oldStations[oldPosition] == values[newPosition]
            })
            stations = values
            diff.dispatchUpdatesTo(this)
        }

        val changedIds = mutableSetOf<String>()
        val artworkChangedIds = mutableSetOf<String>()
        changedIds += lastFavourites - favourites
        changedIds += favourites - lastFavourites
        if (lastStationId != stationId || lastNowPlaying != nowPlaying) {
            lastStationId?.let(changedIds::add)
            stationId?.let(changedIds::add)
        }
        changedIds += lastArtworkLoading - artworkLoading
        changedIds += artworkLoading - lastArtworkLoading
        if (lastOnlineArtworkEnabled != onlineArtworkEnabled) {
            changedIds += values.map { it.id }
            artworkChangedIds += values.map { it.id }
        }
        if (lastRepositoryRevision != repo.revisions.value) {
            val favoriteIds = values.asSequence().map { it.id }
                .filter { it in favourites }.toList()
            changedIds += favoriteIds
            artworkChangedIds += favoriteIds
        }
        val accent = AppearanceTheme.current(owner as android.content.Context).accent
        if (lastAccent != accent) stationId?.let(changedIds::add)
        if (oldStations != values || lastArtworkLoading != artworkLoading ||
            lastOnlineArtworkEnabled != onlineArtworkEnabled || lastRepositoryRevision != repo.revisions.value
        ) artworkWindow = emptyList()
        changedIds.forEach { id ->
            val position = positionOf(id)
            if (position >= 0) notifyItemChanged(position,
                if (id in artworkChangedIds) StationArtworkPayload else StationStatePayload)
        }

        lastFavourites = favourites
        lastStationId = stationId
        lastNowPlaying = nowPlaying
        lastArtworkLoading = artworkLoading
        lastOnlineArtworkEnabled = onlineArtworkEnabled
        lastRepositoryRevision = repo.revisions.value
        lastAccent = accent
    }
    override fun getItemCount() = stations.size
    fun positionOf(id: String) = stations.indexOfFirst { it.id == id }
    fun prefetchAhead(first: Int, last: Int, backwards: Boolean = false) {
        if (stations.isEmpty()) return
        val start = first.coerceAtLeast(0).coerceAtMost(stations.lastIndex)
        val end = last.coerceAtLeast(start).coerceAtMost(minOf(stations.lastIndex, start + PREFETCH_ROWS - 1))
        val positions = if (backwards) end downTo start else start..end
        val window = positions.map(stations::get)
        if (artworkWindow == window) return
        artworkWindow = window
        artworkResolution?.cancel()
        artworkResolution = owner.lifecycleScope.launch {
            val uris = withContext(ArtworkWorkScheduler.dispatcher) { window.mapNotNull(metadata::logoDisplayUri) }
            artworkCache.prefetch(uris)
        }
    }
    suspend fun prepareInitialArtwork(first: Int, last: Int, center: Int) {
        if (stations.isEmpty()) return
        val start = first.coerceAtLeast(0).coerceAtMost(stations.lastIndex)
        val end = last.coerceAtLeast(start).coerceAtMost(stations.lastIndex)
        val positions = (start..end).sortedBy { kotlin.math.abs(it - center) }
        val window = positions.map(stations::get)
        val uris = withContext(ArtworkWorkScheduler.dispatcher) { window.mapNotNull(metadata::logoDisplayUri) }
        uris.forEach { artworkCache.restore(it) }
        artworkCache.waitForInitialRemoteArt(uris)
    }
    fun refreshRange(first: Int, last: Int) {
        if (stations.isEmpty()) return
        val start = first.coerceAtLeast(0).coerceAtMost(stations.lastIndex)
        val end = last.coerceAtLeast(start).coerceAtMost(stations.lastIndex)
        notifyItemRangeChanged(start, end - start + 1, StationArtworkPayload)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val wrapper = PhoneUi.column(c).apply {
            if (compact) layoutParams = RecyclerView.LayoutParams(-1, -2)
        }
        val row = PhoneUi.row(c).apply { minimumHeight = PhoneUi.dp(c, if (compact) 48 else 56) }
        val selected = View(c)
        row.addView(selected, LinearLayout.LayoutParams(PhoneUi.dp(c, 3), PhoneUi.dp(c, 48)))
        val art = FrameLayout(c).apply { background = PhoneUi.shape(c, PhoneUi.SURFACE, 8); clipToOutline = true }
        val initials = PhoneUi.text(c, size = 18f).apply { gravity = Gravity.CENTER }
        val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        art.addView(initials, FrameLayout.LayoutParams(-1, -1)); art.addView(logo, FrameLayout.LayoutParams(-1, -1))
        row.addView(art, LinearLayout.LayoutParams(PhoneUi.dp(c, 40), PhoneUi.dp(c, 40)).apply {
            marginStart = PhoneUi.dp(c, 10); marginEnd = PhoneUi.dp(c, 12)
        })
        val text = PhoneUi.column(c)
        val title = PhoneUi.text(c, size = 18f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = PhoneUi.text(c, size = 14f, muted = true).apply { maxLines = if (compact) 1 else 2; ellipsize = TextUtils.TruncateAt.END }
        text.addView(title); text.addView(subtitle)
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val heart = PhoneUi.icon(c, R.drawable.ic_star_outline, "Favorite station").apply {
            setPadding(PhoneUi.dp(c, 8), PhoneUi.dp(c, 8), PhoneUi.dp(c, 8), PhoneUi.dp(c, 8))
        }
        row.addView(heart)
        row.isClickable = true; row.isFocusable = true
        wrapper.addView(row); wrapper.addView(PhoneUi.divider(c))
        return Holder(wrapper, row, selected, title, subtitle, initials, logo, heart)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        bind(holder, position, refreshArtwork = false)
    }
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        bind(holder, position, refreshArtwork = StationArtworkPayload in payloads)
    }
    private fun bind(holder: Holder, position: Int, refreshArtwork: Boolean) {
        val station = stations[position]; val playing = station.id == PlaybackStatusBus.stationId.value
        holder.row.tag = "phone_station_${station.id}"
        holder.title.text = station.name
        val now = PlaybackStatusBus.nowPlaying.value
        holder.subtitle.text = when {
            repo.isDetachedFavorite(station.id) -> "Not in current playlists"
            playing && !now?.songTitle.isNullOrBlank() -> listOfNotNull(now?.songTitle, now?.artist).joinToString(" — ")
            else -> station.genre
        }
        holder.selected.setBackgroundColor(if (playing) net.mspanc.twinsenradio.data.AppearanceTheme.current(holder.row.context).accent else PhoneUi.CANVAS)
        val favorite = station.id in prefs.favourites
        holder.heart.setImageResource(if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        holder.heart.isSelected = favorite
        holder.heart.contentDescription = if (favorite) "Remove ${station.name} from Favorites" else "Add ${station.name} to Favorites"
        holder.heart.setOnClickListener { prefs.toggleFavourite(station.id) }
        holder.row.setOnClickListener { play(station) }
        val uri = metadata.logoDisplayUri(station)
        val fallback = metadata.logoResId(station)
        val artworkKey = "${uri?.toString() ?: "resource"}:$fallback:${station.name}"
        if (refreshArtwork || holder.artworkStationId != station.id ||
            holder.artworkKey != artworkKey
        ) {
            holder.artworkStationId = station.id
            holder.artworkKey = artworkKey
            holder.initials.visibility = View.GONE
            holder.logo.visibility = View.VISIBLE
            if (uri == null && fallback != R.drawable.logo_placeholder) {
                holder.logo.setImageResource(fallback)
            } else {
                holder.logo.setImageDrawable(artworkCache.drawable(uri, station.name))
            }
        }
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        val position = holder.bindingAdapterPosition
        if (position in stations.indices && holder.logo.drawable is PhoneStationArtwork) {
            bind(holder, position, refreshArtwork = true)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.initials.visibility = View.GONE
        holder.logo.setImageDrawable(null)
        holder.artworkStationId = null
        holder.artworkKey = null
        super.onViewRecycled(holder)
    }
    class Holder(root: View, val row: View, val selected: View, val title: TextView, val subtitle: TextView,
        val initials: TextView, val logo: ImageView, val heart: ImageButton) : RecyclerView.ViewHolder(root) {
        var artworkStationId: String? = null
        var artworkKey: String? = null
    }

    private companion object { const val PREFETCH_ROWS = 32 }
}

/** Reveal a station once per selection, without snapping back on metadata or artwork updates. */
@UnstableApi
internal class PhoneStationFollow {
    private var selection: Pair<String, String>? = null
    private var motion: ValueAnimator? = null
    private var observedList: RecyclerView? = null
    private val touchListener = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) stop()
            return false
        }
    }

    fun reveal(list: RecyclerView, adapter: PhoneStationAdapter, collection: String, stationId: String?) {
        if (observedList !== list) {
            stop()
            observedList?.removeOnItemTouchListener(touchListener)
            observedList = list
            list.addOnItemTouchListener(touchListener)
        }
        if (stationId == null) {
            stop()
            selection = null
            return
        }
        val key = collection to stationId
        if (key == selection) return
        stop()
        if (adapter.positionOf(stationId) < 0) {
            selection = null
            return
        }
        selection = key
        list.doOnLayout {
            if (selection != key) return@doOnLayout
            if (list.adapter !== adapter || !list.isShown) {
                selection = null
                return@doOnLayout
            }
            if (list.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) return@doOnLayout
            val position = adapter.positionOf(stationId)
            val layout = list.layoutManager as? LinearLayoutManager ?: return@doOnLayout
            if (position < 0) return@doOnLayout
            val distance = remainingDistance(list, layout, position)
            if (distance == 0f) return@doOnLayout
            if (!ValueAnimator.areAnimatorsEnabled()) {
                layout.scrollToPositionWithOffset(position, list.paddingTop)
                return@doOnLayout
            }

            list.stopScroll()
            animateTo(list, layout, adapter, key, stationId, distance)
        }
    }

    fun reset() {
        stop()
        cancelCentering()
        selection = null
    }

    private var centerList: RecyclerView? = null
    private var centerListener: ViewTreeObserver.OnPreDrawListener? = null
    val isCentering get() = centerListener != null

    private fun cancelCentering() {
        val observer = centerList?.viewTreeObserver
        val listener = centerListener
        if (observer?.isAlive == true && listener != null) observer.removeOnPreDrawListener(listener)
        centerList = null
        centerListener = null
    }

    /** Center the active row before allowing the list's first entry frame to draw. */
    fun centerBeforeDraw(list: RecyclerView, adapter: PhoneStationAdapter, collection: String, stationId: String?) {
        if (stationId == null) return
        cancelCentering()
        val key = collection to stationId
        stop()
        selection = key
        list.stopScroll()
        var estimated = false
        var positioned = false
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val position = adapter.positionOf(stationId)
                val layout = list.layoutManager as? LinearLayoutManager
                if (positioned || list.adapter !== adapter || !list.isShown || position < 0 || layout == null) {
                    cancelCentering()
                    return true
                }
                val row = layout.findViewByPosition(position)
                if (row == null) {
                    if (estimated || list.height == 0) {
                        cancelCentering()
                        return true
                    }
                    layout.scrollToPositionWithOffset(position, list.height / 2 - PhoneUi.dp(list.context, 28))
                    estimated = true
                } else {
                    val rowHeight = layout.getDecoratedMeasuredHeight(row)
                    layout.scrollToPositionWithOffset(position, (list.height - rowHeight) / 2)
                    positioned = true
                }
                list.requestLayout()
                return false
            }
        }
        centerList = list
        centerListener = listener
        list.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun animateTo(
        list: RecyclerView, layout: LinearLayoutManager, adapter: PhoneStationAdapter,
        key: Pair<String, String>, stationId: String, distance: Float
    ) {
        val screens = kotlin.math.abs(distance) / list.height.coerceAtLeast(1)
        var progress = 0f
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (320 + 370 * kotlin.math.sqrt(screens)).toLong().coerceAtMost(1_650)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val position = adapter.positionOf(stationId)
                if (list.adapter !== adapter || !list.isShown || position < 0) {
                    stop()
                    return@addUpdateListener
                }
                val next = it.animatedValue as Float
                if (progress == 1f || next == progress) return@addUpdateListener
                // Re-estimate from bound rows, then use the exact distance once the target is laid out.
                val remaining = remainingDistance(list, layout, position)
                val step = remaining * ((next - progress) / (1f - progress))
                list.scrollBy(0, kotlin.math.round(step).toInt())
                progress = next
            }
            doOnEnd {
                motion = null
                list.post {
                    if (selection != key ||
                        list.adapter !== adapter || !list.isShown
                    ) return@post
                    val position = adapter.positionOf(stationId)
                    if (position < 0) return@post
                    val target = layout.findViewByPosition(position)
                    if (target == null ||
                            layout.getDecoratedTop(target) < list.paddingTop ||
                            layout.getDecoratedBottom(target) > list.height - list.paddingBottom
                    ) {
                        // Estimates are only used while the target is far offscreen;
                        // anchor it exactly once variable-height rows are encountered.
                        layout.scrollToPositionWithOffset(position, list.paddingTop)
                    }
                }
            }
        }
        motion?.start()
    }

    private fun remainingDistance(list: RecyclerView, layout: LinearLayoutManager, position: Int): Float {
        val top = list.paddingTop
        val bottom = list.height - list.paddingBottom
        layout.findViewByPosition(position)?.let { target ->
            val targetTop = layout.getDecoratedTop(target)
            val targetBottom = layout.getDecoratedBottom(target)
            return when {
                targetTop < top || targetBottom - targetTop > bottom - top -> (targetTop - top).toFloat()
                targetBottom > bottom -> (targetBottom - bottom).toFloat()
                else -> 0f
            }
        }
        val first = layout.findFirstVisibleItemPosition()
        val last = layout.findLastVisibleItemPosition()
        val firstTop = layout.findViewByPosition(first)?.let(layout::getDecoratedTop) ?: return 0f
        val lastBottom = layout.findViewByPosition(last)?.let(layout::getDecoratedBottom) ?: return 0f
        val rowHeight = (lastBottom - firstTop).toFloat() / (last - first + 1)
        return if (position < first) firstTop - top - (first - position) * rowHeight
            else lastBottom - bottom + (position - last) * rowHeight
    }

    private fun stop() {
        motion?.removeAllUpdateListeners()
        motion?.removeAllListeners()
        motion?.cancel()
        motion = null
    }

}
