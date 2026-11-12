package net.mspanc.twinsenradio.phone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import kotlin.math.cos
import kotlin.math.sin

/** Projects prepared panes so each animation frame only composites images. */
internal object PhoneWheelTransition {
    private const val DURATION_MS = 310L
    private const val MAX_BITMAP_PIXELS = 600_000L
    private const val EXPIRY_MS = 2_000L

    private data class Pending(
        val destination: String,
        val bitmap: Bitmap?,
        val sourceBounds: RectF?,
        val enteringFromTop: Boolean,
        val fadeOutgoing: Boolean,
        val shared: List<Snapshot?>,
        val browseControls: Snapshot?,
        val createdAt: Long,
    )
    private data class Snapshot(val image: Bitmap, val bounds: RectF)

    private var pending: Pending? = null

    /** Captures only the outgoing pane, downscaled to a bounded software bitmap. */
    fun prepare(
        source: View?, destination: String, enteringFromTop: Boolean,
        fadeOutgoing: Boolean = false, sharedViews: List<View> = emptyList(),
        browseControls: View? = null,
    ) {
        val pane = source?.takeIf { it.width > 0 && it.height > 0 }
        val shared = sharedViews.map(::snapshot)
        val bitmap = pane?.let { withoutSharedViews(sharedViews) { capture(it) } }
        pending = Pending(destination, bitmap, pane?.let(::screenBounds), enteringFromTop,
            fadeOutgoing, shared, browseControls?.let(::snapshot), SystemClock.uptimeMillis())
    }

