package net.mspanc.twinsenradio.automotive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.PathParser
import kotlin.math.ceil

/** RM01: retain the approved refresh arc while withdrawing its arrow. */
class ArtworkLoadingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    // Visibility-only picker callers remain loading by default.
    private var loading = true
    private var activityStopped = false
    private var morphDrawable: RefreshMorphDrawable? = null

    init {
        scaleType = ScaleType.CENTER
        morphDrawable = RefreshMorphDrawable(resources.displayMetrics.density)
        setImageDrawable(morphDrawable)
    }

    /** Keep visible to morph continuously between idle refresh and real work. */
    fun setLoading(value: Boolean) { loading = value; syncAnimation() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncAnimation() }
    override fun onDetachedFromWindow() {
        morphDrawable?.suspendAnimation(loading)
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncAnimation()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncAnimation()
    }
    private fun syncAnimation() {
        val drawable = morphDrawable ?: return
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE && !activityStopped) {
            drawable.setLoading(loading)
        } else drawable.suspendAnimation(loading)
    }
    fun stopForActivity() {
        activityStopped = true
        morphDrawable?.suspendAnimation(loading)
    }
    fun resumeForActivity() { activityStopped = false; syncAnimation() }
}

private class RefreshMorphDrawable(density: Float) : Drawable() {
    // Exact ic_artwork_refresh paths, split only before the terminal straight shaft.
    private val body = PathParser.createPathFromPathData(
        "M20.457,15.078A9,9 0,1 1,12 3c2.52,0 4.93,1 6.74,2.74",
    )!!
    private val head = PathParser.createPathFromPathData("M21,3v5h-5")!!
    private val full = PathParser.createPathFromPathData(
        "M20.457,15.078A9,9 0,1 1,12 3c2.52,0 4.93,1 6.74,2.74L21,8 M21,3v5h-5",
    )!!
    private val shaft = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val size = (19f * density + .5f).toInt()
    private var arrow = 1f
    private var angle = 0f
    private var targetLoading = false
    private var running = false
    private var spin: ValueAnimator? = null
    private var transition: ValueAnimator? = null
    private var drawableAlpha = 255

    fun setLoading(loading: Boolean) {
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            suspendAnimation(loading)
            return
        }
        if (running && targetLoading == loading) return
        targetLoading = loading
        running = true
        transition?.cancel()
        val fromArrow = arrow
        val fromAngle = angle
        spin?.cancel()
        if (loading) {
            // ValueAnimator follows display timing, with no fixed frame timer.
            spin = ValueAnimator.ofFloat(fromAngle, fromAngle + 360f).apply {
                duration = 1620L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    angle = (it.animatedValue as Float) % 360f
                    invalidateSelf()
                }
                start()
            }
        }
        val endAngle = ceil(fromAngle / 360f) * 360f
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val eased = t * t * (3f - 2f * t)
                arrow = fromArrow + ((if (loading) 0f else 1f) - fromArrow) * eased
                if (!loading) angle = fromAngle + (endAngle - fromAngle) * eased
                invalidateSelf()
            }
            start()
        }
    }

    fun suspendAnimation(loading: Boolean) {
        spin?.cancel()
        transition?.cancel()
        spin = null
        transition = null
        running = false
        targetLoading = loading
        arrow = if (loading) 0f else 1f
        angle = 0f
        invalidateSelf()
    }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        canvas.rotate(angle, 12f, 12f)
        drawPass(canvas, 0x99000000.toInt(), 3.5f, .555f + .445f * arrow)
        drawPass(canvas, 0xfff5f2f3.toInt(), 2f, .8f + .2f * arrow)
        canvas.restoreToCount(save)
    }
    private fun drawPass(canvas: Canvas, color: Int, width: Float, opacity: Float) {
        paint.color = color
        paint.alpha = ((color ushr 24) * opacity * drawableAlpha / 255f).toInt()
        paint.strokeWidth = width
        if (arrow == 1f) { canvas.drawPath(full, paint); return }
        if (arrow <= 0f) { canvas.drawPath(body, paint); return }
        val x = 18.74f + 2.26f * arrow
        val y = 5.74f + 2.26f * arrow
        shaft.rewind()
        shaft.addPath(body)
        shaft.lineTo(x, y)
        canvas.drawPath(shaft, paint)
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.scale(arrow, arrow)
        canvas.translate(-21f, -8f)
        canvas.drawPath(head, paint)
        canvas.restoreToCount(save)
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Drawable opacity is unused")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

