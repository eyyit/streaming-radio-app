package net.mspanc.twinsenradio.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaRouter2
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.mediarouter.media.MediaRouter
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import kotlin.math.roundToInt
import net.mspanc.twinsenradio.R

/** Own volume provider uses receiver state, never the MR2 group aggregate. */
@OptIn(UnstableApi::class)
class PhoneCastNativeSession(
    private val context: Context,
    private val ownsMedia: () -> Boolean,
    private val playing: () -> Boolean,
    private val toggle: () -> Unit,
    private val current: () -> CastSession? = {
        CastContext.getSharedInstance(context).sessionManager.currentCastSession
    },
    private val routingId: (CastSession) -> String? = { cast ->
        if (Build.VERSION.SDK_INT < 30) null else MediaRouter2.getInstance(context).controllers
            .firstOrNull { controller -> controller.selectedRoutes.any {
                it.id.endsWith(":" + cast.castDevice?.deviceId)
            } }?.id
    },
    private val endSession: (Boolean) -> Unit = { stopReceiver ->
        CastContext.getSharedInstance(context).sessionManager.endCurrentSession(stopReceiver)
    },
    private val canDisconnectAndKeepPlaying: (CastSession) -> Boolean = {
        try {
            MediaRouter.getInstance(context).selectedRoute
                ?.mediaRouteDescriptor?.canDisconnectAndKeepPlaying() == true
        } catch (error: RuntimeException) {
            android.util.Log.w("PhoneCastNativeSession", "Route capability unavailable", error)
            false
        }
    }
) {
    internal data class DisplaySnapshot(
        val receiverName: String,
        val title: String?,
        val stationId: String?
    )

    internal var session: MediaSession? = null
        private set
    internal var volume: VolumeProvider? = null
        private set
    private var attached: CastSession? = null
    private var attachedClient: com.google.android.gms.cast.framework.media.RemoteMediaClient? = null
    private var controlId: String? = null
    private var pendingVolume: Double? = null
    private var pendingAt = 0L
    private val listener = object : Cast.Listener() {
        override fun onVolumeChanged() { pendingVolume = null; sync() }
    }

    // Android Auto station search goes through RadioService.
    @android.annotation.SuppressLint("MissingOnPlayFromSearch")
    private inner class TransportCallback : MediaSession.Callback() {
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            // Android 8.0's platform callback can toggle on repeated key-down events.
            if (Build.VERSION.SDK_INT == 26 && intent.action == Intent.ACTION_MEDIA_BUTTON) {
                val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                if (event != null && mediaButton(event)) return true
            }
            return super.onMediaButtonEvent(intent)
        }

        override fun onPlay() = action("play")
        override fun onPause() = action("pause")
        override fun onStop() = action("stop")
        override fun onCustomAction(actionName: String, extras: android.os.Bundle?) {
            if (actionName == "disconnect" || actionName == "stop_casting") action(actionName)
        }
    }

    private fun owns(cast: CastSession): Boolean = ownsMedia() && PhoneCastPolicy.ownsRemoteMedia(
        cast.remoteMediaClient?.mediaInfo?.customData?.optString("app"))

    internal fun displaySnapshot(): DisplaySnapshot? {
        val cast = currentOwned() ?: return null
        val info = cast.remoteMediaClient?.mediaInfo
        val metadata = info?.metadata
        return DisplaySnapshot(
            receiverName = cast.castDevice?.friendlyName?.takeIf { it.isNotBlank() } ?: "Casting",
            title = metadata?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE)
                ?.takeIf { it.isNotBlank() },
            stationId = info?.customData?.optString("stationId")?.takeIf { it.isNotBlank() }
        )
    }

    fun sync() {
        val cast = try { current()?.takeIf { it.isConnected && owns(it) } }
        catch (error: RuntimeException) {
            android.util.Log.w("PhoneCastNativeSession", "Cast module unavailable", error)
            null
        }
        if (cast == null) {
            attached?.removeCastListener(listener); attached = null; attachedClient = null
            session?.release(); session = null; volume = null; controlId = null; pendingVolume = null
            context.stopService(Intent(context, PhoneCastPlaybackService::class.java))
            return
        }
        if (attached !== cast) {
            attached?.removeCastListener(listener)
            attached = cast
            pendingVolume = null
            cast.addCastListener(listener)
        }
        attachedClient = cast.remoteMediaClient
        val id = routingId(cast)
        val media = session ?: MediaSession(context, "PhoneCastNativeSession").also {
            session = it
            it.setCallback(TransportCallback())
        }
        val level = if (cast.isMute) 0 else (cast.volume * 50).roundToInt().coerceIn(0, 50)
        if (volume == null || controlId != id) {
            controlId = id
            volume = if (Build.VERSION.SDK_INT >= 30 && id != null) {
                object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 50, level, id) {
                    override fun onAdjustVolume(direction: Int) = adjust(direction)
                    override fun onSetVolumeTo(value: Int) = set(value)
                }
            } else {
                object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 50, level) {
                    override fun onAdjustVolume(direction: Int) = adjust(direction)
                    override fun onSetVolumeTo(value: Int) = set(value)
                }
            }
            media.setPlaybackToRemote(checkNotNull(volume))
        } else volume?.setCurrentVolume(level)
        val remote = cast.remoteMediaClient
        val title = remote?.mediaInfo?.metadata
            ?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE)
            ?: cast.castDevice?.friendlyName ?: "Casting"
        val receiverName = cast.castDevice?.friendlyName ?: "Casting"
        media.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, receiverName)
            .build())
        media.setSessionActivity(playerActivityPendingIntent())
        val routeAction = routeAction(cast)
        val state = PlaybackState.Builder().setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .addCustomAction(routeAction, routeActionLabel(routeAction),
                android.R.drawable.ic_menu_close_clear_cancel)
            .setState(if (playing()) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build()
        media.setPlaybackState(state)
        media.isActive = true
        ContextCompat.startForegroundService(context, Intent(context, PhoneCastPlaybackService::class.java))
    }

    internal fun adjust(direction: Int) = withOwned { cast ->
        when (direction) {
            AudioManager.ADJUST_MUTE -> cast.setMute(true)
            AudioManager.ADJUST_UNMUTE -> cast.setMute(false)
            AudioManager.ADJUST_TOGGLE_MUTE -> cast.setMute(!cast.isMute)
            AudioManager.ADJUST_RAISE, AudioManager.ADJUST_LOWER -> {
                if (cast.isMute) cast.setMute(false)
                val baseline = pendingVolume?.takeIf { SystemClock.elapsedRealtime() - pendingAt < 1000 }
                    ?: cast.volume
                requestVolume(cast, (baseline + direction / 50.0).coerceIn(0.0, 1.0))
            }
        }
    }

    internal fun set(value: Int) = withOwned { cast ->
        if (cast.isMute) cast.setMute(false)
        requestVolume(cast, value.coerceIn(0, 50) / 50.0)
    }

    private fun requestVolume(cast: CastSession, value: Double) {
        cast.volume = value
        pendingVolume = value
        pendingAt = SystemClock.elapsedRealtime()
    }

    private fun playerActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(context, 1,
        Intent(context, PhonePlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun currentOwned(): CastSession? = try {
        current()?.takeIf { it === attached && it.isConnected &&
            it.remoteMediaClient === attachedClient && owns(it) }
    } catch (error: RuntimeException) {
        android.util.Log.w("PhoneCastNativeSession", "Receiver ownership unavailable", error)
        null
    }

    fun mediaButton(event: android.view.KeyEvent): Boolean {
        if (currentOwned() == null) return false
        val command = when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> if (playing()) "pause" else "play"
            else -> return false
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) action(command)
        return true
    }

    private fun withOwned(block: (CastSession) -> Unit) {
        try {
            val cast = currentOwned() ?: return
            block(cast)
        } catch (error: Exception) {
            pendingVolume = null
            android.util.Log.e("PhoneCastNativeSession", "Receiver command failed", error)
        }
    }

    fun action(value: String?) = withOwned {
        when (value) {
            "play" -> if (!playing()) toggle()
            "pause" -> if (playing()) toggle()
            "stop" -> endSession(true)
            "disconnect", "stop_casting" -> {
                val expected = routeAction(it)
                if (value == expected) {
                    // The Cast SDK's non-forced end is sender-only when another sender
                    // owns the receiver. Never force-stop receiver playback here.
                    endSession(false)
                } else {
                    // Ignore an action rendered for an old route capability.
                    sync()
                }
            }
        }
    }

    private fun routeAction(cast: CastSession): String =
        if (canDisconnectAndKeepPlaying(cast)) "disconnect" else "stop_casting"

    private fun routeActionLabel(action: String): String =
        if (action == "disconnect") "Disconnect" else "Stop casting"

    fun notification(): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("cast_playback", "Casting", NotificationManager.IMPORTANCE_LOW))
        val command = if (playing()) "pause" else "play"
        val intent = PendingIntent.getBroadcast(context, 0,
            Intent(context, PhoneCastNotificationReceiver::class.java).setAction(command),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val actionIcon = Icon.createWithResource(context,
            if (playing()) R.drawable.ic_transport_pause else R.drawable.ic_transport_play)
        val routeAction = currentOwned()?.let(::routeAction) ?: "stop_casting"
        val routeCommand = PendingIntent.getBroadcast(context, 1,
            Intent(context, PhoneCastNotificationReceiver::class.java).setAction(routeAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val receiverName = currentOwned()?.castDevice?.friendlyName ?: "Casting"
        return Notification.Builder(context, "cast_playback").setSmallIcon(R.drawable.ic_transport_play)
            .setContentTitle(session?.controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: "Casting").setContentText(receiverName)
            .setContentIntent(playerActivityPendingIntent())
            .setOngoing(playing()).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(actionIcon, if (playing()) "Pause" else "Play", intent).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                routeActionLabel(routeAction), routeCommand).build())
            .setStyle(Notification.MediaStyle()
                .setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1)).build()
    }
}
