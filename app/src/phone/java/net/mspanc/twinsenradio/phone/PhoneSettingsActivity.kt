package net.mspanc.twinsenradio.phone

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.AppearanceTheme
import net.mspanc.twinsenradio.data.BufferProfile
import net.mspanc.twinsenradio.data.Prefs

/** Focused English settings for the phone app. */
@UnstableApi
class PhoneSettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = PhoneUi.CANVAS
        prefs = Prefs(this)

        val root = PhoneUi.column(this).apply {
            setBackgroundColor(PhoneUi.CANVAS)
        }
        val content = PhoneUi.column(this).apply {
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        buildContent(content)
        root.addView(
            PhoneCastUi.settingsRow(this),
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun buildContent(content: LinearLayout) {
        val header = PhoneUi.row(this)
        val title = PhoneUi.heading(this, getString(R.string.phone_settings_title))
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val done = PhoneUi.button(this, getString(R.string.phone_settings_done), primary = true) {
            finish()
        }.apply {
            minHeight = dp(48)
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(48))
            textSize = 14f
            setTextColor(AppearanceTheme.current(this@PhoneSettingsActivity).onAccent)
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(done)
        content.addView(header)

        content.addView(PhoneUi.gap(this, 16))
        addPreferenceRow(content, R.string.phone_settings_artwork) {
            showArtworkChoices()
        }
        content.addView(PhoneUi.divider(this))
        addPreferenceRow(content, R.string.phone_settings_buffer) {
            showBufferChoices()
        }
        content.addView(PhoneUi.divider(this))
        addPreferenceRow(content, R.string.phone_settings_playlists) {
            startActivity(android.content.Intent(this, PhonePlaylistCatalogActivity::class.java))
        }
        content.addView(PhoneUi.divider(this))

        content.addView(PhoneUi.gap(this, 12))
        content.addView(PhoneUi.text(this, getString(R.string.account_about), 16f).apply {
            setTypeface(typeface, Typeface.BOLD)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        content.addView(PhoneUi.gap(this, 6))
        content.addView(PhoneUi.text(this, getString(R.string.twinsen_attribution), 12f, muted = true).apply {
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            linksClickable = true
            setLinkTextColor(PhoneUi.TEXT)
        })
        content.addView(PhoneUi.divider(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) }
        })
        addPreferenceRow(content, R.string.privacy_policy) {
            showPrivacyPolicy()
        }
        content.addView(PhoneUi.divider(this))
    }

    private fun addPreferenceRow(parent: LinearLayout, label: Int, action: () -> Unit) {
        val row = PhoneUi.row(this).apply {
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(PhoneUi.text(this, getString(label), 16f))
        parent.addView(row)
    }

    private fun showArtworkChoices() {
        val choices = arrayOf(
            getString(R.string.phone_settings_artwork_online),
            getString(R.string.phone_settings_artwork_local)
        )
        val selected = if (prefs.onlineArtworkEnabled) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.phone_settings_artwork)
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                prefs.onlineArtworkEnabled = which == 0
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBufferChoices() {
        val choices = listOf(
            R.string.phone_settings_buffer_small,
            R.string.phone_settings_buffer_medium,
            R.string.phone_settings_buffer_large
        ).map { resource ->
            SpannableString(getText(resource)).apply {
                val titleEnd = indexOf('\n')
                if (titleEnd < 0) return@apply
                val descriptionStart = titleEnd + 1
                setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.875f), descriptionStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(PhoneUi.MUTED), descriptionStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val selected = prefs.bufferProfile.coerceIn(BufferProfile.ALL.indices)
        val builder = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_StreamingRadioPhone_BufferDialog)
        val indicatorTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(AppearanceTheme.current(this).accent, PhoneUi.MUTED)
        )
        val adapter = object : ArrayAdapter<CharSequence>(
            builder.context,
            com.google.android.material.R.layout.mtrl_alert_select_dialog_singlechoice,
            choices
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    minimumHeight = dp(64)
                    setPaddingRelative(paddingStart, dp(8), paddingEnd, dp(8))
                    setTextColor(PhoneUi.TEXT)
                    compoundDrawableTintList = indicatorTint
                }
        }
        builder
            .setTitle(R.string.phone_settings_buffer)
            .setSingleChoiceItems(adapter, selected) { dialog, which ->
                prefs.bufferProfile = which
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPrivacyPolicy() {
        val policy = assets.open("legal/privacy-policy.txt")
            .bufferedReader().use { it.readText() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_policy)
            .setMessage(policy)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dp(value: Int) = PhoneUi.dp(this, value)
}
