package net.mspanc.twinsenradio.automotive

import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mspanc.twinsenradio.data.AppearanceTheme
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.playback.ArtworkWorkScheduler
import net.mspanc.twinsenradio.playback.StationArtworkStore

/** Parked-only picker. A selection is committed only by Use this image. */
object StationImageDialog {
    private const val SURFACE = AutomotiveDesign.SURFACE
    private const val TEXT = AutomotiveDesign.TEXT
    private const val MUTED = AutomotiveDesign.MUTED
    private const val BORDER = AutomotiveDesign.BORDER

    fun show(activity: Activity, station: Station, scope: CoroutineScope,
             changed: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
        val prefs = Prefs(activity)
        val theme = AppearanceTheme.current(activity)
        if (!prefs.onlineArtworkEnabled) {
            showDisabled(activity, onOpenSettings)
            return
        }
        val candidates = mutableListOf<StationArtworkStore.ImageCandidate>()
        var selected = -1
        var genericSelected = StationArtworkStore.isGeneric(activity, station)
        var working = false
        var generation = 0
        var job: Job? = null
        var previewJob: Job? = null
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(14), dp(30), dp(12))
        }
        root.addView(label(activity, "Station image · ${station.name}", 25f, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, dp(36)))
        root.addView(label(activity, "Station-directory logos · not Google Images", 15f).apply {
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(-1, dp(27)))
        val dialogWidth = minOf(dp(900), visibleBounds(activity).width() - dp(32)).coerceAtLeast(dp(280))
        val wide = dialogWidth >= dp(760)
        val body = LinearLayout(activity).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val previewSize = if (wide) dp(310) else minOf(dp(310), dialogWidth - dp(56))
        val previewFrame = FrameLayout(activity).apply {
            background = rounded(0xff315b64.toInt(), 20); clipToOutline = true
        }
        previewFrame.addView(InitialsView(activity, station.name), FrameLayout.LayoutParams(-1, -1))
        val preview = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Station image preview"
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val spinner = ArtworkLoadingView(activity).apply {
            visibility = View.GONE; contentDescription = "Loading artwork"
        }
        previewFrame.addView(spinner, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(10); bottomMargin = dp(10)
        })
        left.addView(previewFrame, LinearLayout.LayoutParams(previewSize, previewSize))
        val previous = commandButton(activity, "Previous")
        val next = commandButton(activity, "Next")
        val cycle = LinearLayout(activity)
        cycle.addView(previous, LinearLayout.LayoutParams(0, dp(44), 1f))
        cycle.addView(next, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(12) })
        left.addView(cycle, LinearLayout.LayoutParams(previewSize, dp(44)).apply { topMargin = dp(14) })
        body.addView(left, LinearLayout.LayoutParams(if (wide) previewSize else -1, -2))
        if (!wide) left.gravity = Gravity.CENTER_HORIZONTAL
        val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        right.addView(label(activity, "Search station names", 16f), LinearLayout.LayoutParams(-1, dp(33)))
        val query = EditText(activity).apply {
            setSingleLine(true); setText(station.name); setTextColor(TEXT)
            textSize = 21f; setPadding(dp(16), 0, dp(16), 0)
            background = rounded(AutomotiveDesign.FIELD, 14, BORDER)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val search = commandButton(activity, "Find logos")
        val searchRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(query, LinearLayout.LayoutParams(0, dp(52), 1f))
        searchRow.addView(search, LinearLayout.LayoutParams(dp(if (wide) 158 else 120), dp(44)).apply { leftMargin = dp(16) })
        right.addView(searchRow)
        val statusRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val status = label(activity, "Choose a station logo", 20f, true).apply {
            maxWidth = dialogWidth - dp(if (wide) 426 else 88)
            gravity = Gravity.CENTER_VERTICAL
        }
        val warning = WarningView(activity).apply { visibility = View.GONE }
        statusRow.addView(status, LinearLayout.LayoutParams(-2, -2))
        statusRow.addView(warning, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(8) })
        right.addView(statusRow, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(25) })
        val detail = label(activity, "Search the station directory, or use initials.\nThe saved image stays until you save a choice.", 16f).apply {
            setTextColor(MUTED); setLineSpacing(dp(5).toFloat(), 1f)
            minLines = 2; maxLines = 2
        }
        right.addView(detail, LinearLayout.LayoutParams(-1, dp(43)).apply { topMargin = dp(9) })
        val generic = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(0xff292f37.toInt(), 16, 0xff5c6875.toInt())
            isClickable = true; isFocusable = true
            contentDescription = "Use generic station image. Keep initials; stop automatic logo requests."
        }
        val radio = RadioButton(activity).apply {
            isClickable = false; isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        generic.addView(radio, LinearLayout.LayoutParams(dp(28), dp(32)))
        val genericLabels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        genericLabels.addView(label(activity, "Use generic station image", 18f, true))
        genericLabels.addView(label(activity, "Keep initials; stop automatic logo requests.", 15f).apply {
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        generic.addView(genericLabels, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
        right.addView(generic, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(40); if (wide) height = dp(85) })
        val note = label(activity, "Saved image stays until you choose another", 15f).apply { setTextColor(MUTED); gravity = Gravity.CENTER_VERTICAL }
        right.addView(note, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(14) })
        body.addView(right, LinearLayout.LayoutParams(if (wide) 0 else -1, -2, if (wide) 1f else 0f).apply {
            if (wide) leftMargin = dp(28) else topMargin = dp(20)
        })
        root.addView(body)
        root.addView(View(activity).apply { setBackgroundColor(0xff47515d.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(11) })
        val footer = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        if (wide) footer.addView(label(activity, "Parked only", 14f).apply { setTextColor(MUTED) }, LinearLayout.LayoutParams(0, -2, 1f))
        else root.addView(label(activity, "Parked only", 14f).apply { setTextColor(MUTED) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val cancel = commandButton(activity, "Cancel")
        val use = commandButton(activity, "Use this image", theme.accent, theme.onAccent)
        footer.addView(cancel, LinearLayout.LayoutParams(if (wide) dp(122) else 0, dp(44), if (wide) 0f else 1f))
        footer.addView(use, LinearLayout.LayoutParams(if (wide) dp(170) else 0, dp(44), if (wide) 0f else 1.4f).apply { leftMargin = dp(14) })
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
        installDialog(activity, dialog, root, dialogWidth)

        fun controls() {
            search.isEnabled = !working; query.isEnabled = !working; generic.isEnabled = !working
            generic.alpha = if (working) .36f else 1f
            previous.isEnabled = !working && candidates.size > 1 && !genericSelected
            next.isEnabled = previous.isEnabled
            use.isEnabled = !working && (genericSelected || selected in candidates.indices)
            listOf(search, previous, next, use).forEach { it.alpha = if (it.isEnabled) 1f else .36f }
            spinner.visibility = if (working) View.VISIBLE else View.GONE
            radio.isChecked = genericSelected
            generic.background = rounded(if (genericSelected) 0xff333f48.toInt() else 0xff292f37.toInt(), 16,
                if (genericSelected) theme.accent else 0xff5c6875.toInt())
        }
        fun state(title: String, lines: String, failed: Boolean = false) {
            status.text = title; detail.text = lines; warning.visibility = if (failed) View.VISIBLE else View.GONE
        }
        fun showBitmap(bytes: ByteArray?) {
            previewJob?.cancel()
            previewJob = scope.launch {
                val bitmap = withContext(ArtworkWorkScheduler.dispatcher) { bytes?.let { decodeSized(it, 384) } }
                if (dialog.isShowing && !genericSelected) preview.setImageBitmap(bitmap) else bitmap?.recycle()
            }
        }
        fun renderCandidate(index: Int) {
            if (index !in candidates.indices || working) return
            selected = index; genericSelected = false; showBitmap(candidates[index].bytes)
            state("${index + 1} of ${candidates.size} · ${candidates[index].name}",
                if (candidates.size == 1) "The directory supplied one usable image.\nUse this image, search again, or choose generic."
                else "Preview only · browse with Previous and Next.\nUse this image saves your choice on this device.")
            note.text = "Saved image stays until you choose another"; controls()
        }
        generic.setOnClickListener {
            if (working) return@setOnClickListener
            genericSelected = true; previewJob?.cancel(); preview.setImageDrawable(null)
            state("Generic image selected", "No automatic logo searches for this station.\nSearch again whenever you want a logo.")
            note.text = "Preview only · choose Use this image to save"; controls()
        }
        previous.setOnClickListener { if (candidates.isNotEmpty()) renderCandidate((selected - 1).mod(candidates.size)) }
        next.setOnClickListener { if (candidates.isNotEmpty()) renderCandidate((selected + 1).mod(candidates.size)) }
        search.setOnClickListener {
            if (working) return@setOnClickListener
            val term = query.text.toString().trim().ifBlank { station.name }
            val request = ++generation
            job?.cancel(); previewJob?.cancel()
            working = true; selected = -1; genericSelected = false; candidates.clear()
            state("Searching Radio Browser…", "Checking the directory for available logos.\nYour saved image stays in place.")
            controls()
            job = scope.launch {
                try {
                    val result = StationArtworkStore.searchCandidates(activity, station, term, onDownloading = {
                        scope.launch {
                            if (dialog.isShowing && request == generation && working)
                                state("Loading image previews…", "Logo links found · downloading previews.\nYour saved image stays until results are ready.")
                        }
                    })
                    if (!dialog.isShowing || request != generation) return@launch
                    working = false
                    when (result) {
                        is StationArtworkStore.CandidateSearch.Found -> {
                            candidates.addAll(result.images)
                            if (candidates.isNotEmpty()) renderCandidate(0)
                            else state("No directory logos for “$term”", "Try a broader station name.\nGoogle Images is not searched here.")
                        }
                        StationArtworkStore.CandidateSearch.NoResults -> state("No directory logos for “$term”", "Try a broader station name.\nGoogle Images is not searched here.")
                        StationArtworkStore.CandidateSearch.DirectoryFailed -> state("Station directory unavailable", "Check your connection, then try Find logos.\nSaved image unchanged · this is not “no results”.")
                        StationArtworkStore.CandidateSearch.DownloadFailed -> state("Logo links found, but images failed to load", "No usable previews downloaded.\nTry again or choose a generic station image.")
                        StationArtworkStore.CandidateSearch.LowQuality -> {
                            previewJob?.cancel(); preview.setImageDrawable(null)
                            state("Available logos are too small", "Station logos need at least 512 × 512 pixels.\nTry another name, or use a generic station image.")
                        }
                        StationArtworkStore.CandidateSearch.Disabled -> state("Station images are turned off", "Enable online artwork in Settings to search.\nYour saved image is unchanged.")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (dialog.isShowing && request == generation) state("Station directory unavailable", "Check your connection, then try Find logos.\nYour saved image is unchanged.")
                } finally {
                    if (dialog.isShowing && request == generation) { working = false; controls() }
                }
            }
        }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { search.performClick(); true } else false
        }
        cancel.setOnClickListener { dialog.dismiss() }
        use.setOnClickListener {
            if (working || (!genericSelected && selected !in candidates.indices)) return@setOnClickListener
            val chosenGeneric = genericSelected
            val candidate = candidates.getOrNull(selected)
            val request = ++generation
            working = true; controls()
            state("Saving this station image…", "Keeping your choice on this device.\nThe dialog closes after a successful save.")
            job = scope.launch {
                val saved = try {
                    if (chosenGeneric) StationArtworkStore.useGeneric(activity, station)
                    else candidate != null && StationArtworkStore.useCandidate(activity, station, candidate)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (!dialog.isShowing || request != generation) return@launch
                working = false
                if (saved) {
                    withContext(ArtworkWorkScheduler.dispatcher) {
                        activity.getSharedPreferences("twinsen_radio", 0).edit()
                            .putLong("station_artwork_revision", System.currentTimeMillis()).commit()
                    }
                    changed(); dialog.dismiss()
                } else {
                    state("Could not save this station image", "Your current saved image is unchanged.\nChoose Use this image to retry, or Cancel.", true)
                    controls()
                }
            }
        }
        dialog.setOnDismissListener { generation++; job?.cancel(); previewJob?.cancel() }
        dialog.show(); controls()
        if (genericSelected) state("Generic image selected", "No automatic logo searches for this station.\nSearch again whenever you want a logo.")
        job = scope.launch {
            val bytes = withContext(ArtworkWorkScheduler.dispatcher) {
                runCatching {
                    prefs.customLogo(station.id)?.let { java.io.File(it) }?.takeIf { it.isFile }?.readBytes()
                        ?: activity.contentResolver.openInputStream(StationArtworkStore.uri(activity, station))?.use { it.readBytes() }
                }.getOrNull()
            }
            if (dialog.isShowing && selected == -1 && !genericSelected) showBitmap(bytes)
        }
    }

    fun showDisabled(activity: Activity, onOpenSettings: () -> Unit) {
        val theme = AppearanceTheme.current(activity)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(20), dp(30), dp(38))
        }
        val title = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        title.addView(label(activity, "Station images are turned off", 24f, true).apply {
            maxWidth = minOf(dp(676), visibleBounds(activity).width() - dp(32)) - dp(96)
        }, LinearLayout.LayoutParams(-2, -2))
        title.addView(WarningView(activity), LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(12) })
        root.addView(title)
        root.addView(label(activity, "Enable online artwork in Settings to change station images.\nYour saved image is unchanged.", 17f).apply {
            setLineSpacing(dp(8).toFloat(), 1f)
            minLines = 2; maxLines = 2
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
        val actions = LinearLayout(activity)
        actions.addView(commandButton(activity, "OK").apply { setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(commandButton(activity, "Open Settings", theme.accent, theme.onAccent).apply {
            setOnClickListener { dialog.dismiss(); onOpenSettings() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(16) })
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(58) })
        installDialog(activity, dialog, root, minOf(dp(676), visibleBounds(activity).width() - dp(32)))
        dialog.show()
    }

    /** Keep the pre-keyboard geometry; the IME overlays this floating surface. */
    private fun installDialog(activity: Activity, dialog: Dialog, content: View, width: Int) {
        val frame = stableWindowBounds(activity)
        val maximum = (frame.height() - dp(32)).coerceAtLeast(dp(120))
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false; background = rounded(SURFACE, 28, BORDER); clipToOutline = true
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        dialog.setContentView(scroll)
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST),
        )
        val initialTop = ((frame.height() - scroll.measuredHeight) / 2).coerceAtLeast(0)
        dialog.window?.apply {
            setWindowAnimations(0)
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.72f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            // CENTER repositions when the IME changes the system's usable frame.
            // Anchor the initial centered position to the stable top edge instead.
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = initialTop }
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }
    private fun stableWindowBounds(activity: Activity): Rect {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val metrics = activity.windowManager.currentWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
            )
            return Rect(metrics.bounds).apply {
                left += bars.left; top += bars.top
                right -= bars.right; bottom -= bars.bottom
            }
        }
        // Pre-R devices capture their visible frame once, before this dialog's IME.
        return visibleBounds(activity)
    }
    private fun visibleBounds(activity: Activity) = Rect().also {
        activity.window.decorView.getWindowVisibleDisplayFrame(it)
        if (it.width() <= 0 || it.height() <= 0)
            it.set(0, 0, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
    }
    private fun label(activity: Activity, value: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(TEXT); includeFontPadding = false
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private fun commandButton(activity: Activity, value: String, color: Int = AutomotiveDesign.CONTROL, foreground: Int = TEXT) = Button(activity).apply {
        text = value; textSize = 16f; isAllCaps = false; setTextColor(foreground)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        background = rounded(color, 22, if (color == AutomotiveDesign.CONTROL) 0xff66717e.toInt() else null)
    }
    private fun dp(value: Int) = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1).coerceAtLeast(1), stroke)
    }
    private fun decodeSized(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > size * 2 || bounds.outHeight / sample > size * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    private class InitialsView(activity: Activity, name: String) : View(activity) {
        private val initials = name.split(Regex("\\s+")).mapNotNull { it.firstOrNull { char -> char.isLetter() } }
            .take(2).joinToString("").uppercase().ifBlank { "FM" }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xff315b64.toInt()); paint.color = 0x14ffffff
            canvas.drawCircle(width * .8f, height * .1f, width * .26f, paint)
            paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); paint.textSize = width * .29f
            canvas.drawText(initials, width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }
    private class WarningView(activity: Activity) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val triangle = Path().apply { moveTo(12f, 3f); lineTo(22f, 21f); lineTo(2f, 21f); close() }
        init { contentDescription = "Warning" }
        override fun onDraw(canvas: Canvas) {
            canvas.save(); canvas.scale(width / 24f, height / 24f)
            paint.style = Paint.Style.FILL; paint.color = 0xfffa5665.toInt(); canvas.drawPath(triangle, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.3f; paint.strokeJoin = Paint.Join.ROUND
            paint.color = 0xffff8d98.toInt(); canvas.drawPath(triangle, paint)
            paint.color = Color.WHITE; paint.strokeWidth = 2f; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(12f, 8f, 12f, 14f, paint)
            paint.style = Paint.Style.FILL; canvas.drawCircle(12f, 17.5f, 1.1f, paint); canvas.restore()
        }
    }
}
