package net.mspanc.twinsenradio.phone

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.data.PlaylistSource
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.playback.PlaybackStatusBus

/** A source-order view; opening it does not choose a playback collection. */
@UnstableApi
class PhonePlaylistActivity : PhonePlaybackActivity() {
    private lateinit var heading: TextView
    private lateinit var location: TextView
    private lateinit var count: TextView
    private lateinit var error: TextView
    private lateinit var refresh: Button
    private lateinit var list: RecyclerView
    private lateinit var adapter: PhoneStationAdapter
    private val stationFollow = PhoneStationFollow()
    private val model by lazy { ViewModelProvider(this)[RefreshModel::class.java] }
    private val sourceId get() = intent.getStringExtra("source_id").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { renderContent() }
            }
        }
    }

    override fun onStop() {
        stationFollow.reset()
        super.onStop()
    }

    override fun stationAreaReady() = !stationFollow.isCentering

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderContent()
    }

    override fun buildBody(): View = PhoneUi.column(this).apply {
        val header = PhoneUi.row(this@PhonePlaylistActivity)
        heading = PhoneUi.heading(this@PhonePlaylistActivity, "Playlist")
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(PhoneUi.button(this@PhonePlaylistActivity, "Done", true) { finish() })
        addView(header)
        location = PhoneUi.text(this@PhonePlaylistActivity, muted = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        count = PhoneUi.text(this@PhonePlaylistActivity, muted = true)
        error = PhoneUi.text(this@PhonePlaylistActivity, size = 14f).apply { setTextColor(PhoneUi.WARNING) }
        addView(location); addView(count); addView(error)
        refresh = PhoneUi.button(this@PhonePlaylistActivity, "Refresh playlist") {
            repo.playlists().firstOrNull { it.id == sourceId }?.let { source -> model.refresh(repo, source) }
        }
        addView(refresh)
        list = RecyclerView(this@PhonePlaylistActivity).apply { layoutManager = LinearLayoutManager(context) }
        adapter = PhoneStationAdapter(this@PhonePlaylistActivity, prefs, repo) { station ->
            playStation(station, "/playlist/$sourceId", explicitCollectionSelection = true)
        }
        list.adapter = adapter; addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        animateStationArea(list)
    }

    override fun renderContent() {
        if (!::list.isInitialized) return
        val source = repo.playlists().firstOrNull { it.id == sourceId }
        heading.text = source?.name ?: "Playlist unavailable"
        location.text = if (source?.isLocal == true) "Local import · File changes are not synced" else source?.url.orEmpty()
        location.contentDescription = location.text
        val stations = if (source == null) emptyList() else repo.sourceStations(sourceId)
        count.text = "${stations.size} saved stations"
        adapter.update(stations)
        val cast = PhoneCastRouting.get(applicationContext).displaySnapshot()
        val activeStation = if (cast == null) PlaybackStatusBus.stationId.value else cast.stationId
        if (intent.getBooleanExtra(PhonePlayerActivity.CENTER_ACTIVE_STATION, false)) {
            intent.removeExtra(PhonePlayerActivity.CENTER_ACTIVE_STATION)
            stationFollow.centerBeforeDraw(list, adapter, sourceId, activeStation)
        } else stationFollow.reveal(list, adapter, sourceId, activeStation)
        val state = model.state.value
        error.text = when {
            source == null -> "This playlist is no longer available."
            state.failed -> "Could not refresh playlist. Keeping the last saved stations."
            state.busy -> "Refreshing playlist…"
            else -> ""
        }
        refresh.visibility = if (source != null && !source.isLocal) View.VISIBLE else View.GONE
        refresh.isEnabled = !state.busy
        refresh.text = if (state.failed) "Retry refresh" else "Refresh playlist"
    }

    class RefreshModel : ViewModel() {
        data class State(val busy: Boolean = false, val failed: Boolean = false)
        val state = MutableStateFlow(State())
        fun refresh(repo: StationRepository, source: PlaylistSource) {
            if (state.value.busy || source.isLocal) return
            state.value = State(busy = true)
            viewModelScope.launch {
                try {
                    repo.savePlaylist(source.name, source.url, source.id)
                    state.value = State()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { state.value = State(failed = true) }
            }
        }
    }
}
