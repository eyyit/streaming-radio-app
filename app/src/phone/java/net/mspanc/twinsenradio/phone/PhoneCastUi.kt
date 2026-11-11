package net.mspanc.twinsenradio.phone

import android.content.Intent
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import net.mspanc.twinsenradio.R

@UnstableApi
internal object PhoneCastUi {
    fun dockRow(activity: AppCompatActivity): View = summaryRow(activity, showTransport = false)

    fun settingsRow(activity: AppCompatActivity): View = summaryRow(activity, showTransport = true)

    private fun summaryRow(activity: AppCompatActivity, showTransport: Boolean): View {
        val routing = PhoneCastRouting.get(activity.applicationContext)
        val title = PhoneUi.text(activity, size = 15f).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val receiver = PhoneUi.text(activity, size = 13f, muted = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val openPlayer = {
            activity.startActivity(Intent(activity, PhonePlayerActivity::class.java))
        }
        val details = PhoneUi.column(activity).apply {
            minimumHeight = PhoneUi.dp(activity, 48)
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(receiver)
            if (showTransport) {
                setOnClickListener { openPlayer() }
                isFocusable = true
            }
        }
        val transport = if (showTransport) {
            PhoneUi.icon(activity, R.drawable.ic_transport_play, "Play").apply {
                val padding = PhoneUi.dp(activity, 15)
                setPadding(padding, padding, padding, padding)
                setOnClickListener { routing.controller.toggleRemotePlayback() }
            }
        } else null
        val row = PhoneUi.row(activity).apply {
            tag = "phone_cast_summary"
            visibility = View.GONE
            minimumHeight = PhoneUi.dp(activity, 52)
            background = PhoneUi.shape(activity, PhoneUi.SURFACE)
            setPadding(PhoneUi.dp(activity, 12), 0, PhoneUi.dp(activity, 8), 0)
            addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            if (transport != null) addView(transport)
            else addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_expand_more)
                imageTintList = ColorStateList.valueOf(PhoneUi.MUTED)
                rotation = -90f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(PhoneUi.dp(activity, 24), PhoneUi.dp(activity, 24)))
            if (transport == null) {
                setOnClickListener { openPlayer() }
                isFocusable = true
            }
        }
        val refresh: () -> Unit = {
            val cast = routing.displaySnapshot()
            row.visibility = if (cast == null) View.GONE else View.VISIBLE
            if (cast != null) {
                title.text = cast.title ?: "Streaming Radio"
                receiver.text = "Casting to ${cast.receiverName}"
                val openPlayerTarget = if (showTransport) details else row
                openPlayerTarget.contentDescription = "${title.text}, ${receiver.text}. Open player"
                if (transport != null) {
                    val playing = routing.controller.isRemotePlaying()
                    transport.setImageResource(if (playing) R.drawable.ic_transport_pause else R.drawable.ic_transport_play)
                    transport.contentDescription = if (playing) "Pause" else "Play"
                }
            }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                routing.listeners.add(refresh)
                refresh()
            }

            override fun onStop(owner: LifecycleOwner) {
                routing.listeners.remove(refresh)
            }
        })
        return row
    }

    fun introduce(activity: AppCompatActivity, button: MediaRouteButton) {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var context: CastContext? = null
            private val tip = PhoneCastIntroTip(activity, button)
            private val listener = CastStateListener { state ->
                tip.setAvailable(state == CastState.NOT_CONNECTED)
            }

            override fun onResume(owner: LifecycleOwner) {
                runCatching {
                    context = CastContext.getSharedInstance(activity)
                    context?.addCastStateListener(listener)
                    tip.setAvailable(context?.castState == CastState.NOT_CONNECTED)
                    button.doOnLayout { tip.showIfAvailable() }
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                runCatching { context?.removeCastStateListener(listener) }
                context = null
                tip.setAvailable(false)
            }
        })
    }
}
