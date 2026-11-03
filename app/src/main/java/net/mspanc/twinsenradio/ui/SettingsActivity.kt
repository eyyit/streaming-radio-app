package net.mspanc.twinsenradio.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.ArtContent
import net.mspanc.twinsenradio.data.ArtworkMode
import net.mspanc.twinsenradio.data.BufferProfile
import net.mspanc.twinsenradio.data.ClockColors
import net.mspanc.twinsenradio.data.ContentStyle
import net.mspanc.twinsenradio.data.Line
import net.mspanc.twinsenradio.data.LineContent
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.databinding.ActivitySettingsBinding
import net.mspanc.twinsenradio.playback.DiagnosticFields
import net.mspanc.twinsenradio.playback.Trace

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    /** The selected index of each list - MaterialAutoCompleteTextView holds text, not a position. */
    private val chosen = HashMap<Int, Int>()

    /** Which LineContent values each line dropdown offers - see [bindChoices]. */
    private val lineChoices = HashMap<Int, List<LineContent>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.toolbar.setNavigationOnClickListener { finish() }

        // --- dashboard display -------------------------------------------------
        // Labels and hints come from the Line enum, so the row descriptions
        // aren't duplicated in two places.
        b.swEnrich.isChecked = prefs.enrichWithYear
        bindPair(b.tilLineTop, b.ddLineTop, b.ddLineTop2, Line.TOP, LineContent.WHEN_PLAYING, prefs.lineTop, prefs.lineTop2)
        bindPair(b.tilLineMiddle, b.ddLineMiddle, b.ddLineMiddle2, Line.MIDDLE, LineContent.WHEN_PLAYING, prefs.lineMiddle, prefs.lineMiddle2)
        bindPair(b.tilLineBottom, b.ddLineBottom, b.ddLineBottom2, Line.BOTTOM, LineContent.WHEN_PLAYING, prefs.lineBottom, prefs.lineBottom2)
        bindPair(b.tilLineTopIdle, b.ddLineTopIdle, b.ddLineTopIdle2, Line.TOP, LineContent.WHEN_IDLE, prefs.lineTopIdle, prefs.lineTopIdle2)
        bindPair(b.tilLineMiddleIdle, b.ddLineMiddleIdle, b.ddLineMiddleIdle2, Line.MIDDLE, LineContent.WHEN_IDLE, prefs.lineMiddleIdle, prefs.lineMiddleIdle2)
        bindPair(b.tilLineBottomIdle, b.ddLineBottomIdle, b.ddLineBottomIdle2, Line.BOTTOM, LineContent.WHEN_IDLE, prefs.lineBottomIdle, prefs.lineBottomIdle2)
        // The "· album" options' labels spell out whether they'll carry a year -
        // keep that in sync with the switch without losing what's already picked.
        // Only the playing set has those entries; the other list is unaffected.
        b.swEnrich.setOnCheckedChangeListener { _, checked ->
            listOf(b.ddLineTop, b.ddLineMiddle, b.ddLineBottom, b.ddLineTop2, b.ddLineMiddle2, b.ddLineBottom2)
                .forEach { bind(it, LineContent.labels(lineChoices[it.id].orEmpty(), checked), pick(it)) }
        }

        // --- artwork -------------------------------------------------------
        // One choice per state, exactly like the lines above it.
        bind(b.ddArtPlaying, ArtContent.LABELS, artPosition(prefs.artPlaying)) { updateClockOptionsEnabled() }
        bind(b.ddArtIdle, ArtContent.LABELS, artPosition(prefs.artIdle)) { updateClockOptionsEnabled() }
        bind(b.ddClockBg, ClockColors.BACKGROUND_LABELS, prefs.clockBackground)
        bind(b.ddClockFg, ClockColors.FOREGROUND_LABELS, prefs.clockForeground)
        bind(b.ddArtwork, ArtworkMode.LABELS, prefs.artworkMode)
        updateClockOptionsEnabled()

        // --- diagnostics -----------------------------------------------------
        b.swDiag.isChecked = prefs.diagnosticMode
        b.swDiagApi.isChecked = prefs.diagnosticShowApiName
        b.swDiagApi.setOnCheckedChangeListener { _, _ -> renderLegend() }
        renderLegend()

        b.swTrace.isChecked = prefs.traceEnabled
        b.btnTraceClear.setOnClickListener {
            Trace.clear(this)
            // The delete runs on the trace's own thread, so a size read this
            // instant would still be the old one.
            b.tvTraceState.postDelayed({ renderTrace() }, 300)
            Toast.makeText(this, R.string.opt_trace_cleared, Toast.LENGTH_SHORT).show()
        }
        renderTrace()

        // --- the rest ----------------------------------------------------------
        bind(b.ddBrowsable, ContentStyle.LABELS, ContentStyle.valueToIndex(prefs.browsableStyle))
        bind(b.ddPlayable, ContentStyle.LABELS, ContentStyle.valueToIndex(prefs.playableStyle))
        bind(b.ddBuffer, BufferProfile.ALL.map { it.label }, prefs.bufferProfile)
        // Both are stored as the value itself, not a position, so the list of
        // choices can change without moving anybody's setting.
        bind(
            b.ddApiHold,
            Prefs.API_HOLD_CHOICES.map { if (it == 0) "Bez opóźnienia" else "$it s" },
            Prefs.API_HOLD_CHOICES.indexOf(prefs.apiHoldSeconds).coerceAtLeast(0)
        )
        bind(
            b.ddNewsMinutes,
            Prefs.NEWS_MINUTES_CHOICES.map { if (it == 0) "Nie rozpoznawaj serwisu" else "$it min" },
            Prefs.NEWS_MINUTES_CHOICES.indexOf(prefs.newsMinutes).coerceAtLeast(0)
        )
        b.etM3u.setText(prefs.userM3uUrls.joinToString("\n"))
        renderHidden()

        b.btnSave.setOnClickListener { save() }
    }

    /**
     * Binds a dropdown list. Material holds text in it, but we need the
     * index - hence our own map of selected positions.
     */
    private fun bind(
        dropdown: MaterialAutoCompleteTextView,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit = {}
    ) {
        val index = selected.coerceIn(labels.indices)
        dropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        dropdown.setText(labels[index], false)
        chosen[dropdown.id] = index
        dropdown.setOnItemClickListener { _, _, position, _ ->
            chosen[dropdown.id] = position
            onPick(position)
        }
    }

    /** Fills one line dropdown, translating between the stored ordinal and a position. */
    private fun bindChoices(
        dropdown: MaterialAutoCompleteTextView,
        choices: List<LineContent>,
        selected: Int,
        fallback: LineContent = choices.firstOrNull() ?: LineContent.EMPTY,
        onPick: (Int) -> Unit = {}
    ) {
        lineChoices[dropdown.id] = choices
        // A value this list doesn't offer - an old setting, one saved by a later
        // version, or the entry that just stopped being compatible with the
        // other half of this line - lands on [fallback] rather than on whatever
        // happens to sit at that index.
        val position = choices.indexOf(LineContent.at(selected))
            .takeIf { it >= 0 }
            ?: choices.indexOf(fallback).coerceAtLeast(0)
        bind(dropdown, LineContent.labels(choices, b.swEnrich.isChecked), position, onPick)
    }

    /**
     * Binds one line: what it carries, and what it carries after the dot.
     *
     * The second list never offers what the first one is already showing -
     * a line reading "18:48 · 18:48" is not a layout anybody wants, and
     * leaving it selectable would mean explaining why it does nothing. So the
     * second list is rebuilt whenever the first changes, and if the choice it
     * was holding is the one that just disappeared, it drops to "Puste".
     */
    private fun bindPair(
        layout: TextInputLayout,
        primary: MaterialAutoCompleteTextView,
        secondary: MaterialAutoCompleteTextView,
        line: Line,
        choices: List<LineContent>,
        selectedPrimary: Int,
        selectedSecondary: Int
    ) {
        fun rebuildSecondary(keep: Int) {
            val taken = LineContent.at(pickLine(primary))
            // A second half that no longer fits drops to "Puste" rather than to
            // whatever tops the shortened list - silence is the safe default here.
            bindChoices(secondary, choices.filterNot { it.overlaps(taken) }, keep, LineContent.EMPTY)
        }

        layout.hint = line.label
        layout.helperText = line.hint
        layout.isHelperTextEnabled = true
        // A retired composite is unfolded here exactly as it is when the
        // service reads it, so the screen shows what the car shows. Without
        // this the picker would fail to find it, quietly fall back to the first
        // entry, and the next save would write that instead.
        val spec = LineContent.expandedBy(
            LineContent.at(selectedPrimary),
            LineContent.at(selectedSecondary)
        )
        bindChoices(primary, choices, spec.primary.ordinal) { rebuildSecondary(pickLine(secondary)) }
        rebuildSecondary(spec.secondary.ordinal)
    }

    /** Turns a dropdown's position back into the ordinal that gets stored. */
    private fun pickLine(dropdown: MaterialAutoCompleteTextView): Int {
        val choices = lineChoices[dropdown.id] ?: LineContent.WHEN_PLAYING
        return choices.getOrElse(pick(dropdown)) { choices.first() }.ordinal
    }

    private fun pick(dropdown: MaterialAutoCompleteTextView): Int = chosen[dropdown.id] ?: 0

    // The picture is stored as an ordinal, but the picker no longer offers every
    // entry ("Puste" is gone), so a position in the list is not an ordinal.
    private fun artPosition(stored: Int): Int = ArtContent.OFFERED.indexOf(ArtContent.at(stored)).coerceAtLeast(0)

    private fun pickArt(dropdown: MaterialAutoCompleteTextView): ArtContent =
        ArtContent.OFFERED.getOrElse(pick(dropdown)) { ArtContent.COVER }

    /** Clock colours matter only if at least one of the two states draws a clock. */
    private fun updateClockOptionsEnabled() {
        val usesClock = listOf(b.ddArtPlaying, b.ddArtIdle)
            .any { pickArt(it).clockFace != null }
        listOf(b.tilClockBg, b.tilClockFg).forEach {
            it.isEnabled = usesClock
            it.alpha = if (usesClock) 1f else 0.4f
        }
    }

    /**
     * Stations removed from the list. Built-in ones can't be deleted, so they're
     * only hidden - and this is the only place they can be recovered from.
     */
    private fun renderHidden() {
        val repo = StationRepository.get(this)
        val hidden = prefs.hidden
        if (hidden.isEmpty()) {
            b.hiddenBox.visibility = View.GONE
            return
        }
        val names = repo.allIncludingHidden()
            .filter { it.id in hidden }
            .joinToString(", ") { it.name }
        b.hiddenBox.visibility = View.VISIBLE
        b.hiddenLabel.text = getString(R.string.opt_hidden_label, names.ifBlank { "${hidden.size}" })
        b.btnRestoreHidden.setOnClickListener {
            prefs.restoreAllHidden()
            renderHidden()
            Toast.makeText(this, R.string.opt_restored_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderLegend() {
        b.tvLegend.text = DiagnosticFields.legend(b.swDiagApi.isChecked)
    }

    /** How much of a drive is on the phone, and where it sits. */
    private fun renderTrace() {
        val (count, bytes) = Trace.size(this)
        b.tvTraceState.text = if (count == 0) {
            getString(R.string.opt_trace_empty)
        } else {
            val size = if (bytes < 1024 * 1024) {
                "${bytes / 1024} kB"
            } else {
                String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
            }
            getString(R.string.opt_trace_state, count, size) + "\n" + Trace.dir(this).absolutePath
        }
    }

    private fun save() {
        prefs.lineTop = pickLine(b.ddLineTop)
        prefs.lineMiddle = pickLine(b.ddLineMiddle)
        prefs.lineBottom = pickLine(b.ddLineBottom)
        prefs.lineTopIdle = pickLine(b.ddLineTopIdle)
        prefs.lineMiddleIdle = pickLine(b.ddLineMiddleIdle)
        prefs.lineBottomIdle = pickLine(b.ddLineBottomIdle)
        prefs.lineTop2 = pickLine(b.ddLineTop2)
        prefs.lineMiddle2 = pickLine(b.ddLineMiddle2)
        prefs.lineBottom2 = pickLine(b.ddLineBottom2)
        prefs.lineTopIdle2 = pickLine(b.ddLineTopIdle2)
        prefs.lineMiddleIdle2 = pickLine(b.ddLineMiddleIdle2)
        prefs.lineBottomIdle2 = pickLine(b.ddLineBottomIdle2)
        prefs.enrichWithYear = b.swEnrich.isChecked

        prefs.artPlaying = pickArt(b.ddArtPlaying).ordinal
        prefs.artIdle = pickArt(b.ddArtIdle).ordinal
        prefs.clockBackground = pick(b.ddClockBg)
        prefs.clockForeground = pick(b.ddClockFg)
        prefs.artworkMode = pick(b.ddArtwork)

        prefs.diagnosticMode = b.swDiag.isChecked
        prefs.diagnosticShowApiName = b.swDiagApi.isChecked
        prefs.traceEnabled = b.swTrace.isChecked

        prefs.browsableStyle = ContentStyle.indexToValue(pick(b.ddBrowsable))
        prefs.playableStyle = ContentStyle.indexToValue(pick(b.ddPlayable))
        prefs.bufferProfile = pick(b.ddBuffer)
        prefs.apiHoldSeconds = Prefs.API_HOLD_CHOICES[pick(b.ddApiHold)]
        prefs.newsMinutes = Prefs.NEWS_MINUTES_CHOICES[pick(b.ddNewsMinutes)]
        prefs.userM3uUrls = b.etM3u.text?.toString().orEmpty().lines()

        lifecycleScope.launch {
            StationRepository.get(this@SettingsActivity).refreshUserLists(force = true)
            Toast.makeText(this@SettingsActivity, R.string.opt_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
