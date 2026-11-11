package net.mspanc.twinsenradio.phone

import android.database.Cursor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.AppearanceTheme
import net.mspanc.twinsenradio.data.CatalogLimits
import net.mspanc.twinsenradio.data.M3uParser
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.playback.MetadataFactory
import net.mspanc.twinsenradio.playback.StationArtworkStore
import net.mspanc.twinsenradio.ui.ArtworkLoader
import java.io.FileNotFoundException

/** Add a remote station catalog or import a local M3U copy into the phone library. */
@androidx.media3.common.util.UnstableApi
class PhoneImportActivity : PhonePlaybackActivity() {
    private lateinit var headerTitle: TextView
    private lateinit var urlGroup: LinearLayout
    private lateinit var localGroup: LinearLayout
    private lateinit var urlField: EditText
    private lateinit var urlNameField: EditText
    private lateinit var localNameField: EditText
    private lateinit var fileName: TextView
    private lateinit var fileSummary: TextView
    private lateinit var previewRows: LinearLayout
    private lateinit var message: TextView
    private lateinit var chooseInitialFileButton: Button
    private lateinit var chooseFileButton: Button
    private lateinit var importFileButton: Button
    private lateinit var addUrlButton: Button
    private lateinit var cancelButton: TextView
    private val previewArtwork = mutableListOf<ImageView>()
    private val previewTargets = mutableListOf<Pair<net.mspanc.twinsenradio.data.Station, ImageView>>()
    private var previewPreloadJob: Job? = null
    private var previewPreloadSnapshot: ImportPreview? = null
    private val importState by lazy { androidx.lifecycle.ViewModelProvider(this)[PhoneImportState::class.java] }
    private var busy = false
    private var restoredUrl = ""
    private var restoredUrlName = ""
    private var restoredLocalName = ""

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readPickedFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        restoredUrl = savedInstanceState?.getString(STATE_URL).orEmpty().take(CatalogLimits.MAX_URL)
        restoredUrlName = savedInstanceState?.getString(STATE_URL_NAME).orEmpty().take(CatalogLimits.MAX_NAME)
        restoredLocalName = savedInstanceState?.getString(STATE_LOCAL_NAME).orEmpty().take(CatalogLimits.MAX_NAME)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!busy) finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        startPreviewPreload()
    }

    override fun onStop() {
        cancelPreviewPreload()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::urlField.isInitialized) outState.putString(STATE_URL, urlField.text.toString().take(CatalogLimits.MAX_URL))
        if (::urlNameField.isInitialized) outState.putString(STATE_URL_NAME, urlNameField.text.toString().take(CatalogLimits.MAX_NAME))
        if (::localNameField.isInitialized) outState.putString(STATE_LOCAL_NAME, localNameField.text.toString().take(CatalogLimits.MAX_NAME))
        super.onSaveInstanceState(outState)
    }

    override fun buildBody(): View {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CANVAS)
        }
        val header = object : android.widget.FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                cancelButton.measure(
                    View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val inset = maxOf(dp(56), cancelButton.measuredWidth) + dp(8)
                headerTitle.setPadding(inset, 0, inset, 0)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            minimumHeight = dp(56)
        }
        cancelButton = TextView(this).apply {
            text = "Cancel"
            textSize = 16f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            minimumWidth = dp(56)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        headerTitle = label("Add playlist", 20f, TEXT, true).apply { gravity = Gravity.CENTER }
        header.addView(headerTitle, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        header.addView(cancelButton, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        screen.addView(header)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        urlGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        urlGroup.addView(label("Playlist URL", 14f, MUTED, true))
        urlField = field("https://example.com/playlist.m3u", "Playlist URL", CatalogLimits.MAX_URL).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(restoredUrl)
        }
        urlGroup.addView(urlField, fieldParams())
        urlGroup.addView(label("Name (optional)", 14f, MUTED, true).apply {
            setPadding(0, dp(10), 0, 0)
        })
        urlNameField = field("", "Hosted playlist name", CatalogLimits.MAX_NAME).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(restoredUrlName)
        }
        urlGroup.addView(urlNameField, fieldParams())
        addUrlButton = textButton("Add playlist", primary = true) { saveUrlPlaylist() }
        urlGroup.addView(addUrlButton, actionParams(top = 12))
        urlGroup.addView(label(
            "Hosted playlists refresh. A saved copy stays available if refresh fails.", 14f, MUTED
        ).apply { setPadding(0, dp(8), 0, 0) })
        urlGroup.addView(View(this).apply { setBackgroundColor(BORDER) },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            })
        urlGroup.addView(label("Or import a local file", 18f, TEXT, true))
        chooseInitialFileButton = textButton("Choose M3U file", primary = false, outlined = true) { openPicker() }
        urlGroup.addView(chooseInitialFileButton, actionParams(top = 10))
        urlGroup.addView(label("Saved on this device. Original file changes are not synced.", 14f, MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(urlGroup)

        localGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val fileCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = PhoneUi.shape(this@PhoneImportActivity, SURFACE, 12)
        }
        fileName = label("", 16f, TEXT, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        fileCard.addView(fileName)
        fileSummary = label("", 14f, MUTED)
        fileCard.addView(fileSummary)
        localGroup.addView(fileCard, LinearLayout.LayoutParams(-1, -2))
        localGroup.addView(label("Name (optional)", 14f, MUTED, true).apply {
            setPadding(0, dp(8), 0, 0)
        })
        localNameField = field("", "Local playlist name", CatalogLimits.MAX_NAME).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(restoredLocalName)
        }
        localGroup.addView(localNameField, fieldParams())
        previewRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        localGroup.addView(previewRows)
        localGroup.addView(label(
            "A copy will be saved on this device. The original file stays unchanged.", 14f, MUTED
        ).apply { setPadding(0, dp(8), 0, 0) })
        chooseFileButton = textButton("Choose another file", primary = false, outlined = true) { openPicker() }
        localGroup.addView(chooseFileButton, actionParams(top = 8))
        importFileButton = textButton("Import playlist", primary = true) { saveLocalPlaylist() }
        localGroup.addView(importFileButton, actionParams(top = 8))
        content.addView(localGroup)
        message = label("", 14f, MUTED).apply {
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
            doAfterTextChanged { value -> visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE }
        }
        content.addView(message)
        scroll.addView(content)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        renderSelectedFile()
        return screen
    }

    private fun openPicker() {
        if (!busy) filePicker.launch(arrayOf(
            "audio/x-mpegurl", "application/x-mpegurl", "audio/mpegurl",
            "application/vnd.apple.mpegurl", "text/*", "application/octet-stream", "*/*"
        ))
    }

    private fun readPickedFile(uri: Uri) {
        if (busy) return
        cancelPreviewPreload()
        setBusy(true)
        message.text = "Reading file…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val name = displayName(uri) ?: "M3U file"
                    val input = contentResolver.openInputStream(uri)
                        ?: throw FileNotFoundException("Could not open the selected file.")
                    val parsed = PhoneM3uFile.read(input).let(PhoneM3uFile::parse)
                    ImportPreview(name, parsed)
                }
                importState.preview = result
                if (localNameField.text.isNullOrBlank()) {
                    localNameField.setText(result.name.substringBeforeLast('.', result.name)
                        .take(CatalogLimits.MAX_NAME))
                }
                renderSelectedFile()
                message.text = ""
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.text = error.message ?: "Could not read this M3U file. Choose another file."
            } finally {
                if (!isFinishing && !isDestroyed) setBusy(false)
                startPreviewPreload()
            }
        }
    }

    private fun renderSelectedFile() {
        cancelPreviewPreload()
        if (::previewRows.isInitialized) {
            previewArtwork.forEach { image ->
                ArtworkLoader.into(
                    lifecycleScope, null, null, android.R.color.transparent, image
                )
            }
            previewArtwork.clear()
            previewTargets.clear()
            previewRows.removeAllViews()
        }
        val selected = importState.preview
        val hasFile = selected != null
        urlGroup.visibility = if (hasFile) View.GONE else View.VISIBLE
        localGroup.visibility = if (hasFile) View.VISIBLE else View.GONE
        headerTitle.text = if (hasFile) "Import local playlist" else "Add playlist"
        if (!hasFile) return
        fileName.text = selected.name
        fileSummary.text = "${selected.parsed.stations.size} stations found"
        val prefs = Prefs(this)
        val metadata = MetadataFactory(this, prefs)
        selected.parsed.stations.take(3).forEachIndexed { index, station ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
            }
            val artwork = FrameLayout(this).apply {
                background = PhoneUi.shape(this@PhoneImportActivity, SURFACE, 8)
                clipToOutline = true
            }
            val initials = label(
                station.name.split(Regex("\\s+")).mapNotNull { it.firstOrNull { char -> char.isLetter() } }
                    .take(2).joinToString("").uppercase().ifBlank { "FM" },
                14f, TEXT, true
            ).apply { gravity = Gravity.CENTER }
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            artwork.addView(initials, FrameLayout.LayoutParams(-1, -1))
            artwork.addView(image, FrameLayout.LayoutParams(-1, -1))
            row.addView(artwork, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(4)
                marginEnd = dp(12)
            })
            row.addView(label(station.name, 16f, TEXT).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            previewRows.addView(row, LinearLayout.LayoutParams(-1, -2))
            previewArtwork.add(image)
            previewTargets.add(station to image)
            val uri = previewLogoUri(station, prefs)
            val fallback = metadata.logoResId(station)
            val showInitials = uri == null && fallback == net.mspanc.twinsenradio.R.drawable.logo_placeholder
            initials.visibility = if (showInitials) View.VISIBLE else View.GONE
            image.visibility = if (uri != null || !showInitials) View.VISIBLE else View.GONE
            if (uri != null) ArtworkLoader.into(
                lifecycleScope, null, uri,
                if (showInitials) android.R.color.transparent else fallback,
                image,
                fallbackDrawable = if (fallback == net.mspanc.twinsenradio.R.drawable.logo_placeholder)
                    PhoneStationArtwork(station.name) else null
            ) else if (!showInitials) image.setImageResource(fallback)
            if (index < minOf(2, selected.parsed.stations.lastIndex)) {
                previewRows.addView(View(this).apply { setBackgroundColor(BORDER) },
                    LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        importFileButton.isEnabled = true
        startPreviewPreload()
    }

    private fun startPreviewPreload() {
        val selected = importState.preview ?: return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (previewPreloadSnapshot === selected && previewPreloadJob?.isActive == true) return
        cancelPreviewPreload()
        val visibleStations = selected.parsed.stations.take(3)
        if (visibleStations.isEmpty()) return
        previewPreloadSnapshot = selected
        previewPreloadJob = lifecycleScope.launch {
            StationArtworkStore.preloadPreview(
                applicationContext, visibleStations
            ) { changedStation ->
                if (importState.preview !== selected || previewPreloadSnapshot !== selected ||
                    isFinishing || isDestroyed
                ) return@preloadPreview
                val cached = StationArtworkStore.file(
                    applicationContext, M3uParser.stableKey(changedStation.id)
                ) ?: return@preloadPreview
                val fallback = MetadataFactory(this@PhoneImportActivity, Prefs(this@PhoneImportActivity))
                    .logoResId(changedStation)
                previewTargets.filter { it.first.id == changedStation.id }.forEach { (_, image) ->
                    image.visibility = View.VISIBLE
                    (image.parent as? FrameLayout)?.getChildAt(0)?.visibility = View.GONE
                    ArtworkLoader.into(
                        lifecycleScope, null, Uri.fromFile(cached),
                        if (fallback == net.mspanc.twinsenradio.R.drawable.logo_placeholder)
                            android.R.color.transparent else fallback,
                        image,
                        fallbackDrawable = if (fallback == net.mspanc.twinsenradio.R.drawable.logo_placeholder)
                            PhoneStationArtwork(changedStation.name) else null
                    )
                }
            }
        }
    }

    private fun cancelPreviewPreload() {
        previewPreloadJob?.cancel()
        previewPreloadJob = null
        previewPreloadSnapshot = null
    }

    private fun previewLogoUri(station: net.mspanc.twinsenradio.data.Station, prefs: Prefs): Uri? {
        prefs.customLogo(station.id)?.let { path ->
            java.io.File(path).takeIf { it.isFile }?.let { return Uri.fromFile(it) }
        }
        if (!prefs.onlineArtworkEnabled) return null
        if (StationArtworkStore.isGeneric(this, station)) return null
        if (StationArtworkStore.hasDownloaded(this, station)) {
            return StationArtworkStore.file(this, M3uParser.stableKey(station.id))?.let(Uri::fromFile)
        }
        station.logoUrl?.takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
        return null
    }

    private fun displayName(uri: Uri): String? {
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun saveUrlPlaylist() {
        if (busy) return
        val url = urlField.text.toString().trim()
        if (url.isBlank()) {
            message.text = "Enter a playlist URL first."
            urlField.requestFocus()
            return
        }
        setBusy(true)
        message.text = "Validating and adding playlist…"
        lifecycleScope.launch {
            try {
                repo.savePlaylist(urlNameField.text.toString().trim(), url)
                if (!isFinishing && !isDestroyed) finish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isFinishing && !isDestroyed) message.text = error.message
                    ?: "Could not add playlist. Check the URL and try again."
            } finally {
                if (!isFinishing && !isDestroyed) setBusy(false)
            }
        }
    }

    private fun saveLocalPlaylist() {
        val selected = importState.preview ?: return
        if (busy) return
        setBusy(true)
        message.text = "Importing playlist…"
        lifecycleScope.launch {
            try {
                val fallback = selected.name.substringBeforeLast('.', selected.name)
                    .ifBlank { "Imported playlist" }.take(CatalogLimits.MAX_NAME)
                val name = localNameField.text.toString().trim().ifBlank { fallback }
                repo.saveLocalPlaylist(name, selected.parsed.body)
                if (!isFinishing && !isDestroyed) finish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isFinishing && !isDestroyed) message.text = error.message
                    ?: "Could not import playlist. Your saved playlists are unchanged."
            } finally {
                if (!isFinishing && !isDestroyed) setBusy(false)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        cancelButton.isEnabled = !value
        addUrlButton.isEnabled = !value
        chooseInitialFileButton.isEnabled = !value
        chooseFileButton.isEnabled = !value
        importFileButton.isEnabled = !value && importState.preview != null
        urlField.isEnabled = !value
        urlNameField.isEnabled = !value
        localNameField.isEnabled = !value
    }

    private fun field(hintText: String, description: String, maximum: Int) = EditText(this).apply {
        hint = hintText
        contentDescription = description
        textSize = 16f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(maximum))
        setPadding(dp(14), 0, dp(14), 0)
        background = rounded(FIELD, BORDER, 12)
    }

    private fun fieldParams() = LinearLayout.LayoutParams(-1, dp(48)).apply {
        topMargin = dp(6)
    }

    private fun label(value: String, size: Float, color: Int, medium: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (medium) setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun textButton(
        caption: String,
        primary: Boolean,
        outlined: Boolean = false,
        click: () -> Unit
    ) = Button(this).apply {
            text = caption
            textSize = 16f
            isAllCaps = false
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            minimumHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { click() }
            val theme = AppearanceTheme.current(this@PhoneImportActivity)
            backgroundTintList = null
            background = when {
                primary -> rounded(theme.accent, theme.accent, 12)
                outlined -> rounded(FIELD, BORDER, 12)
                else -> rounded(CONTROL, BORDER, 12)
            }
            setTextColor(if (primary) TEXT else TEXT)
        }

    private fun actionParams(top: Int) = LinearLayout.LayoutParams(-1, dp(48)).apply {
        topMargin = dp(top)
    }

    private fun rounded(color: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    companion object {
        private const val STATE_URL = "phone_import_url"
        private const val STATE_URL_NAME = "phone_import_url_name"
        private const val STATE_LOCAL_NAME = "phone_import_local_name"
        private const val CANVAS = 0xff0d0e12.toInt()
        private const val SURFACE = 0xff20252c.toInt()
        private const val CONTROL = 0xff333a43.toInt()
        private const val FIELD = 0xff15191e.toInt()
        private const val TEXT = 0xfff4f5f7.toInt()
        private const val MUTED = 0xffbdc6d0.toInt()
        private const val BORDER = 0xff778390.toInt()
    }
}

internal class PhoneImportState : ViewModel() {
    var preview: ImportPreview? = null
}

internal data class ImportPreview(val name: String, val parsed: PhoneM3uFile.Parsed)
