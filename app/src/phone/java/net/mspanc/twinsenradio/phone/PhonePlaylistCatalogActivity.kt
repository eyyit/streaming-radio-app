package net.mspanc.twinsenradio.phone

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.AppearanceTheme
import net.mspanc.twinsenradio.data.PlaylistSource
import net.mspanc.twinsenradio.data.StationRepository

/** Saved playlist browsing and management, reached from Settings. */
@OptIn(UnstableApi::class)
class PhonePlaylistCatalogActivity : AppCompatActivity() {
    private val repo by lazy { StationRepository.get(applicationContext) }
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
        val row = PhoneUi.column(this).apply {
            minimumHeight = dp(72)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@PhonePlaylistCatalogActivity, PhonePlaylistActivity::class.java)
                    .putExtra("source_id", source.id))
            }
        }
        val name = PhoneUi.text(this, source.name, 18f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(name)
        val detailText = "${repo.sourceStations(source.id).size} stations · ${if (source.isLocal) "Local import" else "Hosted playlist"}"
        row.addView(PhoneUi.text(this, detailText, 14f, true))
        entries.addView(row)
        entries.addView(PhoneUi.divider(this))
    }

    private fun dp(value: Int) = PhoneUi.dp(this, value)
}