    /** Starts before the first destination frame, using its actual laid-out live view. */
    fun playIncoming(
        activity: ComponentActivity,
        livePanel: View,
        sharedViews: List<View>,
        browseControls: View?,
        onMoving: (Boolean) -> Unit = {},
        ready: () -> Boolean = { true },
    ) {
        val transition = pending ?: return
        pending = null
        if (SystemClock.uptimeMillis() - transition.createdAt > EXPIRY_MS ||
            transition.destination != activity.javaClass.name
        ) return

        val root = activity.window.decorView as? ViewGroup ?: return
        livePanel.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!activity.isFinishing && !ready()) return false
                livePanel.viewTreeObserver.removeOnPreDrawListener(this)
                if (!activity.isFinishing && livePanel.width > 0 && livePanel.height > 0) {
                    animate(activity, root, livePanel, transition, sharedViews, browseControls, onMoving)
                }
                return true
            }
        })
    }

    private fun screenBounds(view: View): RectF {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0].toFloat()
        val y = location[1].toFloat()
        return RectF(x, y, x + view.width, y + view.height)
    }

    private fun snapshot(view: View): Snapshot? =
        if (view.isShown && view.alpha > 0f && view.width > 0 && view.height > 0)
            capture(view)?.let { Snapshot(it, screenBounds(view)) } else null

    private inline fun <T> withoutSharedViews(views: List<View>, draw: () -> T): T {
        val alphas = views.map { it.alpha }
        views.forEach { it.alpha = 0f }
        return try { draw() } finally {
            views.zip(alphas).forEach { (view, alpha) -> view.alpha = alpha }
        }
    }

    private fun capture(view: View): Bitmap? = runCatching {
        val scale = minOf(
            1f,
            kotlin.math.sqrt(MAX_BITMAP_PIXELS.toFloat() / (view.width.toLong() * view.height)),
        )
        val width = (view.width * scale).toInt().coerceAtLeast(1)
        val height = (view.height * scale).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                scale(width.toFloat() / view.width, height.toFloat() / view.height)
                view.draw(this)
            }
        }
    }.getOrNull()

    private fun animate(
        activity: ComponentActivity,
        root: ViewGroup,
        livePanel: View,
        pending: Pending,
        sharedViews: List<View>,
        browseControls: View?,
        onMoving: (Boolean) -> Unit,
    ) {
        val bitmap = pending.bitmap ?: return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val location = IntArray(2)
        val rootLocation = IntArray(2)
        livePanel.getLocationOnScreen(location)
        root.getLocationOnScreen(rootLocation)
        val left = (location[0] - rootLocation[0]).toFloat()
        val top = (location[1] - rootLocation[1]).toFloat()
        val width = livePanel.width.toFloat()
        val height = livePanel.height.toFloat()
        val start = pending.sourceBounds ?: return
        val startLeft = start.left - rootLocation[0]
        val startTop = start.top - rootLocation[1]
        val startWidth = start.width()
        val startHeight = start.height()
        val direction = if (pending.enteringFromTop) -1f else 1f
        val originalAlpha = livePanel.alpha
        val shared = if (pending.shared.any { it != null }) sharedViews.map(::snapshot) else emptyList()
        val paired = pending.shared.zip(shared).mapIndexedNotNull { index, (from, to) ->
            if (from != null && to != null) Triple(sharedViews[index], from, to) else null
        }
        val hidden = paired.map { (view, _, _) -> view to view.alpha }
        val incomingControls = browseControls?.let(::snapshot)
        val controlsAlpha = browseControls?.alpha
        val controls = incomingControls ?: pending.browseControls
        val controlsTravel = PhoneUi.dp(activity, 12).toFloat()
        val listTravel = PhoneUi.dp(activity, 26).toFloat()
        onMoving(true)
        val incoming = withoutSharedViews(hidden.map { it.first }) { capture(livePanel) } ?: run {
            onMoving(false)
            return
        }

        val face = object : View(activity) {
            val bounds = RectF()
            val local = RectF(0f, 0f, width, height)
            val source = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
            val points = FloatArray(8)
            val projection = Matrix()
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val outgoingBounds = RectF(startLeft, startTop, startLeft + startWidth, startTop + startHeight)
            val incomingBounds = RectF(left, top, left + width, top + height)
            val sharedBounds = RectF()
            val movingBounds = RectF()
            var progress = 0f
            var elapsedFraction = 0f

            override fun onDraw(canvas: Canvas) {
                val t = progress
                val angle = t * (Math.PI.toFloat() / 2f)
                val blend = if (pending.fadeOutgoing) 1f else t
                val x = startLeft + (left - startLeft) * blend
                val y = startTop + (top - startTop) * blend
                val w = startWidth + (width - startWidth) * blend
                val h = startHeight + (height - startHeight) * blend
                bounds.set(x, y, x + w, y + h)
                val outgoingHeight = h * cos(angle).coerceAtLeast(0f)
                val incomingHeight = h * sin(angle).coerceAtLeast(0f)
                val seam = h / 2f + direction * h / 2f * (1f - 2f * t)
                if (pending.fadeOutgoing && elapsedFraction < 0.5f) {
                    paint.alpha = ((1f - elapsedFraction * 2f) * 255).toInt()
                    canvas.drawBitmap(bitmap, null, outgoingBounds, paint)
                    paint.alpha = 255
                }
                if (controls != null) {
                    val libraryProgress = if (incomingControls != null) elapsedFraction else 1f - elapsedFraction
                    val controlsProgress = reveal(libraryProgress, 0.24f, 0.87f)
                    val listProgress = reveal(libraryProgress, 0.34f, 0.97f)
                    val list = if (incomingControls != null) incoming else bitmap
                    val listBounds = if (incomingControls != null) incomingBounds else outgoingBounds
                    val controlsOffset = controlsTravel * (1f - controlsProgress)
                    val saved = canvas.save()
                    canvas.clipRect(listBounds.left, listBounds.top + controlsOffset, listBounds.right, listBounds.bottom)
                    movingBounds.set(listBounds)
                    movingBounds.offset(0f, listTravel * (1f - listProgress))
                    paint.alpha = (255 * listProgress).toInt()
                    canvas.drawBitmap(list, null, movingBounds, paint)
                    canvas.restoreToCount(saved)
                    movingBounds.set(controls.bounds)
                    movingBounds.offset(-rootLocation[0].toFloat(),
                        -rootLocation[1] + controlsOffset)
                    paint.alpha = (255 * controlsProgress).toInt()
                    canvas.drawBitmap(controls.image, null, movingBounds, paint)
                    paint.alpha = 255
                } else {
                    val saved = canvas.save()
                    canvas.clipRect(bounds)
                    val outgoingAbove = direction > 0f
                    if (!pending.fadeOutgoing) {
                        drawFace(canvas, seam, outgoingHeight, outgoingAbove, t) {
                            canvas.drawBitmap(bitmap, null, local, paint)
                        }
                    }
                    drawFace(canvas, seam, incomingHeight, !outgoingAbove, 1f - t) {
                        canvas.drawBitmap(incoming, null, local, paint)
                    }
                    canvas.restoreToCount(saved)
                }
                for ((view, from, to) in paired) {
                    val a = from.bounds
                    val b = to.bounds
                    sharedBounds.set(
                        a.left + (b.left - a.left) * t - rootLocation[0],
                        a.top + (b.top - a.top) * t - rootLocation[1],
                        a.right + (b.right - a.right) * t - rootLocation[0],
                        a.bottom + (b.bottom - a.bottom) * t - rootLocation[1],
                    )
                    paint.alpha = if (view is PhoneCarouselArtwork) 255 else (255 * (1f - t)).toInt()
                    canvas.drawBitmap(from.image, null, sharedBounds, paint)
                    paint.alpha = (255 * t).toInt()
                    canvas.drawBitmap(to.image, null, sharedBounds, paint)
                    paint.alpha = 255
                }
            }

            private fun reveal(progress: Float, start: Float, end: Float): Float {
                val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
                return t * t * (3f - 2f * t)
            }

            private inline fun drawFace(
                canvas: Canvas,
                seam: Float,
                faceHeight: Float,
                above: Boolean,
                depth: Float,
                draw: () -> Unit,
            ) {
                if (faceHeight < 1f) return
                val farInset = bounds.width() * 0.11f * depth
                val topInset = if (above) farInset else 0f
                val bottomInset = if (above) 0f else farInset
                val yTop = bounds.top + if (above) seam - faceHeight else seam
                val yBottom = yTop + faceHeight
                points[0] = bounds.left + topInset
                points[1] = yTop
                points[2] = bounds.right - topInset
                points[3] = yTop
                points[4] = bounds.right - bottomInset
                points[5] = yBottom
                points[6] = bounds.left + bottomInset
                points[7] = yBottom
                projection.setPolyToPoly(source, 0, points, 0, 4)
                val saved = canvas.save()
                canvas.concat(projection)
                draw()
                canvas.restoreToCount(saved)
            }
        }.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            layout(0, 0, root.width, root.height)
        }
        livePanel.alpha = 0f
        if (incomingControls != null) browseControls?.alpha = 0f
        // Artwork loads update alpha. Keep the actual destination out of the draw
        // pass until the morph reaches its final bounds.
        hidden.forEach { (view, _) ->
            if (view is PhoneCarouselArtwork) view.visibility = View.INVISIBLE else view.alpha = 0f
        }
        root.overlay.add(face)
        var cleaned = false
        val cleanup = {
            if (!cleaned) {
                cleaned = true
                root.overlay.remove(face)
                livePanel.alpha = originalAlpha
                if (controlsAlpha != null) browseControls?.alpha = controlsAlpha
                hidden.forEach { (view, alpha) ->
                    if (view is PhoneCarouselArtwork) view.visibility = View.VISIBLE else view.alpha = alpha
                }
                onMoving(false)
            }
        }
        val animator = ValueAnimator.ofFloat(0f, 1f).setDuration(DURATION_MS)
        val rotationEasing = PathInterpolator(0.22f, 0f, 0.12f, 1f)
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener {
            face.elapsedFraction = it.animatedFraction
            face.progress = rotationEasing.getInterpolation(face.elapsedFraction)
            face.invalidate()
        }
        val owner = WeakReference(activity)
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                animator.cancel()
                cleanup()
                owner.lifecycle.removeObserver(this)
            }
        }
        activity.lifecycle.addObserver(observer)
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                cleanup()
                owner.get()?.lifecycle?.removeObserver(observer)
            }
            override fun onAnimationCancel(animation: Animator) = cleanup()
        })
        animator.start()
    }
}
