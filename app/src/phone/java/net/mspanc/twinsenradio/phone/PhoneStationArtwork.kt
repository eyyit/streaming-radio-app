package net.mspanc.twinsenradio.phone

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** A station-specific fallback when the playlist supplies no usable logo. */
internal class PhoneStationArtwork(name: String) : Drawable() {
    private val initials = name.split(Regex("\\s+")).mapNotNull { word ->
        word.firstOrNull { it.isLetterOrDigit() }
    }.take(2).joinToString("").uppercase().ifBlank { "FM" }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PhoneUi.TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(bounds)
        canvas.drawColor(PhoneUi.SURFACE)
        paint.textSize = minOf(bounds.width(), bounds.height()) * .36f
        val baseline = bounds.exactCenterY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(initials, bounds.exactCenterX(), baseline, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun toString() = "station-initials:$initials"
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Drawable opacity is unused")
    override fun getOpacity() = PixelFormat.OPAQUE
}
