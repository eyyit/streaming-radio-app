/* SPDX-License-Identifier: MIT
 * Gradient loading indicator adapted from OpenAI LoadingIndicator, Copyright 2025 OpenAI.
 */
package net.mspanc.twinsenradio.phone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SweepGradient
import android.os.Build
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/** A non-interactive 32dp gradient ring for real phone artwork work. */
class PhoneGradientLoadingView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val strokeWidth = 2f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@PhoneGradientLoadingView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        shader = SweepGradient(
            0f,
            0f,
            intArrayOf(TRANSPARENT, WHITE),
            floatArrayOf(0f, 1f),
        )
    }
    private var idleIndicator: View? = null
    private var idleAlpha = 1f
    private var isLoading = false
    private var activityStopped = false
    private var rotation = 0f
    private var ringAnimator: ValueAnimator? = null
    private var transitionAnimator: ValueAnimator? = null
    private var ringAlpha = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        alpha = 0f
    }

    /** Crossfade this ring with the existing refresh button. */
    fun bindIdleIndicator(view: View) {
        if (idleIndicator === view) return
        idleIndicator?.alpha = idleAlpha
        idleIndicator = view
        idleAlpha = view.alpha
        view.alpha = if (isLoading) 0f else idleAlpha
        alpha = ringAlpha
    }

    /** Change visual state without taking click or accessibility ownership. */
    fun setLoading(value: Boolean, idleAlpha: Float = this.idleAlpha) {
        val clampedIdleAlpha = idleAlpha.coerceIn(0f, 1f)
        val changed = value != isLoading
        this.idleAlpha = clampedIdleAlpha
        if (!changed) {
            if (!value && transitionAnimator == null) {
                idleIndicator?.alpha = clampedIdleAlpha
            }
            syncAnimation()
            return
        }

        isLoading = value
        cancelTransition()
        val fromRing = ringAlpha
        val fromIdle = idleIndicator?.alpha ?: clampedIdleAlpha
        val toRing = if (value) 1f else 0f
        if (!canAnimate()) {
            ringAlpha = toRing
            alpha = toRing
            idleIndicator?.alpha = if (value) 0f else clampedIdleAlpha
            syncAnimation()
            return
        }
        val animator = ValueAnimator.ofFloat(0f, 1f)
        transitionAnimator = animator
        animator.duration = FADE_DURATION_MS
        animator.interpolator = android.view.animation.PathInterpolator(.25f, .1f, .25f, 1f)
        animator.addUpdateListener {
            val progress = it.animatedValue as Float
            ringAlpha = fromRing + (toRing - fromRing) * progress
            alpha = ringAlpha
            val toIdle = if (value) 0f else this@PhoneGradientLoadingView.idleAlpha
            idleIndicator?.alpha = fromIdle + (toIdle - fromIdle) * progress
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (transitionAnimator === animator) {
                    transitionAnimator = null
                    syncAnimation()
                }
            }
        })
        animator.start()
        syncAnimation()
    }

    fun stopForActivity() {
        activityStopped = true
        settleTransition()
        syncAnimation()
    }

    fun resumeForActivity() {
        activityStopped = false
        syncAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        settleTransition()
        stopRotation()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        handleVisibilityChange()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        handleVisibilityChange()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val diameter = min(width, height).toFloat()
        val radius = (diameter - strokeWidth) / 2f
        if (radius <= 0f) return

        val save = canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(rotation - 90f)
        canvas.drawCircle(0f, 0f, radius, paint)
        canvas.drawCircle(radius, 0f, strokeWidth / 2f, capPaint)
        canvas.restoreToCount(save)
    }

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        style = Paint.Style.FILL
    }

    private fun syncAnimation() {
        if (shouldAnimate() && animatorsEnabled()) startRotation() else stopRotation()
    }

    private fun shouldAnimate() = (isLoading || ringAlpha > 0f) && !activityStopped &&
        isAttachedToWindow && isShown && windowVisibility == VISIBLE

    private fun canAnimate() = shouldAnimate() && animatorsEnabled()

    private fun handleVisibilityChange() {
        if (!shouldAnimate()) settleTransition()
        syncAnimation()
    }

    private fun settleTransition() {
        cancelTransition()
        ringAlpha = if (isLoading) 1f else 0f
        alpha = ringAlpha
        idleIndicator?.alpha = if (isLoading) 0f else idleAlpha
    }

    private fun cancelTransition() {
        val animator = transitionAnimator
        transitionAnimator = null
        animator?.cancel()
    }

    private fun animatorsEnabled() = Build.VERSION.SDK_INT < 26 ||
        ValueAnimator.areAnimatorsEnabled()

    private fun startRotation() {
        if (ringAnimator?.isStarted == true) return
        ringAnimator = ValueAnimator.ofFloat(rotation, rotation + 360f).apply {
            duration = ROTATION_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotation = (it.animatedValue as Float) % 360f
                invalidate()
            }
            start()
        }
    }

    private fun stopRotation() {
        ringAnimator?.cancel()
        ringAnimator = null
        rotation %= 360f
    }

    private companion object {
        const val WHITE = -1
        const val TRANSPARENT = 0x00ffffff
        const val FADE_DURATION_MS = 150L
        const val ROTATION_DURATION_MS = 800L
    }
}
