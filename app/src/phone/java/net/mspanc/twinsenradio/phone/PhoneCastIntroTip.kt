package net.mspanc.twinsenradio.phone

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.mediarouter.app.MediaRouteButton
import net.mspanc.twinsenradio.data.AppearanceTheme

/** Compact, app-owned first-use tip for the real Cast button. */
internal class PhoneCastIntroTip(
    private val activity: AppCompatActivity,
    private val anchor: MediaRouteButton
) {
    private val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var available = false
    private var popup: PopupWindow? = null
    private var previousForeground: Drawable? = null
    private var anchorHighlighted = false
    private var layoutListener: View.OnLayoutChangeListener? = null

    fun setAvailable(value: Boolean) {
        available = value
        if (value) showIfAvailable() else dismiss()
    }

    fun dismiss() {
        removeLayoutListener()
        popup?.dismiss()
    }

    fun showIfAvailable() {
        if (!available || popup != null || activity.isFinishing ||
            preferences.getBoolean(SHOWN, false)) return
        if (!anchor.isAttachedToWindow || !anchor.isLaidOut || !anchor.isShown ||
            !anchor.isEnabled || anchor.width == 0 || anchor.height == 0) {
            waitForVisibleAnchor()
            return
        }

        runCatching { show() }.onFailure { error ->
            Log.w(TAG, "Could not show the Cast introduction", error)
            popup?.dismiss()
            popup = null
            restoreAnchor()
        }
    }

    private fun show() {
        val theme = AppearanceTheme.current(activity)
        val root = anchor.rootView
        val margin = dp(16)
        val width = (root.width - 2 * margin).coerceAtMost(dp(288))
        if (width <= 0) return
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
            background = PhoneUi.shape(activity, theme.accent, 14)
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setAccessibilityPaneTitle(this, "Cast introduction")
        }
        content.addView(TextView(activity).apply {
            text = "Play on a speaker"
            textSize = 16f
            setTextColor(theme.onAccent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
        })
        content.addView(TextView(activity).apply {
            text = "Tap the Cast button to choose an available speaker."
            textSize = 14f
            setTextColor(theme.onAccent)
            includeFontPadding = false
            setPadding(0, dp(6), 0, dp(4))
        })
        val dismiss = TextView(activity).apply {
            text = "Got it"
            textSize = 14f
            setTextColor(theme.onAccent)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(64)
            isFocusable = true
            isClickable = true
            contentDescription = "Dismiss Cast introduction"
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { this@PhoneCastIntroTip.dismiss() }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_DOWN) {
                    this@PhoneCastIntroTip.dismiss()
                    true
                } else false
            }
        }
        content.addView(dismiss, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.END
        })

        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = content.measuredHeight
        val back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismiss()
        }
        val window = PopupWindow(content, width, popupHeight, Build.VERSION.SDK_INT >= 29).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            if (Build.VERSION.SDK_INT >= 29) isTouchModal = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setOnDismissListener {
                back.remove()
                restoreAnchor()
                popup = null
            }
        }
        val anchorPosition = IntArray(2).also(anchor::getLocationInWindow)
        val rootPosition = IntArray(2).also(root::getLocationInWindow)
        val anchorTop = anchorPosition[1] - rootPosition[1]
        val below = anchorTop + anchor.height + dp(8)
        val y = if (below + popupHeight <= root.height - margin) below
            else (anchorTop - popupHeight - dp(8)).coerceAtLeast(margin)
        val left = (anchorPosition[0] - rootPosition[0] + anchor.width - width)
            .coerceIn(margin, root.width - width - margin)
        val start = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) root.width - width - left else left

        previousForeground = anchor.foreground
        anchorHighlighted = true
        anchor.foreground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), theme.accent)
        }
        popup = window
        window.showAtLocation(root, Gravity.TOP or Gravity.START, start, y)
        activity.onBackPressedDispatcher.addCallback(activity, back)
        removeLayoutListener()
        preferences.edit().putBoolean(SHOWN, true).apply()
        dismiss.requestFocus()
        content.announceForAccessibility("Play on a speaker. Tap the Cast button to choose an available speaker.")
    }

    private fun restoreAnchor() {
        if (anchorHighlighted) anchor.foreground = previousForeground
        anchorHighlighted = false
        previousForeground = null
    }

    private fun waitForVisibleAnchor() {
        if (layoutListener != null || !anchor.isAttachedToWindow) return
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            showIfAvailable()
        }
        layoutListener = listener
        anchor.addOnLayoutChangeListener(listener)
        anchor.post { showIfAvailable() }
    }

    private fun removeLayoutListener() {
        layoutListener?.let(anchor::removeOnLayoutChangeListener)
        layoutListener = null
    }

    private fun dp(value: Int): Int = PhoneUi.dp(activity, value)

    private companion object {
        const val TAG = "PhoneCastIntroTip"
        const val PREFERENCES = "phone_cast_intro_tip"
        const val SHOWN = "shown"
    }
}
