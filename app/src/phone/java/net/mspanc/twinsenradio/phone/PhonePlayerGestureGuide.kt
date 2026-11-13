package net.mspanc.twinsenradio.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.mspanc.twinsenradio.data.AppearanceTheme

internal class PhonePlayerGestureGuide(
    context: Context,
    private val artwork: View,
    onDismiss: () -> Unit
) : FrameLayout(context) {
    private val theme = AppearanceTheme.current(context)
    private val bounds = RectF()
    private val artworkPosition = IntArray(2)
    private val guidePosition = IntArray(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrim = Path()
    private val arrows = Path()
    private val margin = dp(24)
    private val card = ScrollView(context).apply {
        background = PhoneUi.shape(context, PhoneUi.SURFACE, 20)
        isFillViewport = true
        addView(PhoneUi.column(context).apply {
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(PhoneUi.heading(context, "Player gestures"))
            addView(PhoneUi.gap(context, 12))
            instruction("← / →", "Swipe left for next, right for previous")
            addView(PhoneUi.gap(context, 8))
            instruction("↑ / ↓", "Swipe up or down to open the library")
            addView(PhoneUi.gap(context, 18))
            addView(PhoneUi.button(context, "Got it", primary = true, action = onDismiss).apply {
                setTextColor(theme.onAccent)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, -2))
        })
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        ViewCompat.setAccessibilityPaneTitle(this, "Player gestures")
        addView(card)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            requestLayout()
            insets
        }
    }

    private fun LinearLayout.instruction(symbol: String, message: String) {
        addView(PhoneUi.row(context).apply {
            addView(PhoneUi.text(context, symbol, 16f).apply {
                gravity = Gravity.CENTER
                setTextColor(0xff7adaff.toInt())
                background = PhoneUi.shape(context, PhoneUi.CONTROL, 8)
                setPadding(dp(4), dp(5), dp(4), dp(5))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(57), -2))
            addView(PhoneUi.text(context, message, 14f), LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(10)
            })
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        card.measure(
            MeasureSpec.makeMeasureSpec((width - 2 * margin).coerceIn(0, dp(460)), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((height - dp(80)).coerceAtLeast(0), MeasureSpec.AT_MOST)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        artwork.getLocationInWindow(artworkPosition)
        getLocationInWindow(guidePosition)
        val x = (artworkPosition[0] - guidePosition[0]).toFloat()
        val y = (artworkPosition[1] - guidePosition[1]).toFloat()
        bounds.set(x, y, x + artwork.width, y + artwork.height)
        val bars = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val minTop = (bars?.top ?: 0) + dp(8)
        val maxTop = (height - (bars?.bottom ?: 0) - dp(12) - card.measuredHeight).coerceAtLeast(minTop)
        val cardTop = (bounds.bottom.toInt() + dp(18)).coerceIn(minTop, maxTop)
        val cardLeft = (width - card.measuredWidth) / 2
        card.layout(cardLeft, cardTop, cardLeft + card.measuredWidth, cardTop + card.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val corner = dp(16).toFloat()
        scrim.rewind()
        scrim.fillType = Path.FillType.EVEN_ODD
        scrim.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        scrim.addRoundRect(bounds, corner, corner, Path.Direction.CW)
        paint.color = 0xb307090d.toInt()
        canvas.drawPath(scrim, paint)
        paint.color = 0x38070c17
        canvas.drawRoundRect(bounds, corner, corner, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.color = 0xff7adaff.toInt()
        canvas.drawRoundRect(bounds, corner, corner, paint)
        paint.style = Paint.Style.FILL

        val scale = minOf(bounds.width(), bounds.height()) / 280f
        if (scale <= 0f) return
        val saved = canvas.save()
        canvas.translate(bounds.centerX(), bounds.centerY())
        canvas.scale(scale, scale)
        paint.color = 0xff101d2d.toInt()
        canvas.drawRoundRect(-87f, -93f, 87f, 93f, 23f, 23f, paint)
        drawArrow(canvas, -1f, 0f, 0xff7adaff.toInt())
        drawArrow(canvas, 1f, 0f, 0xff7adaff.toInt())
        drawArrow(canvas, 0f, -1f, PhoneUi.TEXT)
        drawArrow(canvas, 0f, 1f, PhoneUi.TEXT)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(0f, 0f, 8f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12f
        canvas.drawText("Start on the cover art", 0f, 78f, paint)
        canvas.restoreToCount(saved)
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, color: Int) {
        arrows.rewind()
        arrows.moveTo(x * 18, y * 18)
        arrows.lineTo(x * 64, y * 64)
        arrows.moveTo(x * 54 - y * 9, y * 54 + x * 9)
        arrows.lineTo(x * 64, y * 64)
        arrows.lineTo(x * 54 + y * 9, y * 54 - x * 9)
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(arrows, paint)
        paint.style = Paint.Style.FILL
    }

    private fun dp(value: Int) = PhoneUi.dp(context, value)
}
