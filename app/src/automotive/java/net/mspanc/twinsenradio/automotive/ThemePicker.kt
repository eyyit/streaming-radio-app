package net.mspanc.twinsenradio.automotive

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.mspanc.twinsenradio.data.AppearanceTheme

/** Approved ROYGBIV accent picker. Each row is one accessible preset choice. */
object ThemePicker {
    private var activeClose: ((Boolean) -> Unit)? = null

    /** Close the active theme picker when the activity handles a back press. */
    fun handleBack(): Boolean = close(notifyClosed = true)

    /** Remove an active picker during host teardown. */
    fun close(notifyClosed: Boolean = false): Boolean {
        val close = activeClose ?: return false
        activeClose = null
        close(notifyClosed)
        return true
    }

    fun show(
        activity: Activity,
        changed: () -> Unit,
        closed: () -> Unit,
        host: ViewGroup? = null
    ) {
        close()

        fun dp(value: Int) =
            (value * activity.resources.displayMetrics.density).toInt()

        fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

        val dialog = if (host == null) Dialog(activity) else null
        dialog?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var attached: View? = null

        var isClosed = false
        var closeAction: ((Boolean) -> Unit)? = null

        fun finish(notifyClosed: Boolean, dismissDialog: Boolean) {
            if (isClosed) return
            isClosed = true
            if (activeClose === closeAction) activeClose = null
            if (host != null) {
                attached?.let { host.removeView(it) }
                attached = null
            } else if (dismissDialog) {
                dialog?.setOnDismissListener(null)
                dialog?.dismiss()
            }
            if (notifyClosed) closed()
        }
        val action: (Boolean) -> Unit = { notify -> finish(notify, true) }
        closeAction = action
        activeClose = action

        val surface = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(20))
            background = rounded(AutomotiveDesign.SURFACE, 24)
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(activity).apply {
            text = "Theme"
            textSize = 27f
            setTextColor(AutomotiveDesign.TEXT)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))

        val done = TextView(activity).apply {
            text = "Done"
            textSize = AutomotiveDesign.BUTTON_TEXT_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { finish(true, true) }
        }
        header.addView(done, LinearLayout.LayoutParams(-2, dp(48)))
        surface.addView(header)
        surface.addView(TextView(activity).apply {
            text = "Choose an accent preset"
            textSize = 16f
            setTextColor(AutomotiveDesign.MUTED)
        }, LinearLayout.LayoutParams(-1, dp(28)))

        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            clipToPadding = false
            addView(rows)
        }
        surface.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(12)
        })

        val rowViews = mutableListOf<ThemeRow>()
        fun refresh() {
            val theme = AppearanceTheme.current(activity)
            done.setTextColor(theme.onAccent)
            done.background = RippleDrawable(
                ColorStateList.valueOf(0x30ffffff),
                rounded(theme.accent, 22),
                null
            )
            rowViews.forEach { row ->
                row.isSelected = row.preset.id == theme.id
                row.contentDescription = "${row.preset.name} theme" +
                    if (row.isSelected) ", selected" else ""
                row.invalidate()
            }
        }

        AppearanceTheme.presets.forEach { preset ->
            val row = ThemeRow(activity, preset).apply {
                isFocusable = true
                setOnClickListener {
                    AppearanceTheme.select(activity, preset.id)
                    refresh()
                    changed()
                }
            }
            rowViews += row
            rows.addView(row, LinearLayout.LayoutParams(-1, dp(62)).apply {
                bottomMargin = dp(8)
            })
        }

        if (host != null) {
            val layer = FrameLayout(activity).apply {
                setBackgroundColor(AutomotiveDesign.CANVAS)
                addView(surface, FrameLayout.LayoutParams(-1, -1).apply {
                    setMargins(dp(22), dp(16), dp(22), dp(16))
                })
            }
            attached = layer
            host.addView(layer, ViewGroup.LayoutParams(-1, -1))
        } else {
            dialog?.setContentView(surface)
            dialog?.setCanceledOnTouchOutside(false)
            dialog?.setOnDismissListener { finish(true, false) }
            dialog?.show()
            dialog?.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                    (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
                )
            }
        }
        refresh()
    }

    private class ThemeRow(
        activity: Activity,
        val preset: AppearanceTheme.Preset
    ) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val heart =
            "M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78L12 21.23l8.84-8.84a5.5 5.5 0 0 0 0-7.78Z"

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val centerY = height / 2f
            fun x(referenceX: Float) = width * (referenceX / 672f)
            fun d(value: Float) = value * density

            paint.style = Paint.Style.FILL
            paint.color = if (isSelected) {
                AutomotiveDesign.FIELD
            } else {
                AutomotiveDesign.CONTROL
            }
            canvas.drawRoundRect(
                0f, 0f, width.toFloat(), height.toFloat(), d(16f), d(16f), paint
            )

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d(if (isSelected) 2f else 1f)
            paint.color = if (isSelected) preset.accent else AutomotiveDesign.CONTROL
            canvas.drawRoundRect(
                d(1f), d(1f), width - d(1f), height - d(1f), d(16f), d(16f), paint
            )

            paint.style = Paint.Style.FILL
            paint.color = preset.accent
            val playX = x(43f)
            val pauseX = x(111f)
            val iconRadius = d(24f)
            canvas.drawCircle(playX, centerY, iconRadius, paint)
            canvas.drawCircle(pauseX, centerY, iconRadius, paint)
            paint.color = preset.onAccent
            canvas.drawPath(Path().apply {
                moveTo(playX - d(5f), centerY - d(9f))
                lineTo(playX + d(9f), centerY)
                lineTo(playX - d(5f), centerY + d(9f))
                close()
            }, paint)
            canvas.drawRoundRect(
                pauseX - d(6f), centerY - d(9f), pauseX - d(2f), centerY + d(9f),
                d(1f), d(1f), paint
            )
            canvas.drawRoundRect(
                pauseX + d(2f), centerY - d(9f), pauseX + d(6f), centerY + d(9f),
                d(1f), d(1f), paint
            )

            paint.color = preset.accent
            val switchX = x(214f)
            val switch = RectF(
                switchX - d(29f), centerY - d(15f),
                switchX + d(29f), centerY + d(15f)
            )
            canvas.drawRoundRect(switch, d(15f), d(15f), paint)
            paint.color = AutomotiveDesign.FIELD
            canvas.drawCircle(switchX + d(15f), centerY, d(11f), paint)

            canvas.save()
            canvas.translate(x(303f) - d(12f), centerY - d(12f))
            canvas.scale(d(1f), d(1f))
            paint.color = preset.accent
            androidx.core.graphics.PathParser.createPathFromPathData(heart)?.let {
                canvas.drawPath(it, paint)
            }
            canvas.restore()

            if (isSelected) {
                paint.color = preset.accent
                val checkX = x(616f)
                canvas.drawCircle(checkX, centerY, d(14f), paint)
                paint.color = preset.onAccent
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = d(2f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(Path().apply {
                    moveTo(checkX - d(5f), centerY)
                    lineTo(checkX - d(1f), centerY + d(4f))
                    lineTo(checkX + d(6f), centerY - d(4f))
                }, paint)
            }
        }
    }
}

