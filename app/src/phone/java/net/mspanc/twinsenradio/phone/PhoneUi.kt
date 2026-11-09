package net.mspanc.twinsenradio.phone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import net.mspanc.twinsenradio.data.AppearanceTheme

internal object PhoneUi {
    val CANVAS = 0xff0d0e12.toInt()
    val SURFACE = 0xff20252c.toInt()
    val CONTROL = 0xff333a43.toInt()
    val FIELD = 0xff15191e.toInt()
    val TEXT = 0xfff4f5f7.toInt()
    val MUTED = 0xffbdc6d0.toInt()
    val WARNING = 0xffff8b8b.toInt()
    fun dp(c: Context, value: Int) = (value * c.resources.displayMetrics.density + .5f).toInt()
    fun shape(c: Context, color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radius).toFloat()
    }
    fun column(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(c: Context, value: String = "", size: Float = 16f, muted: Boolean = false) = TextView(c).apply {
        text = value; textSize = size; setTextColor(if (muted) MUTED else TEXT)
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    fun heading(c: Context, value: String) = text(c, value, 24f).apply { setTypeface(typeface, Typeface.BOLD) }
    fun button(c: Context, value: String, primary: Boolean = false, action: () -> Unit) = Button(c).apply {
        text = value; textSize = 16f; isAllCaps = false
        minHeight = dp(c, 48); minimumHeight = dp(c, 48)
        val theme = AppearanceTheme.current(c)
        setTextColor(TEXT)
        includeFontPadding = false
        backgroundTintList = null
        background = shape(c, if (primary) theme.accent else CONTROL)
        setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
        setOnClickListener { action() }
    }
    fun icon(c: Context, drawable: Int, name: String, size: Int = 48) = ImageButton(c).apply {
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
        setImageResource(drawable); imageTintList = ColorStateList.valueOf(TEXT)
        background = null; contentDescription = name
        setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
    }
    fun divider(c: Context) = View(c).apply {
        setBackgroundColor(CONTROL); layoutParams = LinearLayout.LayoutParams(-1, dp(c, 1))
    }
    fun gap(c: Context, height: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c, height)) }
}
