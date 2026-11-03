package net.mspanc.twinsenradio.data

import android.content.Context

/** Local UI accents only; never changes radio/artist artwork or playback. */
object AppearanceTheme {
    data class Preset(val id: String, val name: String, val accent: Int, val onAccent: Int)

    const val DEFAULT_ID = "blue"
    private const val ON_ACCENT = 0xff07151d.toInt()

    val presets = listOf(
        Preset("red", "Red", 0xffff5b64.toInt(), ON_ACCENT),
        Preset("orange", "Orange", 0xffff963f.toInt(), ON_ACCENT),
        Preset("amber", "Yellow", 0xfff4d64f.toInt(), ON_ACCENT),
        Preset("green", "Green", 0xff43c98b.toInt(), ON_ACCENT),
        Preset("blue", "Blue", 0xff159fe0.toInt(), ON_ACCENT),
        Preset("indigo", "Indigo", 0xff8c91ff.toInt(), ON_ACCENT),
        Preset("violet", "Violet", 0xffc17cff.toInt(), ON_ACCENT)
    )

    fun current(context: Context): Preset {
        val selected = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .getString("accent", DEFAULT_ID)
        return presets.firstOrNull { it.id == selected } ?: presets.first {
            it.id == DEFAULT_ID
        }
    }

    fun select(context: Context, id: String) {
        require(presets.any { it.id == id })
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("accent", id).apply()
    }
}
