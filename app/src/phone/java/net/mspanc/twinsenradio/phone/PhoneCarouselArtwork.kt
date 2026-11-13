package net.mspanc.twinsenradio.phone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.animation.doOnEnd
import androidx.core.graphics.createBitmap
import kotlin.math.abs

/** Draws the carousel inside the existing artwork bounds, leaving its touch target stationary. */
internal class PhoneCarouselArtwork @JvmOverloads constructor(
    context: Context,
    private val square: Boolean = false
) :
    AppCompatImageView(context) {
    private val matrix = Matrix()
    private val faceCorners = FloatArray(8)
    private val projectedCorners = FloatArray(8)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bounds = Rect()
    private val roundedFace = Path()
    private var restingSurface: Pair<Int?, Boolean>? = null
    private var stationId: String? = null
    private var position = 0f
    private var direction = 0
    private var expectedStep = 0
    private var expectedUntil = 0L
    private var oldCover: Bitmap? = null
    private var dragCover: Bitmap? = null
    private var oldDrawable: Drawable? = null
    private var incomingFallback: Drawable? = null
    private var keepPreviewForTurn = false
    private var adjacentCover: Drawable? = null
    private var previewDirection = 0
    private var previewStationId: String? = null
    private var motion: ValueAnimator? = null
    private var coverMoving = false
    private var panelMoving = false
    val isMoving: Boolean get() = coverMoving || panelMoving
    private var details: List<TextView> = emptyList()
    private var hiddenDetails: List<Pair<View, Int>>? = null
    private var pendingDetails: (() -> Unit)? = null
    private var artworkOverlay: View? = null
    private var overlayRoot: ViewGroup? = null
    private val artworkLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private var expandedViewport: View? = null
    private val viewportLocation = IntArray(2)
    private val faceBounds = RectF()
    private val release = Runnable { settle() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (square) {
            val side = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(side, side)
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = (background as? GradientDrawable)?.cornerRadius ?: PhoneUi.dp(context, 12).toFloat()
        roundedFace.reset()
        roundedFace.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
    }

    fun setAdjacentPreview(
        step: Int,
        adjacentStationId: String?,
        image: Drawable?,
    ) {
        previewDirection = step
        previewStationId = adjacentStationId
        adjacentCover = image
        artworkOverlay?.invalidate()
        invalidate()
    }

    fun attachDetails(vararg views: TextView) {
        details = views.toList()
    }

    fun useExpandedViewport(viewport: View) {
        expandedViewport = viewport
    }

    fun displayDetails(update: () -> Unit) {
        if (isMoving) pendingDetails = update else update()
    }

    fun setPanelMoving(moving: Boolean) {
        panelMoving = moving
        syncDetailsVisibility()
    }

    private fun syncDetailsVisibility() {
        updateCoverSurface()
        if (coverMoving && !panelMoving) {
            if (artworkOverlay == null) startArtworkOverlay()
            artworkOverlay?.invalidate()
        } else {
            removeArtworkOverlay()
        }
        // The panel transition draws its own text. A station turn keeps the
        // actual labels still and applies new metadata after the cover settles.
        if (panelMoving) {
            if (hiddenDetails != null) return
            hiddenDetails = details.map { it to it.visibility }
            details.filter { it.visibility == View.VISIBLE }.forEach { it.visibility = View.INVISIBLE }
        } else {
            hiddenDetails?.forEach { (view, visibility) -> view.visibility = visibility }
            hiddenDetails = null
            if (!coverMoving) {
                val update = pendingDetails
                pendingDetails = null
                update?.invoke()
            }
        }
    }

    private fun startArtworkOverlay() {
        val root = rootView as? ViewGroup ?: return
        val overlay = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                getLocationOnScreen(rootLocation)
                this@PhoneCarouselArtwork.getLocationOnScreen(artworkLocation)
                val x = (artworkLocation[0] - rootLocation[0]).toFloat()
                val y = (artworkLocation[1] - rootLocation[1]).toFloat()
                val saved = canvas.save()
                canvas.translate(x, y)
                val viewport = expandedViewport
                if (viewport != null) {
                    viewport.getLocationOnScreen(viewportLocation)
                    // The viewport owns the face. The cover is inset within it.
                    faceBounds.set(-x, 0f, root.width - x,
                        maxOf(this@PhoneCarouselArtwork.height.toFloat(),
                            (viewportLocation[1] + viewport.height - artworkLocation[1]).toFloat()))
                    canvas.clipRect(faceBounds.left,
                        maxOf(0f, (viewportLocation[1] - artworkLocation[1]).toFloat()),
                        faceBounds.right, (viewportLocation[1] + viewport.height - artworkLocation[1]).toFloat())
                } else {
                    faceBounds.set(0f, 0f, this@PhoneCarouselArtwork.width.toFloat(),
                        this@PhoneCarouselArtwork.height.toFloat())
                }
                drawCarousel(canvas)
                canvas.restoreToCount(saved)
            }
        }.apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            layout(0, 0, root.width, root.height)
        }
        artworkOverlay = overlay
        overlayRoot = root
        root.overlay.add(overlay)
    }

    private fun removeArtworkOverlay() {
        artworkOverlay?.let { overlayRoot?.overlay?.remove(it) }
        artworkOverlay = null
        overlayRoot = null
    }

    private fun updateCoverSurface() {
        if (isMoving && restingSurface == null) {
            restingSurface = background?.alpha to clipToOutline
            background?.alpha = 0
            clipToOutline = false
        } else if (!isMoving) {
            val (alpha, clipped) = restingSurface ?: return
            restingSurface = null
            alpha?.let { background?.alpha = it }
            clipToOutline = clipped
        }
    }

    fun drag(distance: Float, enabled: Boolean, turnDistance: Float = width.toFloat()) {
        if (!canAnimate(enabled) || oldCover != null) return
        removeCallbacks(release)
        stopMotion()
        if (position == 0f && distance != 0f && dragCover == null && drawable != null) {
            dragCover = captureCover()
        }
        val limit = if (adjacentCover == null) 0.82f else 1f
        position = (distance / turnDistance.coerceAtLeast(1f)).coerceIn(-limit, limit)
        coverMoving = position != 0f
        syncDetailsVisibility()
        postInvalidateOnAnimation()
    }

    fun expectStation(step: Int, enabled: Boolean) {
        if (!canAnimate(enabled)) return
        expectedStep = step
        expectedUntil = SystemClock.uptimeMillis() + 1_500
        removeCallbacks(release)
        postDelayed(release, 450)
    }

    fun settle() {
        if (oldCover != null) return
        if (position != 0f) animatePosition(0f, 220)
        else if (dragCover != null) reset()
    }

    fun stationChanged(id: String?, name: String?, enabled: Boolean) {
        if (id == stationId) return
        val previous = stationId
        stationId = id
        val expected = expectedStep.takeIf { it != 0 && SystemClock.uptimeMillis() <= expectedUntil }
        // A committed swipe follows the direction of the finger, even when
        // shuffled queue order places the selected station on the other side.
        val dragStep = dragCover?.takeIf { position != 0f }?.let {
            if (position > 0f) 1 else -1
        }
        val step = dragStep ?: expected ?: -1
        expectedStep = 0
        val start = position.takeIf { it * step > 0f } ?: 0f
        val outgoing = dragCover.takeIf { start != 0f }
        val preview = adjacentCover.takeIf { previewStationId == id && previewDirection == step }
        reset(revealDetails = false)
        val current = drawable
        if (previous == null || id == null || !canAnimate(enabled) || current == null) {
            coverMoving = false
            syncDetailsVisibility()
            return
        }

        oldCover = outgoing ?: captureCover()
        oldDrawable = current
        incomingFallback = preview ?: name?.let(::PhoneStationArtwork)
        keepPreviewForTurn = preview != null
        direction = step
        position = start
        coverMoving = true
        syncDetailsVisibility()
        // A full drag already placed the incoming face flat. Give the new
        // drawable one frame to bind before releasing the preview and labels.
        val duration = if (abs(start) == 1f) 16L else (420 - abs(start) * 150).toLong()
        animatePosition(step.toFloat(), duration, committed = true)
    }

    private fun captureCover(): Bitmap {
        val scale = minOf(1f, 512f / maxOf(width, height))
        val bitmap = createBitmap(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val surface = background
        val originalAlpha = surface?.alpha
        surface?.alpha = 0
        try {
            val canvas = Canvas(bitmap).apply { scale(scale, scale) }
            canvas.clipPath(roundedFace)
            drawCurrentCover(canvas)
        } finally {
            if (originalAlpha != null) surface?.alpha = originalAlpha
        }
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        if (artworkOverlay == null) {
            faceBounds.set(0f, 0f, width.toFloat(), height.toFloat())
            drawCarousel(canvas)
        }
    }

    // The face supplies clipping and backgrounds. Draw only the ImageView content.
    @android.annotation.SuppressLint("WrongCall")
    private fun drawCurrentCover(canvas: Canvas) = super.onDraw(canvas)

    private fun drawCarousel(canvas: Canvas) {
        val old = oldCover
        bounds.set(0, 0, width, height)
        if (old == null) {
            val frozen = dragCover
            drawFace(canvas, position) {
                if (frozen == null) drawCurrentCover(canvas) else canvas.drawBitmap(frozen, null, bounds, paint)
            }
            val step = previewDirection.takeIf { it != 0 && position * it > 0f }
            val adjacent = adjacentCover
            if (step != null && adjacent != null) {
                drawFace(canvas, position - step) {
                    drawPreview(canvas, adjacent)
                }
            }
            return
        }
        drawFace(canvas, position) { canvas.drawBitmap(old, null, bounds, paint) }
        drawFace(canvas, position - direction) {
            val fallback = incomingFallback
            if (fallback != null && (keepPreviewForTurn || drawable == null || drawable === oldDrawable)) {
                drawPreview(canvas, fallback)
            } else drawCurrentCover(canvas)
        }
    }

    private fun drawPreview(canvas: Canvas, image: Drawable) {
        val sourceWidth = image.intrinsicWidth
        val sourceHeight = image.intrinsicHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            image.bounds = bounds
            image.draw(canvas)
            return
        }
        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight
        val scale = if (scaleType == ScaleType.CENTER_CROP) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)
        val save = canvas.save()
        canvas.translate(
            kotlin.math.round((width - sourceWidth * scale) / 2f),
            kotlin.math.round((height - sourceHeight * scale) / 2f),
        )
        canvas.scale(scale, scale)
        image.setBounds(0, 0, sourceWidth, sourceHeight)
        image.draw(canvas)
        canvas.restoreToCount(save)
    }

    private inline fun drawFace(canvas: Canvas, offset: Float, draw: () -> Unit) {
        if (abs(offset) >= 1f) return
        val face = canvas.save()
        if (offset != 0f) {
            if (square && artworkOverlay != null) {
                // The projected hinge separates the two cover faces.
                val progress = if (offset > 0f) offset else 1f + offset
                val hinge = faceBounds.left + faceBounds.width() * progress
                if (offset > 0f) canvas.clipRect(hinge, faceBounds.top, faceBounds.right, faceBounds.bottom)
                else canvas.clipRect(faceBounds.left, faceBounds.top, hinge, faceBounds.bottom)
            }
            canvas.concat(faceProjection(offset))
        }
        val artwork = canvas.save()
        if (offset != 0f || isMoving || (clipToOutline && !canvas.isHardwareAccelerated)) {
            canvas.clipPath(roundedFace)
        }
        draw()
        canvas.restoreToCount(artwork)
        canvas.restoreToCount(face)
    }

    private fun faceProjection(offset: Float): Matrix {
        // Project both faces into this view's coordinates. Their meeting edges
        // share the same x coordinate while the outer edges recede in depth.
        val w = faceBounds.width()
        val h = faceBounds.height()
        val depth = abs(offset)
        val faceWidth = w * kotlin.math.cos(depth * Math.PI / 2).toFloat()
        val left = faceBounds.left + if (offset < 0f) w * (1f - depth) - faceWidth else w * depth
        val right = left + faceWidth
        val leftInset = h * (0.10f * depth - 0.10f * offset)
        val rightInset = h * (0.10f * depth + 0.10f * offset)
        faceCorners[0] = faceBounds.left
        faceCorners[2] = faceBounds.right
        faceCorners[4] = faceBounds.right
        faceCorners[5] = faceBounds.bottom
        faceCorners[6] = faceBounds.left
        faceCorners[7] = faceBounds.bottom
        projectedCorners[0] = left
        projectedCorners[1] = leftInset
        projectedCorners[2] = right
        projectedCorners[3] = rightInset
        projectedCorners[4] = right
        projectedCorners[5] = h - rightInset
        projectedCorners[6] = left
        projectedCorners[7] = h - leftInset
        matrix.setPolyToPoly(faceCorners, 0, projectedCorners, 0, 4)
        return matrix
    }

    private fun canAnimate(enabled: Boolean) =
        enabled && ValueAnimator.areAnimatorsEnabled() && isShown && isAttachedToWindow &&
            width > 0 && height > 0

    private fun animatePosition(end: Float, durationMillis: Long, committed: Boolean = false) {
        stopMotion()
        motion = ValueAnimator.ofFloat(position, end).apply {
            duration = durationMillis
            interpolator = if (committed) PathInterpolator(0.20f, 0.60f, 0.25f, 1f)
                else AccelerateDecelerateInterpolator()
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
                artworkOverlay?.invalidate()
            }
            doOnEnd { reset() }
        }
        motion?.start()
    }

    private fun stopMotion() {
        motion?.removeAllUpdateListeners()
        motion?.removeAllListeners()
        motion?.cancel()
        motion = null
    }

    private fun reset(revealDetails: Boolean = true) {
        removeCallbacks(release)
        stopMotion()
        position = 0f
        direction = 0
        oldCover = null
        dragCover = null
        oldDrawable = null
        incomingFallback = null
        keepPreviewForTurn = false
        adjacentCover = null
        previewDirection = 0
        previewStationId = null
        if (revealDetails) {
            coverMoving = false
            syncDetailsVisibility()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        expectedStep = 0
        panelMoving = false
        reset()
        super.onDetachedFromWindow()
    }
}
