package net.mspanc.twinsenradio.phone

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.AppearanceTheme
import net.mspanc.twinsenradio.data.PlaylistSource
import net.mspanc.twinsenradio.data.StationRepository

/** Saved playlist browsing and management, reached from Settings. */
@OptIn(UnstableApi::class)
class PhonePlaylistCatalogActivity : AppCompatActivity() {
    private val repo by lazy { StationRepository.get(applicationContext) }
    private val model by lazy { ViewModelProvider(this)[RefreshModel::class.java] }
    private lateinit var entries: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = PhoneUi.CANVAS
        val root = PhoneUi.column(this).apply { setBackgroundColor(PhoneUi.CANVAS) }
        val content = PhoneUi.column(this).apply { setPadding(dp(20), dp(12), dp(20), dp(16)) }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val header = PhoneUi.row(this)
        header.addView(PhoneUi.heading(this, getString(R.string.phone_settings_playlists)),
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(PhoneUi.button(this, getString(R.string.phone_settings_done), true) { finish() }.apply {
            setTextColor(AppearanceTheme.current(this@PhonePlaylistCatalogActivity).onAccent)
        })
        content.addView(header)
        content.addView(PhoneUi.gap(this, 12))
        content.addView(PhoneUi.button(this, "+ Add playlist", true) {
            startActivity(Intent(this, PhoneImportActivity::class.java))
        })
        content.addView(PhoneUi.gap(this, 8))
        empty = PhoneUi.text(this, "No saved playlists yet. Add a hosted URL or import an M3U file.", 14f, true)
        content.addView(empty)
        entries = PhoneUi.column(this)
        content.addView(entries)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.states.collect { if (::entries.isInitialized) render() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::entries.isInitialized) render()
    }

    private fun render() {
        val sources = repo.playlists()
        empty.visibility = if (sources.isEmpty()) View.VISIBLE else View.GONE
        entries.removeAllViews()
        sources.forEach { source -> addEntry(source) }
    }

    private fun addEntry(source: PlaylistSource) {
        val state = model.states.value[source.id] ?: RefreshModel.EntryState()
        val row = PhoneUi.row(this).apply {
            minimumHeight = dp(72)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val info = PhoneUi.column(this).apply {
            isFocusable = true
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@PhonePlaylistCatalogActivity, PhonePlaylistActivity::class.java)
                    .putExtra("source_id", source.id))
            }
        }
        val name = PhoneUi.text(this, source.name, 18f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(name)
        val stationCount = repo.sourceStations(source.id).size
        val detailText = when {
            state.busy -> "$stationCount stations · Refreshing…"
            state.failed -> "Could not refresh · Keeping saved stations"
            else -> "$stationCount stations · ${if (source.isLocal) "Local import" else "Hosted playlist"}"
        }
        val detail = PhoneUi.text(this, detailText, 14f, muted = !state.failed).apply {
            if (state.failed) setTextColor(PhoneUi.WARNING)
        }
        info.addView(detail)
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        if (!source.isLocal) {
            val refreshBox = FrameLayout(this).apply {
                background = PhoneUi.shape(this@PhonePlaylistCatalogActivity, PhoneUi.CONTROL, 12)
            }
            if (state.busy) {
                val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(
                        AppearanceTheme.current(this@PhonePlaylistCatalogActivity).accent
                    )
                    contentDescription = "Refreshing ${source.name}"
                    tag = "phone_playlist_refresh_busy_${source.id}"
                }
                refreshBox.addView(spinner, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            } else {
                val refreshButton = PhoneUi.icon(
                    this,
                    R.drawable.phone_ic_artwork_refresh,
                    "Refresh ${source.name}",
                    size = 44
                ).apply {
                    tag = "phone_playlist_refresh_${source.id}"
                    setPadding(dp(11), dp(11), dp(11), dp(11))
                    setOnClickListener { model.refresh(repo, source) }
                }
                refreshBox.addView(refreshButton, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            }
            row.addView(refreshBox, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(12)
            })
        }
        val removeBox = FrameLayout(this).apply {
            background = PhoneUi.shape(this@PhonePlaylistCatalogActivity, PhoneUi.CONTROL, 12)
        }
        val removeButton = PhoneUi.icon(
            this,
            R.drawable.phone_ic_delete,
            "Remove ${source.name}",
            size = 44
        ).apply {
            tag = "phone_playlist_remove_${source.id}"
            imageTintList = ColorStateList.valueOf(PhoneUi.WARNING)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener { confirmRemove(source) }
        }
        removeBox.addView(removeButton, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        row.addView(removeBox, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginStart = dp(if (source.isLocal) 12 else 8)
        })
        entries.addView(row)
        entries.addView(PhoneUi.divider(this))
    }

    private fun confirmRemove(source: PlaylistSource) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove playlist?")
            .setMessage("Remove \"${source.name}\"? Stations only in this playlist will be removed from your library.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Remove") { _, _ ->
                model.clear(source.id)
                repo.removePlaylist(source.id)
                render()
            }
            .show()
    }

    private fun dp(value: Int) = PhoneUi.dp(this, value)

    class RefreshModel : ViewModel() {
        data class EntryState(val busy: Boolean = false, val failed: Boolean = false)
        val states = MutableStateFlow<Map<String, EntryState>>(emptyMap())

        fun clear(sourceId: String) {
            states.value = states.value - sourceId
        }

        fun refresh(repo: StationRepository, source: PlaylistSource) {
            if (source.isLocal || states.value[source.id]?.busy == true) return
            states.value = states.value + (source.id to EntryState(busy = true))
            viewModelScope.launch {
                try {
                    repo.savePlaylist(source.name, source.url, source.id)
                    states.value = states.value - source.id
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    states.value = states.value + (source.id to EntryState(failed = true))
                }
            }
        }
    }
}
