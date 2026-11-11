package net.mspanc.twinsenradio.phone

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlin.math.roundToInt

/** Only the legacy controller changes; the SDK chooser and system switcher remain standard. */
internal object PhoneCastReceiverDialogFactory : MediaRouteDialogFactory() {
    override fun onCreateControllerDialogFragment(): MediaRouteControllerDialogFragment =
        PhoneCastReceiverDialogFragment()
}

class PhoneCastReceiverDialogFragment : MediaRouteControllerDialogFragment() {
    override fun onCreateControllerDialog(context: Context, savedInstanceState: Bundle?):
        MediaRouteControllerDialog = PhoneCastReceiverDialog(context)
}

internal class PhoneCastReceiverDialog(context: Context) : MediaRouteControllerDialog(context) {
    private var cast: CastSession? = null
    private var client: RemoteMediaClient? = null
    private var slider: SeekBar? = null
    private var label: TextView? = null
    private val volumeListener = object : Cast.Listener() {
        override fun onVolumeChanged() = refresh()
    }
    private val mediaListener = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = refresh()
        override fun onMetadataUpdated() = refresh()
    }

    init { setVolumeControlEnabled(false) }

    override fun onCreateMediaControlView(savedInstanceState: Bundle?): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).roundToInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply { tag = "cast_receiver_volume_label" })
            addView(SeekBar(context).apply {
                tag = "cast_receiver_volume"
                contentDescription = "Receiver volume"
                max = 50
                isEnabled = false
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) {
                        if (fromUser) writeVolume(value)
                    }
                    override fun onStartTrackingTouch(view: SeekBar) = Unit
                    override fun onStopTrackingTouch(view: SeekBar) = Unit
                })
            })
        }

    override fun onStart() {
        super.onStart()
        slider = mediaControlView?.findViewWithTag("cast_receiver_volume")
        label = mediaControlView?.findViewWithTag("cast_receiver_volume_label")
        try {
            cast = CastContext.getSharedInstance(context).sessionManager.currentCastSession
            client = cast?.remoteMediaClient
            cast?.addCastListener(volumeListener)
            client?.registerCallback(mediaListener)
        } catch (error: RuntimeException) {
            android.util.Log.w("PhoneCastReceiverDialog", "Receiver listeners unavailable", error)
        }
        refresh()
    }

    override fun onStop() {
        try { cast?.removeCastListener(volumeListener) }
        catch (error: RuntimeException) {
            android.util.Log.w("PhoneCastReceiverDialog", "Receiver listener detach failed", error)
        }
        try { client?.unregisterCallback(mediaListener) }
        catch (error: RuntimeException) {
            android.util.Log.w("PhoneCastReceiverDialog", "Media listener detach failed", error)
        }
        cast = null; client = null; slider = null; label = null
        super.onStop()
    }

    private fun ownedReceiver(): CastSession? {
        val bound = cast ?: return null
        val remote = client ?: return null
        return try {
            val state = remote.mediaStatus?.playerState
            bound.takeIf {
                it.isConnected && it === CastContext.getSharedInstance(context)
                    .sessionManager.currentCastSession && it.remoteMediaClient === remote &&
                    state in listOf(MediaStatus.PLAYER_STATE_PLAYING, MediaStatus.PLAYER_STATE_PAUSED,
                        MediaStatus.PLAYER_STATE_BUFFERING) &&
                    PhoneCastPolicy.ownsRemoteMedia(remote.mediaInfo?.customData?.optString("app")) &&
                    it.volume.isFinite()
            }
        } catch (error: RuntimeException) {
            android.util.Log.w("PhoneCastReceiverDialog", "Receiver state unavailable", error)
            null
        }
    }

    private fun refresh() {
        val receiver = ownedReceiver()
        slider?.isEnabled = receiver != null
        label?.text = if (receiver == null) "Receiver volume unavailable" else "Receiver volume"
        if (receiver != null) {
            try { slider?.progress = (receiver.volume * 50).roundToInt().coerceIn(0, 50) }
            catch (error: RuntimeException) {
                slider?.isEnabled = false
                android.util.Log.w("PhoneCastReceiverDialog", "Receiver volume unavailable", error)
            }
        }
    }

    private fun writeVolume(value: Int) {
        val receiver = ownedReceiver()
        if (receiver == null) { refresh(); return }
        try { receiver.volume = value.coerceIn(0, 50) / 50.0 }
        catch (error: RuntimeException) {
            android.util.Log.w("PhoneCastReceiverDialog", "Receiver volume command failed", error)
            refresh()
        }
    }

    // The base dialog routes volume keys to the aggregate even when its slider is disabled.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyDown(keyCode, event)
        }
        val view = slider
        if (view?.isEnabled == true) {
            val value = (view.progress + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                .coerceIn(0, 50)
            view.progress = value
            writeVolume(value)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true
        else super.onKeyUp(keyCode, event)
}
