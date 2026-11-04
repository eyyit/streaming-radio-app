package net.mspanc.twinsenradio.automotive

import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

/** Parked-only picker. A selection is committed only by Save image. */
object StationImageDialog {
    private const val SURFACE = AutomotiveDesign.SURFACE
    private const val TEXT = AutomotiveDesign.TEXT
    private const val MUTED = AutomotiveDesign.MUTED
    private const val BORDER = AutomotiveDesign.BORDER

    private class EditorHandle(val dismiss: () -> Unit) { var visible = true }
    private val editors = mutableMapOf<Activity, EditorHandle>()
    fun handleBack(activity: Activity): Boolean {
        val editor = editors[activity] ?: return false
        editor.dismiss(); return true
    }
    fun close(activity: Activity) { editors[activity]?.dismiss?.invoke() }

    fun show(activity: Activity, station: Station, scope: CoroutineScope,
             changed: () -> Unit = {}, onOpenSettings: () -> Unit = {},
             host: ViewGroup? = null) {
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
        close(activity)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(14))
        }
        val dialogWidth = minOf(dp(900), visibleBounds(activity).width() - dp(32)).coerceAtLeast(dp(280))
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val previewSize = minOf(dp(340), dialogWidth - dp(56))
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
        val previous = commandButton(activity, "Previous")
        val next = commandButton(activity, "Next")
        val cycle = LinearLayout(activity)
        cycle.addView(previous, LinearLayout.LayoutParams(0, dp(52), 1f))
        cycle.addView(next, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(12) })
        val query = EditText(activity).apply {
            setSingleLine(true); setText(station.name); setTextColor(TEXT)
            textSize = 21f; setPadding(dp(16), 0, dp(16), 0)
            background = rounded(AutomotiveDesign.FIELD, 14, BORDER)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Search station names"
        }
        val search = commandButton(activity, "Find logos")
        val searchRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(query, LinearLayout.LayoutParams(0, dp(52), 1f))
        searchRow.addView(search, LinearLayout.LayoutParams(dp(180), dp(52)).apply { leftMargin = dp(16) })
        val statusRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val status = label(activity, "Choose a station logo", 20f).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val warning = WarningView(activity).apply { visibility = View.GONE }
        statusRow.addView(status, LinearLayout.LayoutParams(0, -1, 1f))
        statusRow.addView(warning, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(8) })
        val detail = label(activity, "Search the station directory, or use initials.", 16f).apply {
            setTextColor(MUTED)
            maxLines = 1; gravity = Gravity.CENTER
        }
        val generic = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(14), dp(6))
            background = rounded(0xff292f37.toInt(), 16, 0xff5c6875.toInt())
            isClickable = true; isFocusable = true
            contentDescription = "Use generic station image. Keep initials; stop automatic logo requests."
        }
        val radio = RadioButton(activity).apply {
            isClickable = false; isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val genericLabels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        genericLabels.addView(label(activity, "Use generic image", 22f))
        genericLabels.addView(label(activity, "Keep initials and stop automatic logo requests", 15f).apply {
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(-1, -2))
        generic.addView(genericLabels, LinearLayout.LayoutParams(0, -2, 1f))
        generic.addView(radio, LinearLayout.LayoutParams(dp(32), dp(48)))
        // Header/search are fixed; only the body scrolls.
        val cancel = commandButton(activity, "Cancel")
        val use = commandButton(activity, "Save image", theme.accent, theme.onAccent)
        listOf(cancel, use, search, previous, next).forEach {
            it.textSize = AutomotiveDesign.BUTTON_TEXT_SP
            it.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            it.maxLines = 1; it.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label(activity, station.name, 25f), LinearLayout.LayoutParams(-1, dp(30)))
        heading.addView(label(activity, "Station image", 14f), LinearLayout.LayoutParams(-1, dp(20)))
        header.addView(cancel, LinearLayout.LayoutParams(dp(102), dp(52)))
        header.addView(heading, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(24); rightMargin = dp(16) })
        header.addView(use, LinearLayout.LayoutParams(dp(168), dp(52)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(searchRow, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(24) })
        body.gravity = Gravity.CENTER_HORIZONTAL; body.setPadding(0, dp(30), 0, 0)
        body.addView(previewFrame, LinearLayout.LayoutParams(previewSize, previewSize))
        status.gravity = Gravity.CENTER
        statusRow.gravity = Gravity.CENTER
        body.addView(statusRow, LinearLayout.LayoutParams(-1, dp(28)).apply { topMargin = dp(10) })
        body.addView(detail, LinearLayout.LayoutParams(-1, dp(26)))
        body.addView(cycle, LinearLayout.LayoutParams(previewSize, dp(52)).apply { topMargin = dp(10) })
        body.addView(generic, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(14) })
        root.addView(ScrollView(activity).apply {
            isFillViewport = false; addView(body, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val previousViews = host?.let { parent ->
            (0 until parent.childCount).map { parent.getChildAt(it) to parent.getChildAt(it).visibility }
        }.orEmpty()
        val hostVisibility = host?.visibility
        val oldSoftInputMode = activity.window.attributes.softInputMode
        val container = FrameLayout(activity).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16)); setBackgroundColor(AutomotiveDesign.CANVAS)
            isClickable = true; isFocusableInTouchMode = true
            addView(root, FrameLayout.LayoutParams(-1, -1))
        }
        root.background = rounded(SURFACE, 28); root.clipToOutline = true
        lateinit var editor: EditorHandle
        fun finish() {
            if (!editor.visible) return
            editor.visible = false; generation++; job?.cancel(); previewJob?.cancel()
            (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(container.windowToken, 0)
            container.clearFocus(); host?.removeView(container)
            previousViews.forEach { (view, visibility) -> if (view.parent === host) view.visibility = visibility }
            if (hostVisibility != null) host?.visibility = hostVisibility
            activity.window.setSoftInputMode(oldSoftInputMode)
            if (editors[activity] === editor) editors.remove(activity)
            dialog.dismiss()
        }
        editor = EditorHandle { finish() }; editors[activity] = editor
        if (host != null) {
            previousViews.forEach { (view, _) -> view.visibility = View.GONE }
            host.visibility = View.VISIBLE; host.addView(container, ViewGroup.LayoutParams(-1, -1))
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        } else {
            val frame = stableWindowBounds(activity)
            dialog.setContentView(container)
            dialog.window?.apply {
                setWindowAnimations(0); setBackgroundDrawableResource(android.R.color.transparent)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            }
            dialog.setOnDismissListener { finish() }; dialog.show()
            dialog.window?.setLayout(dialogWidth, frame.height() - dp(32))
        }
        container.requestFocus()

        fun controls() {
            search.isEnabled = !working; query.isEnabled = !working; generic.isEnabled = !working
            search.text = if (working && selected == -1 && !genericSelected) "Searching…" else "Find logos"
            generic.alpha = if (working) .36f else 1f
            previous.isEnabled = !working && candidates.size > 1 && !genericSelected
            next.isEnabled = previous.isEnabled
            use.isEnabled = !working && (genericSelected || selected in candidates.indices)
            listOf(search, previous, next, use).forEach { it.alpha = if (it.isEnabled) 1f else .36f }
            spinner.visibility = if (working) View.VISIBLE else View.GONE
            radio.isChecked = genericSelected
            generic.contentDescription = "Use generic image. " +
                (if (genericSelected) "Selected. " else "Not selected. ") +
                "Keep initials and stop automatic logo requests. Save image to apply."
            generic.background = rounded(if (genericSelected) 0xff333f48.toInt() else 0xff292f37.toInt(), 16,
                if (genericSelected) theme.accent else 0xff5c6875.toInt())
        }
        fun state(title: String, lines: String, failed: Boolean = false) {
            status.text = title; detail.text = lines.replace("\n", " ")
            detail.contentDescription = lines
            warning.visibility = if (failed) View.VISIBLE else View.GONE
        }
        fun showBitmap(bytes: ByteArray?) {
            previewJob?.cancel()
            previewJob = scope.launch {
                val bitmap = withContext(ArtworkWorkScheduler.dispatcher) { bytes?.let { decodeSized(it, 384) } }
                if (editor.visible && !genericSelected) preview.setImageBitmap(bitmap) else bitmap?.recycle()
            }
        }
        fun renderCandidate(index: Int) {
            if (index !in candidates.indices || working) return
            selected = index; genericSelected = false; showBitmap(candidates[index].bytes)
            state("${index + 1} of ${candidates.size} · ${candidates[index].name}",
                "Preview only — tap Save image to apply")
            controls()
        }
        generic.setOnClickListener {
            if (working) return@setOnClickListener
            genericSelected = true; previewJob?.cancel(); preview.setImageDrawable(null)
            state("Generic image selected", "No automatic logo searches for this station.\nSearch again whenever you want a logo.")
            controls()
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
                            if (editor.visible && request == generation && working)
                                state("Loading image previews…", "Logo links found · downloading previews.\nYour saved image stays until results are ready.")
                        }
                    })
                    if (!editor.visible || request != generation) return@launch
                    working = false
                    when (result) {
                        is StationArtworkStore.CandidateSearch.Found -> {
                            candidates.addAll(result.images)
                            if (candidates.isNotEmpty()) renderCandidate(0)
                            else state("No directory logos found", "Saved image unchanged. Try another search.")
                        }
                        StationArtworkStore.CandidateSearch.NoResults -> state("No directory logos found", "Saved image unchanged. Try another search.")
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
                    if (editor.visible && request == generation) state("Station directory unavailable", "Check your connection, then try Find logos.\nYour saved image is unchanged.")
                } finally {
                    if (editor.visible && request == generation) { working = false; controls() }
                }
            }
        }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { search.performClick(); true } else false
        }
        cancel.setOnClickListener { finish() }
        use.setOnClickListener {
            if (working || (!genericSelected && selected !in candidates.indices)) return@setOnClickListener
            val chosenGeneric = genericSelected
            val candidate = candidates.getOrNull(selected)
            val request = ++generation
            working = true; controls()
            state("Saving this station image…", "Keeping your choice on this device.\nThe editor closes after a successful save.")
            job = scope.launch {
                val saved = try {
                    if (chosenGeneric) StationArtworkStore.useGeneric(activity, station)
                    else candidate != null && StationArtworkStore.useCandidate(activity, station, candidate)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (!editor.visible || request != generation) return@launch
                working = false
                if (saved) {
                    withContext(ArtworkWorkScheduler.dispatcher) {
                        activity.getSharedPreferences("twinsen_radio", 0).edit()
                            .putLong("station_artwork_revision", System.currentTimeMillis()).commit()
                    }
                    changed(); finish()
                } else {
                    state("Could not save this station image", "Your current saved image is unchanged.\nChoose Save image to retry, or Cancel.", true)
                    controls()
                }
            }
        }
        controls()
        if (genericSelected) state("Generic image selected", "No automatic logo searches for this station.\nSearch again whenever you want a logo.")
        job = scope.launch {
            val bytes = withContext(ArtworkWorkScheduler.dispatcher) {
                runCatching {
                    prefs.customLogo(station.id)?.let { java.io.File(it) }?.takeIf { it.isFile }?.readBytes()
                        ?: activity.contentResolver.openInputStream(StationArtworkStore.uri(activity, station))?.use { it.readBytes() }
                }.getOrNull()
            }
            if (editor.visible && selected == -1 && !genericSelected) showBitmap(bytes)
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
